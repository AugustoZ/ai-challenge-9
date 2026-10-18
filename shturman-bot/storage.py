"""Общий слой хранилища и разбора сообщений Bot API для «Штурмана».

Используется двумя независимыми процессами:
    - ``tg_server.py`` — MCP-сервер (stdio), который в read-only режиме читает
      уже накопленный буфер сообщений (когда ``TG_BOT_TOKEN`` не задан);
    - ``bot_responder.py`` — единственный живой ``getUpdates``-поллер, который
      пишет сообщения в эту БД и отвечает пользователям через локальную LLM.

Дублирование логики работы с SQLite/FTS5 запрещено: здесь сосредоточены schema,
``TgStore`` и базовые хелперы разбора сообщений (текст/автор/чат). Каждый
процесс передаёт свою настройку целевого чата (``chat_matches``/``chat_label``),
а вот сам парсинг и работа с БД — в общем модуле.

Правила (общие для tg_server и bot_responder):
    * sqlite-вызовы синхронные, но все публичные методы ``TgStore`` — асинхронные
      с внутренним ``asyncio.Lock`` (на этом объёме — норма).
    * Все логи — в stderr; секреты (токен) сюда не попадают.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import sqlite3
import time
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional
from urllib.parse import quote

log = logging.getLogger("storage")

# httpx пишет каждый запрос с полным URL (в т.ч. /bot<TOKEN>/) на INFO.
# Отключаем INFO-логи httpx, чтобы токен не просачивался в stderr.
logging.getLogger("httpx").setLevel(logging.WARNING)

# Корень проекта: standalone-проект shturman-bot, файлы лежат в его корне.
_REPO_ROOT = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB_PATH = os.path.join(_REPO_ROOT, ".tg_messages.db")
DEFAULT_BUFFER_PATH = os.path.join(_REPO_ROOT, ".tg_buffer.jsonl")

RETENTION_INTERVAL = 6 * 3600  # сек: период фоновой очистки по retention

# Медиа-поля Bot API: наличие любого (и НЕ текстового) -> сообщение «медиа».
MEDIA_KEYS = (
    "photo", "video", "audio", "document", "animation", "voice", "video_note",
    "sticker", "contact", "location", "venue", "poll", "dice", "game",
)
# Служебные события Bot API (ставится в канал/группу, текста нет) — пропускаем.
SERVICE_KEYS = (
    "new_chat_members", "left_chat_member", "new_chat_title", "new_chat_photo",
    "delete_chat_photo", "group_chat_created", "supergroup_chat_created",
    "channel_chat_created", "message_auto_delete_timer_changed",
    "migrate_from_chat_id", "migrate_to_chat_id", "pinned_message",
    "connected_website", "video_chat_started", "video_chat_ended",
    "video_chat_participants_invited", "proximity_alert_triggered",
    "forum_topic_created", "forum_topic_closed", "forum_topic_reopened",
    "forum_topic_edited", "general_forum_topic_hidden",
    "general_forum_topic_unhidden", "boost_added",
)


def utc_from_ts(ts) -> datetime:
    """Unix-epoch -> aware datetime в UTC."""
    return datetime.fromtimestamp(ts, tz=timezone.utc)


def message_text(msg: dict) -> str:
    """Схлопнутый текст ИЛИ '[медиа]' ИЛИ '' (сервисное — не храним)."""
    for key in ("text", "caption"):
        val = msg.get(key)
        if isinstance(val, str) and val.strip():
            return re.sub(r"\s+", " ", val.strip())
    if any(k in msg for k in SERVICE_KEYS):
        return ""
    if any(k in msg for k in MEDIA_KEYS):
        return "[медиа]"
    return ""


def author_name(msg: dict) -> str:
    """Имя автора: from -> 'First Last'/'@username', либо title канала, либо str(id)."""
    sender = msg.get("from")
    chat = msg.get("chat") or {}
    if sender is None:
        # channel_post/анонимный пост: title канала или 'канал'.
        return chat.get("title") or "канал"
    first = sender.get("first_name") or ""
    last = sender.get("last_name") or ""
    name = " ".join(p for p in (first, last) if p).strip()
    if name:
        return name
    username = sender.get("username")
    if username:
        return f"@{username}"
    return chat.get("title") or str(sender.get("id", "?"))


def chat_matches_chat(chat: dict, target: str) -> bool:
    """Совпадение чата с target: числовой target -> по chat.id; иначе по username."""
    if not chat or not target:
        return False
    target = (target or "").strip()
    if target.isdigit():
        return str(chat.get("id")) == target
    target = target.lower().lstrip("@")
    username = (chat.get("username") or "").lower().lstrip("@")
    return username == target


def chat_label(chat: dict, target: str) -> str:
    """Идентификатор чата для поля `chat` записи: id при числовом target,
    иначе username (а если username нет — id)."""
    chat = chat or {}
    target = (target or "").strip()
    if target and target.isdigit():
        return str(chat.get("id", ""))
    username = chat.get("username")
    return username if username else str(chat.get("id", ""))


def entry_chat_matches(entry: dict, target: str) -> bool:
    """Совпадение `chat` записи записи с настроенным target."""
    target = (target or "").strip()
    if not target:
        return False
    ec = str(entry.get("chat") or "")
    if target.isdigit():
        return ec == target
    return ec.lower().lstrip("@") == target.lower().lstrip("@")


def entry_from_message(
    msg: dict,
    *,
    chat_match: Optional[Callable[[dict], bool]] = None,
    chat_labeller: Optional[Callable[[dict], str]] = None,
) -> Optional[dict]:
    """Строит запись буфера из сообщения или None (чужой чат / сервисное).

    ``chat_match`` — предикат (msg['chat']) -> bool; ``chat_labeller`` —
    (msg['chat']) -> str для поля `chat`. Если предикат не задан — чат не
    фильтруется (все сообщения хранятся).
    """
    if chat_match is not None and not chat_match(msg.get("chat")):
        return None
    text = message_text(msg)
    if not text:
        return None  # сервисное/пустое — не храним
    dt = utc_from_ts(msg.get("date", 0))
    lab = chat_labeller(msg.get("chat")) if chat_labeller is not None else str(msg.get("chat") or "")
    return {
        "_dt": dt,
        "date": dt.isoformat(),
        "author": author_name(msg),
        "text": text,
        "id": msg.get("message_id"),
        "chat": lab,
    }


# --------------------------------------------------------------------------
# Хранилище: SQLite + FTS5 (поиск), дедуп по (chat, message_id), WAL.
# --------------------------------------------------------------------------

_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS messages (
  chat TEXT NOT NULL,
  message_id INTEGER NOT NULL,
  ts REAL NOT NULL,
  author TEXT NOT NULL,
  text TEXT NOT NULL,
  PRIMARY KEY (chat, message_id)
);
CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(ts);
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(text, content='messages', content_rowid='rowid');
CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
  INSERT INTO messages_fts(rowid, text) VALUES (new.rowid, new.text);
END;
CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
END;
CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
-- Статистика респондера (@admin): вопросы/пользователи для панели.
CREATE TABLE IF NOT EXISTS bot_questions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  user_id INTEGER,
  username TEXT,
  display_name TEXT,
  question TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bot_questions_ts ON bot_questions(ts);
CREATE TABLE IF NOT EXISTS bot_users (
  user_id INTEGER PRIMARY KEY,
  username TEXT,
  display_name TEXT,
  first_seen REAL NOT NULL,
  last_seen REAL NOT NULL,
  questions INTEGER NOT NULL DEFAULT 0
);
-- События GitHub (наблюдатель PR, github_watcher): дедуп по (repo, number),
-- история для контекста LLM. Retention на неё не распространяется (мало строк).
CREATE TABLE IF NOT EXISTS github_prs (
  repo TEXT NOT NULL,
  number INTEGER NOT NULL,
  title TEXT NOT NULL,
  author TEXT NOT NULL,
  url TEXT NOT NULL,
  branch TEXT NOT NULL DEFAULT '',
  base TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  seen_ts REAL NOT NULL,
  PRIMARY KEY (repo, number)
);
"""


class TgStore:
    """Persistent-хранилище на SQLite (FTS5). Все операции под одним asyncio.Lock;
    sqlite-вызовы синхронные (на этом объёме — норма).

    ``chat_matcher`` — необязательный предикат (entry) -> bool для фильтрации
    выдачи записей при чтении (только настроенный чат). Если None — фильтр
    не применяется (материзуются все записи).
    """

    def __init__(
        self,
        db_path: str,
        retention_days: int,
        legacy_jsonl: str,
        chat_matcher: Optional[Callable[[dict], bool]] = None,
    ):
        self.db_path = db_path
        self.retention_days = retention_days
        self.legacy_jsonl = legacy_jsonl
        self.chat_matcher = chat_matcher
        # TG_DB_READONLY=1 — потребитель снимка БД (aiweb): файл смонтирован :ro,
        # коннект в mode=ro без WAL/schema/миграций/prune. Записи (insert/set_meta)
        # в этом режиме не вызываются: поллер у read-only tg_server не стартует.
        self.read_only = os.environ.get("TG_DB_READONLY") == "1"
        self.lock = asyncio.Lock()
        self._conn: sqlite3.Connection | None = None
        self._init_done = False
        self._migrated = False
        self._next_prune = 0.0

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except Exception:
                pass
            self._conn = None

    # -- инициализация / миграция / retention --

    def _ensure_conn_locked(self) -> None:
        if self._init_done:
            return
        if self.read_only:
            # mode=ro: read-only коннект не может открыть WAL-базу (нечем
            # создать -shm), поэтому снапшот заранее переводится в DELETE.
            uri = f"file:{quote(self.db_path)}?mode=ro"
            conn = sqlite3.connect(uri, uri=True, check_same_thread=False)
            conn.execute("PRAGMA busy_timeout=5000")
            self._conn = conn
            self._init_done = True
            return
        parent = os.path.dirname(os.path.abspath(self.db_path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.executescript(_SCHEMA_SQL)
        self._conn = conn
        self._init_done = True
        self._migrate_locked()
        self._prune_locked(force=True)

    def _migrate_locked(self) -> None:
        """Одноразовая миграция из legacy JSONL в пустую таблицу."""
        if self._migrated:
            return
        self._migrated = True
        count = self._conn.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
        if count > 0:
            return  # не пустая — миграция не нужна (повторный запуск no-op)
        src = self.legacy_jsonl
        if not src or not os.path.exists(src):
            return
        imported = 0
        for lineno, line in enumerate(_iter_jsonl(src), 1):
            try:
                entry = _parse_entry(line)
            except (ValueError, KeyError, TypeError):
                log.warning("storage: миграция: повреждённая строка %d пропущена", lineno)
                continue
            if entry["id"] is None:
                continue
            cur = self._conn.execute(
                "INSERT OR IGNORE INTO messages (chat, message_id, ts, author, text) "
                "VALUES (?, ?, ?, ?, ?)",
                (entry["chat"], entry["id"], entry["_dt"].timestamp(),
                 entry["author"], entry["text"]),
            )
            imported += cur.rowcount
        self._conn.commit()
        self._conn.execute("INSERT INTO messages_fts(messages_fts) VALUES ('rebuild')")
        self._conn.commit()
        try:
            os.replace(src, src + ".imported")
        except OSError as exc:
            log.error("storage: миграция: не переименован источник: %s", type(exc).__name__)
        log.info("storage: миграция: импортировано %d строк -> %s.imported",
                 imported, os.path.basename(src))

    def _prune_locked(self, force: bool = False) -> None:
        if self.retention_days <= 0:
            return
        now = datetime.now(timezone.utc).timestamp()
        if not force and now < self._next_prune:
            return
        self._next_prune = now + RETENTION_INTERVAL
        cutoff = now - self.retention_days * 86400
        cur = self._conn.execute("DELETE FROM messages WHERE ts < ?", (cutoff,))
        self._conn.commit()
        if cur.rowcount:
            log.info("storage: retention: удалено %d старых сообщений", cur.rowcount)

    # -- meta (key-value; используется responder для персистентного offset) --

    async def get_meta(self, key: str) -> str | None:
        async with self.lock:
            self._ensure_conn_locked()
            row = self._conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
            return row[0] if row else None

    async def set_meta(self, key: str, value: str) -> None:
        async with self.lock:
            self._ensure_conn_locked()
            self._conn.execute(
                "INSERT INTO meta (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )
            self._conn.commit()

    # -- GitHub PR (наблюдатель github_watcher) --

    async def known_prs(self) -> set[tuple[str, int]]:
        """Множество уже известных PR (repo, number) — дедуп наблюдателя."""
        async with self.lock:
            self._ensure_conn_locked()
            rows = self._conn.execute("SELECT repo, number FROM github_prs").fetchall()
            return {(str(repo), int(number)) for repo, number in rows}

    async def record_pr(self, entry: dict) -> bool:
        """Записывает PR; True — если запись новая (INSERT сработал)."""
        async with self.lock:
            self._ensure_conn_locked()
            cur = self._conn.execute(
                "INSERT OR IGNORE INTO github_prs "
                "(repo, number, title, author, url, branch, base, created_at, seen_ts) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (entry["repo"], entry["number"], entry["title"], entry["author"],
                 entry["url"], entry.get("branch", ""), entry.get("base", ""),
                 entry["created_at"], time.time()),
            )
            self._conn.commit()
            return cur.rowcount > 0

    async def recent_prs(self, limit: int = 8) -> list[dict]:
        """Последние увиденные PR (свежие первыми) — для истории/отладки."""
        async with self.lock:
            self._ensure_conn_locked()
            rows = self._conn.execute(
                "SELECT repo, number, title, author, url, branch, base, "
                "created_at, seen_ts FROM github_prs "
                "ORDER BY seen_ts DESC LIMIT ?",
                (limit,),
            ).fetchall()
            keys = ("repo", "number", "title", "author", "url", "branch",
                    "base", "created_at", "seen_ts")
            return [dict(zip(keys, row)) for row in rows]

    async def known_users_map(self) -> dict[str, int]:
        """{логин (lower) -> user_id} по bot_users — резолв админов в личку.

        Бот может писать пользователю только если тот начинал с ним диалог,
        поэтому в map попадают лишь «знающие» бота пользователи.
        """
        async with self.lock:
            self._ensure_conn_locked()
            rows = self._conn.execute(
                "SELECT lower(username), user_id FROM bot_users "
                "WHERE username IS NOT NULL"
            ).fetchall()
            return {str(u): int(uid) for u, uid in rows}


    # -- статистика респондера (@admin) --

    async def record_question(
        self,
        *,
        ts: float,
        user_id: int | None,
        username: str | None,
        display_name: str | None,
        question: str,
    ) -> None:
        """Записывает заданный боту вопрос и обновляет агрегат по пользователю."""
        async with self.lock:
            self._ensure_conn_locked()
            if self.read_only:
                return
            self._conn.execute(
                "INSERT INTO bot_questions (ts, user_id, username, display_name, question) "
                "VALUES (?, ?, ?, ?, ?)",
                (ts, user_id, username, display_name, question),
            )
            if user_id is not None:
                self._conn.execute(
                    "INSERT INTO bot_users (user_id, username, display_name, first_seen, last_seen, questions) "
                    "VALUES (?, ?, ?, ?, ?, 1) "
                    "ON CONFLICT(user_id) DO UPDATE SET "
                    "username = COALESCE(NULLIF(excluded.username, ''), bot_users.username), "
                    "display_name = COALESCE(NULLIF(excluded.display_name, ''), bot_users.display_name), "
                    "last_seen = excluded.last_seen, "
                    "questions = bot_users.questions + 1",
                    (user_id, username or "", display_name or "", ts, ts),
                )
            self._incr_meta_locked("stat_questions", 1)
            self._conn.commit()

    async def add_usage(
        self, *, prompt_tokens: int, completion_tokens: int, total_tokens: int
    ) -> None:
        """Прибавляет расход токенов LM к накопительным счётчикам в meta."""
        if not (prompt_tokens or completion_tokens or total_tokens):
            return
        async with self.lock:
            self._ensure_conn_locked()
            if self.read_only:
                return
            for key, val in (
                ("stat_prompt_tokens", prompt_tokens),
                ("stat_completion_tokens", completion_tokens),
                ("stat_total_tokens", total_tokens),
            ):
                if val:
                    self._incr_meta_locked(key, val)
            self._conn.commit()

    async def stats_summary(
        self, recent_users: int = 5, top_questions: int = 10
    ) -> dict:
        """Сводка для /admin: счётчики, последние пользователи, топ вопросов."""
        async with self.lock:
            self._ensure_conn_locked()

            def _meta_int(key: str) -> int:
                row = self._conn.execute(
                    "SELECT value FROM meta WHERE key = ?", (key,)
                ).fetchone()
                try:
                    return int(row[0]) if row and row[0] else 0
                except (TypeError, ValueError):
                    return 0

            totals = {
                "prompt_tokens": _meta_int("stat_prompt_tokens"),
                "completion_tokens": _meta_int("stat_completion_tokens"),
                "total_tokens": _meta_int("stat_total_tokens"),
                "questions": _meta_int("stat_questions"),
            }
            row = self._conn.execute(
                "SELECT value FROM meta WHERE key = 'bot_first_start'"
            ).fetchone()
            try:
                first_start = float(row[0]) if row and row[0] else None
            except (TypeError, ValueError):
                first_start = None
            users = self._conn.execute(
                "SELECT user_id, username, display_name, first_seen, last_seen, questions "
                "FROM bot_users ORDER BY last_seen DESC LIMIT ?",
                (recent_users,),
            ).fetchall()
            recent = [
                {"user_id": r[0], "username": r[1], "display_name": r[2],
                 "first_seen": r[3], "last_seen": r[4], "questions": r[5]}
                for r in users
            ]
            rows = self._conn.execute(
                "SELECT LOWER(TRIM(question)) AS k, COUNT(*) AS c, MIN(ts) AS first_ts "
                "FROM bot_questions GROUP BY k ORDER BY c DESC, first_ts ASC LIMIT ?",
                (top_questions,),
            ).fetchall()
            top: list[dict] = []
            for key, cnt, _first_ts in rows:
                rep = self._conn.execute(
                    "SELECT question FROM bot_questions WHERE LOWER(TRIM(question)) = ? "
                    "ORDER BY ts ASC LIMIT 1",
                    (key,),
                ).fetchone()
                top.append({"question": rep[0] if rep else key, "count": cnt})
            return {
                "totals": totals,
                "first_start": first_start,
                "recent_users": recent,
                "top_questions": top,
            }

    def _incr_meta_locked(self, key: str, amount: int) -> None:
        """Инкремент целочисленного значения в meta (под уже взятым lock)."""
        self._conn.execute(
            "INSERT INTO meta (key, value) VALUES (?, CAST(? AS TEXT)) "
            "ON CONFLICT(key) DO UPDATE SET value = CAST(CAST(value AS INTEGER) + ? AS TEXT)",
            (key, amount, amount),
        )

    # -- запись --

    async def insert(self, entry: dict) -> None:
        async with self.lock:
            self._ensure_conn_locked()
            self._prune_locked()
            self._conn.execute(
                "INSERT OR IGNORE INTO messages (chat, message_id, ts, author, text) "
                "VALUES (?, ?, ?, ?, ?)",
                (entry["chat"], entry["id"], entry["_dt"].timestamp(),
                 entry["author"], entry["text"]),
            )
            self._conn.commit()

    async def has_entries(self) -> bool:
        async with self.lock:
            self._ensure_conn_locked()
            self._prune_locked()
            return self._conn.execute("SELECT 1 FROM messages LIMIT 1").fetchone() is not None

    # -- чтение (окно/поиск) --

    async def window(self, hours: int, limit: int) -> list[dict]:
        async with self.lock:
            self._ensure_conn_locked()
            self._prune_locked()
            cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)
            rows = self._query_rows(cutoff.timestamp())
            return _limit_recent(rows, limit)

    async def search(self, query: str, hours: int, limit: int) -> list[dict]:
        async with self.lock:
            self._ensure_conn_locked()
            self._prune_locked()
            cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)
            cutoff_ts = cutoff.timestamp()
            rows = []
            if _fts_query(query):
                try:
                    rows = self._fts_rows(query, cutoff_ts)
                except sqlite3.OperationalError:
                    rows = []
            if not rows:
                rows = self._like_rows(query, cutoff_ts)
            return _limit_recent(rows, limit)

    def _query_rows(self, cutoff_ts: float) -> list[dict]:
        cur = self._conn.execute(
            "SELECT chat, message_id, ts, author, text FROM messages "
            "WHERE ts >= ? ORDER BY ts ASC",
            (cutoff_ts,),
        )
        out = []
        for row in cur.fetchall():
            entry = _row_to_entry(row)
            if self._matches(entry):
                out.append(entry)
        return out

    def _fts_rows(self, query: str, cutoff_ts: float) -> list[dict]:
        cur = self._conn.execute(
            "SELECT m.chat, m.message_id, m.ts, m.author, m.text "
            "FROM messages_fts JOIN messages m ON m.rowid = messages_fts.rowid "
            "WHERE messages_fts MATCH ? AND m.ts >= ? ORDER BY m.ts ASC",
            (_fts_query(query), cutoff_ts),
        )
        out = []
        for row in cur.fetchall():
            entry = _row_to_entry(row)
            if self._matches(entry):
                out.append(entry)
        return out

    def _like_rows(self, query: str, cutoff_ts: float) -> list[dict]:
        q = (query or "").strip().lower()
        return [e for e in self._query_rows(cutoff_ts) if q in e["text"].lower()]

    def _matches(self, entry: dict) -> bool:
        if self.chat_matcher is None:
            return True
        return bool(self.chat_matcher(entry))


def _row_to_entry(row) -> dict:
    chat, message_id, ts, author, text = row
    dt = datetime.fromtimestamp(ts, tz=timezone.utc)
    return {"_dt": dt, "date": dt.isoformat(), "author": author, "text": text,
            "id": message_id, "chat": chat}


def _limit_recent(rows: list[dict], limit: int) -> list[dict]:
    rows.sort(key=lambda e: e["_dt"])
    if len(rows) > limit:
        rows = rows[-limit:]
    return rows


def _fts_query(query: str) -> str:
    """Санитизированный FTS5-запрос: токены в кавычках, без спец-операторов."""
    toks = re.findall(r"[а-яёa-z0-9-]+", (query or "").lower())
    return " ".join(f'"{t}"' for t in toks)


def _parse_entry(line: str) -> dict:
    """Разбирает строку JSONL в запись (с _dt); ValueError при повреждении."""
    obj = json.loads(line)
    date = str(obj["date"]).replace("Z", "+00:00")
    dt = datetime.fromisoformat(date)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    else:
        dt = dt.astimezone(timezone.utc)
    return {
        "_dt": dt,
        "date": obj["date"],
        "author": obj.get("author", ""),
        "text": obj.get("text", ""),
        "id": obj.get("id"),
        "chat": obj.get("chat", ""),
    }


def _iter_jsonl(path: str):
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield line
