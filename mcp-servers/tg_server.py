"""MCP-сервер TG-чата для «Штурмана» (задача 7b плана mcp-integration-shturman).

Три тула (больше — нельзя по guardrail'ам плана):
    - read_recent — хронологический срез последних N часов (окно часов UTC)
    - search       — поиск по тексту в окне последних N часов (+ счётчик совпадений)
    - summary      — извлекательная сводка окна БЕЗ LLM (вопросы/авторы/темы)

Транспорт: stdio (FastMCP из common.build_server); stdout = только JSON-RPC,
все логи — stderr. Telegram: официальный Bot API (httpx), НЕ Telethon: боту
(созданному через BotFather) не нужен api_id/api_hash, а чат он читает через
getUpdates long-polling. Сообщения пишутся в persistent-хранилище SQLite
(FTS5, файл `TG_DB_FILE`, по умолчанию `.tg_messages.db`), потому что бот по
умолчанию НЕ видит историю чата — накопление идёт с момента старта сервера.

Гарантии:
    - Сервер стартует и отвечает на tools/list даже без TG_BOT_TOKEN: поллер
      просто не запускается; тулы в этом случае либо читают уже накопленный
      буфер (read-only), либо отдают читаемую ошибку про отсутствие токена.
    - Фоновый поллер (getUpdates) самовосстанавливается: сетевые/HTTP-ошибки
      логируются в stderr и повторяются с экспоненциальным бэкоффом.
    - Ничего не пишем в stdout, кроме JSON-RPC; токен НЕ логируем и НЕ выводим.
    - Все httpx-запросы с явными таймаутами; тулы не зависают.
"""

import asyncio
import json
import logging
import os
import re
import sqlite3
from collections import Counter
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone

import httpx

from common import build_server

log = logging.getLogger("tg_server")

# httpx пишет каждый запрос с полным URL (в т.ч. /bot<TOKEN>/) на INFO.
# Отключаем INFO-логи httpx, чтобы токен не просачивался в stderr.
logging.getLogger("httpx").setLevel(logging.WARNING)

DEFAULT_CHAT = "tank_dargo_h6_chat"
DEFAULT_API_BASE = "https://api.telegram.org"
MAX_MESSAGES = 5000          # жёсткий потолок окна read_recent
SEARCH_LIMIT = 100           # потолок поиска
SUMMARY_MAX = 2000           # потолок окна summary
SUMMARY_TOP = 5              # размер топ-списков сводки
LONG_POLL_TIMEOUT = 50       # сек: long-poll getUpdates
REQUEST_TIMEOUT = 60.0       # сек: общий таймаут httpx (должен > long-poll)
POLLER_BACKOFF_MAX = 60.0    # сек: максимум экспоненциального бэкоффа поллера
RETENTION_INTERVAL = 6 * 3600  # сек: период фоновой очистки по retention

# Репo-корень: mcp-servers/tg_server.py -> родитель каталога mcp-servers.
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DB_PATH = os.path.join(_REPO_ROOT, "mcp-servers", ".tg_messages.db")
DEFAULT_BUFFER_PATH = os.path.join(_REPO_ROOT, "mcp-servers", ".tg_buffer.jsonl")

mcp = build_server("shturman-tg")


class TgToolError(Exception):
    """Читаемая ошибка тула: попадает в ответ как ok=false."""

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


# --------------------------------------------------------------------------
# Конфигурация (читается на вызов тула, не на импорт: boot без креда — OK)
# --------------------------------------------------------------------------

def _env_str(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def _bot_token() -> str:
    return _env_str("TG_BOT_TOKEN")


def _chat() -> str:
    return _env_str("TG_CHAT") or DEFAULT_CHAT


def _api_base() -> str:
    return _env_str("TG_API_BASE") or DEFAULT_API_BASE


def _db_path() -> str:
    p = _env_str("TG_DB_FILE") or DEFAULT_DB_PATH
    if not os.path.isabs(p):
        p = os.path.join(_REPO_ROOT, p)
    return p


def _retention_days() -> int:
    raw = _env_str("TG_RETENTION_DAYS")
    try:
        n = int(raw)
        return n if n >= 0 else 0
    except ValueError:
        return 0


def _buffer_path() -> str:
    """Источник одноразовой миграции (legacy JSONL); TG_BUFFER_MAX_LINES игнорируется."""
    p = _env_str("TG_BUFFER_FILE") or DEFAULT_BUFFER_PATH
    if not os.path.isabs(p):
        p = os.path.join(_REPO_ROOT, p)
    return p


# --------------------------------------------------------------------------
# Bot API-клиент (ленивый, с явными таймаутами)
# --------------------------------------------------------------------------

_bot_client: httpx.AsyncClient | None = None


def _get_bot_client() -> httpx.AsyncClient:
    global _bot_client
    if _bot_client is None:
        _bot_client = httpx.AsyncClient(timeout=httpx.Timeout(REQUEST_TIMEOUT))
    return _bot_client


def _method_url(method: str) -> str:
    """Полный URL метода Bot API с токеном в пути. НЕ логировать результат."""
    return f"{_api_base().rstrip('/')}/bot{_bot_token()}/{method}"


async def _get_updates(offset: int) -> list:
    """getUpdates c long-poll (timeout=50s); возвращает список updates.

    Исключения наружу: HTTP-ошибки, таймауты, сетевые сбои — поллер ловит
    и делает бэкофф. Токен/URL сюда не попадает в логи.
    """
    resp = await _get_bot_client().post(
        _method_url("getUpdates"),
        json={
            "offset": offset,
            "timeout": LONG_POLL_TIMEOUT,
            "allowed_updates": ["message", "channel_post"],
        },
    )
    resp.raise_for_status()  # не-2xx -> исключение (401, 429 и т.п.)
    data = resp.json()
    return data.get("result") or []


# --------------------------------------------------------------------------
# Разбор сообщений Bot API
# --------------------------------------------------------------------------

# Медиа-поля Bot API: наличие любого (и НЕ текстового) -> сообщение «медиа».
_MEDIA_KEYS = (
    "photo", "video", "audio", "document", "animation", "voice", "video_note",
    "sticker", "contact", "location", "venue", "poll", "dice", "game",
)
# Служебные события Bot API (ставится в канал/группу, текста нет) — пропускаем.
_SERVICE_KEYS = (
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


def _utc_from_ts(ts) -> datetime:
    return datetime.fromtimestamp(ts, tz=timezone.utc)


def _message_text(msg: dict) -> str:
    """Схлопнутый текст ИЛИ '[медиа]' ИЛИ '' (сервисное — не храним)."""
    for key in ("text", "caption"):
        val = msg.get(key)
        if isinstance(val, str) and val.strip():
            return re.sub(r"\s+", " ", val.strip())
    if any(k in msg for k in _SERVICE_KEYS):
        return ""
    if any(k in msg for k in _MEDIA_KEYS):
        return "[медиа]"
    return ""


def _author_name(msg: dict) -> str:
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


def _chat_matches(chat: dict) -> bool:
    """Совпадение чата: числовой TG_CHAT -> по chat.id; иначе по username."""
    if not chat:
        return False
    target = _chat()
    if not target:
        return False
    if target.isdigit():
        return str(chat.get("id")) == target
    target = target.lower().lstrip("@")
    username = (chat.get("username") or "").lower().lstrip("@")
    return username == target


def _chat_label(msg: dict) -> str:
    """Идентификатор чата для поля `chat` в буфере: id при числовом TG_CHAT,
    иначе username (а если username нет — id)."""
    chat = msg.get("chat") or {}
    target = _chat()
    if target and target.isdigit():
        return str(chat.get("id", ""))
    username = chat.get("username")
    return username if username else str(chat.get("id", ""))


def _entry_chat_matches(entry: dict) -> bool:
    """Совпадение `chat` записи буфера с настроенным TG_CHAT."""
    target = _chat()
    if not target:
        return False
    ec = str(entry.get("chat") or "")
    if target.isdigit():
        return ec == target
    return ec.lower().lstrip("@") == target.lower().lstrip("@")


def _entry_from_message(msg: dict) -> dict | None:
    """Строит запись буфера из сообщения или None (чужой чат / сервисное)."""
    if not _chat_matches(msg.get("chat")):
        return None
    text = _message_text(msg)
    if not text:
        return None  # сервисное/пустое — не храним
    dt = _utc_from_ts(msg.get("date", 0))
    return {
        "_dt": dt,
        "date": dt.isoformat(),
        "author": _author_name(msg),
        "text": text,
        "id": msg.get("message_id"),
        "chat": _chat_label(msg),
    }


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


def _format_line(entry: dict) -> str:
    """'[YYYY-MM-DD HH:MM UTC] author: text'."""
    ts = entry["_dt"].strftime("%Y-%m-%d %H:%M")
    return f"[{ts} UTC] {entry['author']}: {entry['text']}"


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
"""


class TgStore:
    """Persistent-хранилище на SQLite (FTS5). Все операции под одним asyncio.Lock;
    sqlite-вызовы синхронные (на этом объёме — норма)."""

    def __init__(self, db_path: str, retention_days: int, legacy_jsonl: str):
        self.db_path = db_path
        self.retention_days = retention_days
        self.legacy_jsonl = legacy_jsonl
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
                log.warning("tg_db: миграция: повреждённая строка %d пропущена", lineno)
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
            log.error("tg_db: миграция: не переименован источник: %s", type(exc).__name__)
        log.info("tg_db: миграция: импортировано %d строк -> %s.imported",
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
            log.info("tg_db: retention: удалено %d старых сообщений", cur.rowcount)

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
            if _entry_chat_matches(entry):
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
            if _entry_chat_matches(entry):
                out.append(entry)
        return out

    def _like_rows(self, query: str, cutoff_ts: float) -> list[dict]:
        q = (query or "").strip().lower()
        return [e for e in self._query_rows(cutoff_ts) if q in e["text"].lower()]


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


def _iter_jsonl(path: str):
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                yield line


_store = TgStore(_db_path(), _retention_days(), _buffer_path())


# --------------------------------------------------------------------------
# Фоновый поллер (getUpdates loop; самовосстанавливающийся)
# --------------------------------------------------------------------------

_poller_task: asyncio.Task | None = None
_poller_start_lock = asyncio.Lock()
_poller_offset = 0


async def ensure_poller_started() -> None:
    """Запускает поллер один раз (если есть токен); повторный вызов — no-op."""
    global _poller_task
    if _poller_task is not None:
        return
    if not _bot_token():
        return
    async with _poller_start_lock:
        if _poller_task is not None:
            return
        _poller_task = asyncio.create_task(_poller_loop(), name="tg-poller")


async def _poller_loop() -> None:
    global _poller_offset
    backoff = 1.0
    while True:
        try:
            updates = await _get_updates(_poller_offset)
            got_any = False
            for upd in updates:
                _poller_offset = max(_poller_offset, int(upd.get("update_id", 0)) + 1)
                msg = upd.get("message") or upd.get("channel_post")
                if not isinstance(msg, dict):
                    continue
                entry = _entry_from_message(msg)
                if entry is None:
                    continue  # не наш чат или сервисное
                await _store.insert(entry)
                got_any = True
            backoff = 1.0  # успех — сбрасываем бэкофф
            if not got_any:
                await asyncio.sleep(0.05)  # защита от busy-loop на пустом ответе
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # сеть/HTTP/401/429 — не роняем сервер
            log.error("tg-poller: %s (бэкофф %.1fs)", _safe_err(exc), backoff)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, POLLER_BACKOFF_MAX)


def _safe_err(exc: Exception) -> str:
    """Безопасное описание ошибки: не выводим URL/токен."""
    if isinstance(exc, httpx.HTTPStatusError):
        return f"HTTP {exc.response.status_code}"
    if isinstance(exc, httpx.TimeoutException):
        return "timeout"
    if isinstance(exc, httpx.HTTPError):
        return type(exc).__name__
    return type(exc).__name__


@asynccontextmanager
async def _lifespan(server):
    # стартуем поллер при старте сервера (если задан токен)
    await ensure_poller_started()
    try:
        yield
    finally:
        global _poller_task
        if _poller_task is not None:
            _poller_task.cancel()
            try:
                await _poller_task
            except (asyncio.CancelledError, Exception):
                pass
            _poller_task = None
        _store.close()


# Подключаем lifespan (поллер стартует вместе с сервером). build_server не
# принимает lifespan, поэтому задаём его на низкоуровневом сервере FastMCP.
mcp._mcp_server.lifespan = _lifespan


# --------------------------------------------------------------------------
# Результаты (JSON; ок/error)
# --------------------------------------------------------------------------

def _result(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False)


def _error_result(exc: TgToolError) -> str:
    return json.dumps({"ok": False, "error": exc.message}, ensure_ascii=False)


def _norm_hours(hours) -> int:
    h = int(hours)
    return h if h >= 1 else 1


async def _readiness() -> tuple[str, TgToolError | None]:
    """Возвращает (chat, err|None). Проверяет токен/хранилище и запускает поллер."""
    token = _bot_token()
    await ensure_poller_started()
    if not token and not await _store.has_entries():
        return _chat(), TgToolError(
            "Токен бота не задан: установите TG_BOT_TOKEN (@BotFather → /newbot)."
        )
    return _chat(), None


# --------------------------------------------------------------------------
# Извлекательная сводка (БЕЗ LLM, БЕЗ сети вне Bot API)
# --------------------------------------------------------------------------

# Стоп-слова: русские + английские (для тем/ключевых слов).
_STOPWORDS = set("""
и в во не что он на я с со как а то все она так его но да ты к у же вы за бы по
только ее мне было вот от меня еще нет о из ему теперь когда даже ну вдруг ли если
уже или ни быть был него до вас нибудь опять уж вам ведь там потом себя ничего ей
может они тут где есть надо ней для мы тебя их чем была сам чтоб без будто чего раз
тоже себе под будет ж тогда кто этот того потому этого какой совсем ним здесь этом
один почти мой тем чтобы нее сейчас были куда зачем всех никогда можно при наконец
два об другой хоть после над больше тот через эти нас про всего них какая много разве
три эту моя впрочем свою эту перед иногда лучше чуть том нельзя такой им более всегда
конечно всю между
the a an and or but if then this that these those i you he she it we they me him her
is are was were be been being have has had do does did will would can could should
not no yes of to in on at for with from by about as into like than so too very just
its it's their there here what when where who why how all any both each few more most
other some such only own same than s t don now
""".split())


def _tokenize(text: str) -> list[str]:
    words = re.findall(r"[а-яёa-z0-9-]+", text.lower())
    return [w for w in words if len(w) > 1 and w not in _STOPWORDS]


def _short(text: str, limit: int = 160) -> str:
    text = re.sub(r"\s+", " ", text).strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"


# --------------------------------------------------------------------------
# Тулы (описания короткие — идут в промпт LLM)
# --------------------------------------------------------------------------

@mcp.tool()
async def read_recent(hours: int = 24, max_messages: int = 500) -> str:
    """Читает последние сообщения чата за N часов (UTC), хронологически."""
    try:
        chat, err = await _readiness()
        if err:
            return _error_result(err)
        h = _norm_hours(hours)
        limit = max(1, min(int(max_messages), MAX_MESSAGES))
        entries = await _store.window(h, limit)
        lines = [_format_line(e) for e in entries]
        return _result({
            "ok": True,
            "chat": chat,
            "windowHours": h,
            "count": len(lines),
            "messages": lines,
        })
    except TgToolError as exc:
        log.error("read_recent failed: %s", exc.message)
        return _error_result(exc)
    except Exception as exc:
        log.error("read_recent error: %s", _safe_err(exc))
        return _error_result(TgToolError(
            f"Ошибка чтения чата: {type(exc).__name__}: {exc}"
        ))


@mcp.tool()
async def search(query: str, hours: int = 168) -> str:
    """Ищет сообщения по тексту за N часов (UTC), хронологически, со счётчиком."""
    try:
        if not (query or "").strip():
            return _error_result(TgToolError("Пустой запрос: укажите query."))
        chat, err = await _readiness()
        if err:
            return _error_result(err)
        h = _norm_hours(hours)
        entries = await _store.search(query, h, SEARCH_LIMIT)
        lines = [_format_line(e) for e in entries]
        return _result({
            "ok": True,
            "chat": chat,
            "query": query,
            "windowHours": h,
            "count": len(lines),
            "messages": lines,
        })
    except TgToolError as exc:
        log.error("search failed: %s", exc.message)
        return _error_result(exc)
    except Exception as exc:
        log.error("search error: %s", _safe_err(exc))
        return _error_result(TgToolError(
            f"Ошибка поиска: {type(exc).__name__}: {exc}"
        ))


@mcp.tool()
async def summary(hours: int = 24) -> str:
    """Даёт извлекательную сводку чата за N часов: вопросы, авторы, темы."""
    try:
        chat, err = await _readiness()
        if err:
            return _error_result(err)
        h = _norm_hours(hours)
        entries = await _store.window(h, SUMMARY_MAX)

        # Текстовые сообщения для анализа (исключаем чисто медиа-записи).
        text_msgs = [e for e in entries if e["text"] and e["text"] != "[медиа]"]
        total = len(entries)

        # Топ-вопросы (содержат '?'), ранжированные по частоте ключевых слов.
        questions = [e for e in text_msgs if "?" in e["text"]]
        kw_freq: Counter = Counter()
        for e in text_msgs:
            for w in _tokenize(e["text"]):
                kw_freq[w] += 1
        scored = sorted(
            [(sum(kw_freq[t] for t in set(_tokenize(e["text"]))), e["text"]) for e in questions],
            key=lambda x: -x[0],
        )[:SUMMARY_TOP]

        # Топ-авторы по числу сообщений (имя уже в записи).
        author_counter: Counter = Counter(e["author"] for e in text_msgs)
        top_authors = author_counter.most_common(SUMMARY_TOP)

        # Топ-темы по частотным биграммам (стоп-слова ru/en отброшены).
        bigram_counter: Counter = Counter()
        for e in text_msgs:
            toks = _tokenize(e["text"])
            for a, b in zip(toks, toks[1:]):
                bigram_counter[(a, b)] += 1
        top_themes = bigram_counter.most_common(SUMMARY_TOP)

        # Markdown-блок.
        lines = [f"## Сводка чата за последние {h} ч",
                 "",
                 f"Сообщений: {total} | Текстовых: {len(text_msgs)} | "
                 f"Вопросов: {len(questions)} | Участников: {len(author_counter)}",
                 "",
                 "### Вопросы (топ)", ""]
        if scored:
            for i, (s, text) in enumerate(scored, 1):
                lines.append(f"{i}. {_short(text)}")
        else:
            lines.append("— вопросов с '?' за период нет —")
        lines += ["", "### Топ-авторы", ""]
        if top_authors:
            for name, cnt in top_authors:
                lines.append(f"- {name}: {cnt}")
        else:
            lines.append("— нет текстовых сообщений —")
        lines += ["", "### Топ-темы", ""]
        if top_themes:
            for (a, b), cnt in top_themes:
                lines.append(f"- {a} {b}: {cnt}")
        else:
            lines.append("— тем не обнаружено —")
        md = "\n".join(lines)

        return _result({
            "ok": True,
            "chat": chat,
            "windowHours": h,
            "text": md,
        })
    except TgToolError as exc:
        log.error("summary failed: %s", exc.message)
        return _error_result(exc)
    except Exception as exc:
        log.error("summary error: %s", _safe_err(exc))
        return _error_result(TgToolError(
            f"Ошибка сводки: {type(exc).__name__}: {exc}"
        ))


if __name__ == "__main__":
    # stdio по умолчанию: stdout остаётся чистым JSON-RPC.
    mcp.run()
