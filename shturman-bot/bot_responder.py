"""Долгоживущий сервис-ответчик Telegram-бота @shturman_mcp_bot (Дни 27–28 SDD).

Единственный владелец ``getUpdates``-поллера на токен: накапливает сообщения
в общую SQLite/FTS5 базу (``TG_DB_FILE``) и отвечает пользователям через
локальную LLM (LM Studio). Команда ``/ask`` делает RAG-ответ по
HTTP-sidecar поиска с локальными источниками (URL — ``RAG_SEARCH_URL``).

Архитектура (см. README):
    - Это НЕ MCP-сервер: stdout свободен, но все логи идут в stderr.
    - Один getUpdates-поллер на токен: ``tg_server.py`` при работающем
      респондере запускается БЕЗ ``TG_BOT_TOKEN`` (read-only режим).
    - Offset поллера хранится персистентно в ``meta`` той же SQLite, чтобы после
      рестарта не репроцессить старые сообщения и не затирать историю.
    - Полностью локальная генерация: ни один запрос не уходит в облако.

Команды:
    /start, /help        — справка
    /ask <вопрос>        — RAG-ответ (retrieval + локальная генерация)
    /agent <задача>      — «мини-агент»: правка файлов проекта по задаче
                           (выбор модели, гейт подтверждения, отчёт с диффами)
    свободный текст      — обычный вопрос без RAG (личный чат или @упоминание)
"""

from __future__ import annotations

import asyncio
import html
import json
import logging
import os
import re
import sqlite3
import sys
import time
from collections import deque
from datetime import datetime, timezone
from pathlib import Path

import httpx

import agent_session
import deps_map
import diagnose
import file_diff
import github_watcher
import issue_create
import issue_locate
import pr_review
import project_edit
import project_files
import project_shell
from storage import (
    DEFAULT_DB_PATH,
    TgStore,
    author_name,
    chat_matches_chat,
    entry_chat_matches as _entry_matcher,
    entry_from_message,
    message_text,
    chat_label as _chat_label_for,
)

log = logging.getLogger("bot_responder")

# httpx пишет каждый запрос с полным URL (в т.ч. /bot<TOKEN>/) на INFO.
logging.getLogger("httpx").setLevel(logging.WARNING)

# Корень проекта: standalone shturman-bot, config.json лежит рядом с кодом.
_REPO_ROOT = os.path.dirname(os.path.abspath(__file__))
_CONFIG_PATH = os.path.join(_REPO_ROOT, "config.json")

DEFAULT_CHAT = "tank_dargo_h6_chat"
DEFAULT_API_BASE = "https://api.telegram.org"
DEFAULT_LM_API_BASE = "http://localhost:1234/v1"
DEFAULT_LM_MODEL = "qwen3.5-9b-mlx"
# 2048, а не 1024 (рекомендация Дня 29 для кратких фактовых ответов): полные
# перечни («какие прошивки есть?») с reasoning qwen3 в 1024 не помещаются —
# finish=length, ответ обрывается на первой марке. Факт. расход на таком вопросе
# ~770 токенов (finish=stop), потолок лишнего не тратит.
DEFAULT_LM_MAX_TOKENS = 2048
DEFAULT_LM_TEMPERATURE = 0.1  # оптимизация Дня 29: reports/optimization.md (judge 9.5, t_gen 6.95с)
DEFAULT_RAG_SEARCH_URL = "http://127.0.0.1:18702/search"
DEFAULT_RAG_K = 5
DEFAULT_RAG_STRATEGY = "structure"

_HYDRATE_MIN_CHARS = 400

LONG_POLL_TIMEOUT = 50       # сек: long-poll getUpdates
BOT_API_TIMEOUT = 60.0       # сек: таймаут Bot API (должен > long-poll)
LM_TIMEOUT = 120.0           # сек: таймаут генерации LM Studio
RAG_TIMEOUT = 20.0           # сек: таймаут RAG sidecar
POLLER_BACKOFF_MAX = 60.0    # сек: максимум экспоненциального бэкоффа поллера
COOLDOWN_SEC = 5.0           # сек: не более 1 запроса от пользователя
INTERIM_SEC = 45.0           # сек: порог «Думаю…»
MAX_MSG_CHARS = 4096         # лимит Telegram на одно сообщение
MAX_RAG_BLOCK_CHARS = 16000  # потолок RAG-блока в промпте (+ каталог и первоисточник)
COOLDOWN_MSG = "Подождите немного — я не могу отвечать так часто."
IDK_MSG = "Модель не уложилась в лимит, попробуйте короче вопрос"
LM_DOWN_MSG = ("Не удалось достучаться до локальной модели (LM Studio). "
               "Проверьте, что она запущена и модель загружена.")
GEN_TIMEOUT_MSG = ("Локальная модель долго не отвечает (> 120 c). Попробуйте "
                   "короче вопрос или проверьте LM Studio.")

# Контекст диалога: последние пары (вопрос, ответ) на пользователя — чтобы
# уточняющие вопросы модель задавала по ходу беседы, а не шаблоном.
_HISTORY_TURNS = 4           # глубина истории в парах вопрос-ответ
_HISTORY_ANSWER_CHARS = 600  # обрезка длинных ответов при запоминании

_chat_history: dict[int, deque] = {}


def _history_messages(user_id: int) -> list[dict]:
    """История диалога пользователя в формате messages для /chat/completions."""
    turns = _chat_history.get(user_id)
    if not turns:
        return []
    msgs: list[dict] = []
    for q, a in turns:
        msgs.append({"role": "user", "content": q})
        msgs.append({"role": "assistant", "content": a})
    return msgs


def _remember_turn(user_id: int, question: str, answer: str) -> None:
    turns = _chat_history.setdefault(user_id, deque(maxlen=_HISTORY_TURNS))
    turns.append((question, answer[:_HISTORY_ANSWER_CHARS]))


# Статистика для /admin: _START_TS — старт текущего процесса,
# _session_usage — токены текущего процесса, _pending_usage — не сброшенные в БД.
_START_TS = time.time()
_pending_usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
_session_usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}

# ---------------------------------------------------------------------------
# Конфигурация (env; фолбэк токена/чата — из ../config.json)
# ---------------------------------------------------------------------------

def _env_str(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def _load_config() -> dict:
    try:
        with open(_CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


_config = _load_config()


def _mcp_env(name: str) -> str:
    """Ищет значение env в mcp-записи config.json (по имени ключа)."""
    for entry in _config.get("mcp", []):
        env = entry.get("env") or {}
        val = (env.get(name) or "").strip()
        if val:
            return val
    return ""


def _bot_token() -> str:
    t = _env_str("TG_BOT_TOKEN")
    if not t:
        t = _mcp_env("TG_BOT_TOKEN")
    return t


def _chat() -> str:
    c = _env_str("TG_CHAT")
    if not c:
        c = _mcp_env("TG_CHAT")
    return c or DEFAULT_CHAT


def _api_base() -> str:
    return _env_str("TG_API_BASE") or DEFAULT_API_BASE


def _db_path() -> str:
    p = _env_str("TG_DB_FILE") or DEFAULT_DB_PATH
    if not os.path.isabs(p):
        p = os.path.join(_REPO_ROOT, p)
    return p


def _lm_api_base() -> str:
    return _env_str("LM_API_BASE") or DEFAULT_LM_API_BASE


def _lm_model() -> str:
    return _env_str("LM_MODEL") or DEFAULT_LM_MODEL


def _lm_max_tokens() -> int:
    return _int_env("LM_MAX_TOKENS", DEFAULT_LM_MAX_TOKENS)


def _lm_temperature() -> float:
    return _float_env("LM_TEMPERATURE", DEFAULT_LM_TEMPERATURE)


def _rag_search_url() -> str:
    return _env_str("RAG_SEARCH_URL") or DEFAULT_RAG_SEARCH_URL


def _rag_k() -> int:
    return _int_env("RAG_K", DEFAULT_RAG_K)


def _rag_strategy() -> str:
    return _env_str("RAG_STRATEGY") or DEFAULT_RAG_STRATEGY


def _int_env(name: str, default: int) -> int:
    raw = _env_str(name)
    try:
        return int(raw)
    except ValueError:
        return default


def _float_env(name: str, default: float) -> float:
    raw = _env_str(name)
    try:
        return float(raw)
    except ValueError:
        return default


def _retention_days() -> int:
    return _int_env("TG_RETENTION_DAYS", 0)


# ---------------------------------------------------------------------------
# Хранилище (общий TgStore)
# ---------------------------------------------------------------------------

def _entry_chat_matches(entry: dict) -> bool:
    return _entry_matcher(entry, _chat())


def _entry_from_message(msg: dict) -> dict | None:
    return entry_from_message(
        msg,
        chat_match=lambda chat: chat_matches_chat(chat, _chat()),
        chat_labeller=lambda chat: _chat_label_for(chat, _chat()),
    )


_store = TgStore(
    _db_path(),
    _retention_days(),
    os.path.join(_REPO_ROOT, ".tg_buffer.jsonl"),
    chat_matcher=_entry_chat_matches,
)


# ---------------------------------------------------------------------------
# Bot API клиент
# ---------------------------------------------------------------------------

_bot_client: httpx.AsyncClient | None = None
_lm_client: httpx.AsyncClient | None = None


def _get_bot_client() -> httpx.AsyncClient:
    global _bot_client
    if _bot_client is None:
        _bot_client = httpx.AsyncClient(timeout=httpx.Timeout(BOT_API_TIMEOUT))
    return _bot_client


def _get_lm_client() -> httpx.AsyncClient:
    global _lm_client
    if _lm_client is None:
        _lm_client = httpx.AsyncClient(timeout=httpx.Timeout(LM_TIMEOUT))
    return _lm_client


def _method_url(method: str) -> str:
    return f"{_api_base().rstrip('/')}/bot{_bot_token()}/{method}"


async def _api(method: str, payload: dict):
    """Вызов Bot API; возвращает result. Ошибки (HTTP/API) поднимают исключение.

    Функция удаляет 'ok' из ответа и возвращает 'result'. Токен/URL не логируем.
    """
    resp = await _get_bot_client().post(_method_url(method), json=payload)
    if resp.status_code in (401, 403):
        raise _AuthApiError(f"{method}: HTTP {resp.status_code}")
    resp.raise_for_status()
    data = resp.json()
    return data.get("result")


async def _safe_api(method: str, payload: dict):
    """Вызов Bot API с подавлением ошибок (для typing/сервисных)."""
    try:
        await _api(method, payload)
    except Exception as exc:
        log.debug("bot: api %s: %s", method, _safe_err(exc))


_ANGLE_URL = re.compile(r"<(https?://[^>\s]+)>")
_MD_LINK = re.compile(r"\[([^\]]+)\]\((https?://[^)\s]+)\)")
_MD_BOLD = re.compile(r"\*\*(.+?)\*\*", re.DOTALL)
_MD_ITALIC = re.compile(r"(?<!\*)\*([^*\n]+)\*(?!\*)")
_MD_CODE = re.compile(r"`([^`\n]+)`")
_MD_HEADER = re.compile(r"^#{1,6}\s+(.+)$", re.MULTILINE)


def _md_to_telegram_html(text: str) -> str:
    """Markdown от модели -> Telegram HTML (parse_mode='HTML').

    Telegram без parse_mode рендит «сырые» ** и \\_ (проблема «неверного
    форматирования»). HTML-режим надёжнее Telegram-Markdown: не падает на
    незакрытых символах. Порядок: снять экранирование, вытащить <url>,
    экранировать HTML, потом размечать теги.
    """
    text = text.strip()
    if not text:
        return text
    text = text.replace("\\_", "_").replace("\\*", "*").replace("\\#", "#")
    # Список, приклеенный моделью к строке-интро («контактам:  - @ник»),
    # разворачиваем на отдельные строки: дефис после «: + пробелы» — это пункт.
    text = re.sub(r"(:[ \t]+)(- )", r"\1\n\2", text)
    # Маркеры-сноски [N] — мусор: в ответах блок «Источники» не выводится.
    # Ссылки вида [текст](url) не задеваем: за скобкой следует '('.
    text = re.sub(r"\s*\[\d{1,2}\](?=[\s.,;:)\]]|$)", "", text)
    text = _ANGLE_URL.sub(lambda m: m.group(1), text)
    text = html.escape(text, quote=False)
    text = _MD_LINK.sub(lambda m: f'<a href="{m.group(2)}">{m.group(1)}</a>', text)
    text = _MD_BOLD.sub(lambda m: f"<b>{m.group(1)}</b>", text)
    text = _MD_HEADER.sub(lambda m: f"<b>{m.group(1)}</b>", text)
    text = _MD_ITALIC.sub(lambda m: f"<i>{m.group(1)}</i>", text)
    text = _MD_CODE.sub(lambda m: f"<code>{m.group(1)}</code>", text)
    return text


async def _send_text(chat_id, text: str, reply_to=None) -> None:
    parts = _split_message(text)
    for i, part in enumerate(parts):
        payload = {
            "chat_id": chat_id,
            "text": _md_to_telegram_html(part),
            "parse_mode": "HTML",
        }
        if i == 0 and reply_to is not None:
            payload["reply_to_message_id"] = reply_to
        await _api("sendMessage", payload)


async def _send_document(chat_id, filename: str, content: str,
                         caption: str = "", reply_to=None) -> None:
    """Файл-документ в чат (multipart upload); caption до 1024 симв."""
    resp = await _get_bot_client().post(
        _method_url("sendDocument"),
        data={
            "chat_id": str(chat_id),
            "caption": caption[:1024],
            **({"reply_to_message_id": reply_to} if reply_to is not None else {}),
        },
        files={"document": (filename, content.encode("utf-8"), "text/plain")},
    )
    if resp.status_code in (401, 403):
        raise _AuthApiError(f"sendDocument: HTTP {resp.status_code}")
    resp.raise_for_status()


async def _send_photo(chat_id, png: bytes, caption: str = "", reply_to=None) -> None:
    """PNG-картинка в чат (multipart upload); caption до 1024 симв."""
    resp = await _get_bot_client().post(
        _method_url("sendPhoto"),
        data={"chat_id": str(chat_id), "caption": caption[:1024]},
        files={"photo": ("deps.png", png, "image/png")},
    )
    if resp.status_code in (401, 403):
        raise _AuthApiError(f"sendPhoto: HTTP {resp.status_code}")
    resp.raise_for_status()


async def _send_html(chat_id, text: str) -> None:
    """Готовый Telegram-HTML БЕЗ markdown-конвертации (для diff-подсветки).

    ``_send_text`` экранирует HTML (markdown -> HTML), поэтому готовую
    разметку (`<s>/<i>/<b>/<code>`) надо отправлять напрямую. Разбиение
    длинных сообщений — только по границе строки: теги диффа не переходят
    через строку (render_diff_html размечает построчно).
    """
    parts: list[str] = []
    remaining = text
    while len(remaining) > MAX_MSG_CHARS - 300:
        cut = remaining.rfind("\n", 0, MAX_MSG_CHARS - 300)
        if cut <= 0:
            cut = MAX_MSG_CHARS - 300
        parts.append(remaining[:cut])
        remaining = remaining[cut:].lstrip("\n")
    parts.append(remaining)
    for part in parts:
        await _api("sendMessage", {"chat_id": chat_id, "text": part,
                                   "parse_mode": "HTML"})


async def _send_chat_action(chat_id, action: str) -> None:
    await _safe_api("sendChatAction", {"chat_id": chat_id, "action": action})


class AuthError(Exception):
    """getMe ответил 401/403 — токен недействителен (ретраи бесполезны)."""


class _AuthApiError(AuthError):
    """Внутренний маркер авторизационного отказа Bot API."""


async def _get_me() -> dict | None:
    try:
        return await _api("getMe", {})
    except _AuthApiError:
        raise
    except Exception as exc:  # noqa: BLE001 - сеть/таймаут: ретраи имеют смысл
        log.error("bot: getMe failed: %s", _safe_err(exc))
        return None


# ---------------------------------------------------------------------------
# Разбор входящих сообщений
# ---------------------------------------------------------------------------

_CMD_RE = re.compile(
    r"^/(ask|start|help|admin|deps|create|agent)(?:@[\w]+)?[ \t]*(.*)$", re.I
)
_MENTION_RE = re.compile(r"@([A-Za-z0-9_]+)")


def _strip_mention(text: str, bot_username: str) -> str:
    """Убирает '@username' бота из текста вопроса (для групповых упоминаний)."""
    lower = bot_username.lower().lstrip("@")
    out = re.sub(rf"@({re.escape(lower)})", " ", text, flags=re.I)
    return re.sub(r"\s+", " ", out).strip(" \t\n,;:—–-")


def _mentioned(text: str, bot_username: str) -> bool:
    token = bot_username.lower().lstrip("@")
    if not token:
        return False
    return any(m.lower() == token for m in _MENTION_RE.findall(text.lower()))


DEFAULT_ADMIN_USERNAMES = "AugustoTruck,kmnivan"


def _admin_usernames() -> set[str]:
    """Логины админов (/admin): env BOT_ADMIN_USERNAMES через запятую."""
    raw = (
        _env_str("BOT_ADMIN_USERNAMES")
        or _env_str("BOT_ADMIN_USERNAME")
        or DEFAULT_ADMIN_USERNAMES
    )
    return {u.strip().lstrip("@").lower() for u in raw.split(",") if u.strip()}


def _is_admin(msg: dict) -> bool:
    username = ((msg.get("from") or {}).get("username") or "").lstrip("@")
    return bool(username) and username.lower() in _admin_usernames()


class Route:
    """Куда отправить сообщение: (mode, question)."""

    __slots__ = ("mode", "question")

    def __init__(self, mode: str, question: str = ""):
        self.mode = mode
        self.question = question


def _route_message(msg: dict, bot_username: str) -> Route | None:
    chat = msg.get("chat") or {}
    chat_type = chat.get("type")
    text = message_text(msg)
    if not text or text == "[медиа]":
        return None

    cmd = _CMD_RE.match(text)
    if cmd:
        mode = cmd.group(1).lower()
        arg = cmd.group(2).strip()
        if mode == "ask":
            return Route("ask", arg)
        if mode == "admin":
            # Панель видна только администратору; остальным — молчание.
            return Route("admin") if _is_admin(msg) else None
        if mode == "create":
            # Оформление GitHub-issue — только из личного чата.
            return Route("create", arg) if chat_type == "private" else None
        if mode == "agent":
            # «Мини-агент» (правка файлов проекта) — только из личного чата.
            return Route("agent", arg) if chat_type == "private" else None
        return Route(mode)
    if text.startswith("/"):
        # неизвестная команда: в личке подскажем help, в группе — молчим
        return Route("help") if chat_type == "private" else None

    if chat_type == "private":
        # Активная сессия агента перехватывает свободный текст: гейт
        # подтверждения (AWAIT_CONFIRM), дополнение задачи (выбор модели)
        # или «агент занят» — ДО интентов create/RAG.
        sender_id = (msg.get("from") or {}).get("id") or chat.get("id")
        agent_session_live = _agent_sessions.get(sender_id) if sender_id else None
        if agent_session_live is not None:
            if agent_session_live.state == agent_session.AWAIT_CONFIRM:
                return Route("agent_confirm", text)
            if agent_session_live.state == agent_session.FOLLOWUP:
                return Route("agent_followup", text)
            if agent_session_live.state in (
                    agent_session.TASK_SET, agent_session.MODEL_SELECT):
                return Route("agent_note", text)
            return Route("agent_busy")
        # Интент «создай issue …»: глагол создания в начале фразы. Со словами
        # контекста — сразу черновик (быстрый путь, без LLM); без них —
        # LLM-классификатор (create_check), «НЕТ» → обычный RAG-ответ.
        m_create = _CREATE_VERB_RE.match(text)
        if m_create:
            if _CREATE_CTX_RE.search(text):
                return Route("create", (m_create.group("rest") or "").strip())
            return Route("create_check", text)
        # Свободный текст: с каталогом моделей — классификатор «вопрос по
        # документации?» (ДА → RAG, НЕТ → агентная сессия, «агент по
        # умолчанию»); без каталога — прежнее поведение (всё в RAG).
        if _agent_models:
            return Route("agent_check", text)
        return Route("ask", text)

    if chat_type in ("group", "supergroup"):
        if _mentioned(text, bot_username):
            q = _strip_mention(text, bot_username)
            sub = _CMD_RE.match(q)
            if sub and sub.group(1).lower() == "ask":
                return Route("ask", sub.group(2).strip())
            return Route("ask", q)
        return None

    return None


# ---------------------------------------------------------------------------
# LLM (локальная)
# ---------------------------------------------------------------------------

RAG_MARKER = "РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ"

# /no_think в конце системных промптов — мягкий переключатель qwen3: без него
# reasoning вдвое длиннее ответа и генерация в 2-3 раза медленнее
# (замер 10.10.2026: 13.4с -> 4.9с, reasoning 1209 -> 2 симв.).

RAG_SYSTEM = (
    "Ты — бот «Штурман». Отвечай на русском, кратко. Опирайся только на "
    "приведённые документы; путь к документу-источнику указывай прямо в "
    "предложении (например: «см. AGENTS.md»). Маркеры-сноски вида [1] не "
    "оставляй. Если в документах есть перечень (например, список модулей или "
    "эндпоинтов) — перечисляй его полностью и не пиши «список не указан», "
    "когда перечень есть в контексте. "
    "Не выводи собственных разделов «Источники:» и «Цитаты:» — ответ должен "
    "состоять только из текста ответа с указанием путей в предложениях. "
    "Если документов недостаточно для точного ответа, "
    "честно скажи об этом (например: «Не знаю — в приведённых документах об "
    "этом нет данных»), а не домысливай факты. В конце ответа задай ОДИН "
    "короткий уточняющий вопрос, только если он реально помогает и ещё не "
    "звучал в диалоге. Если контекст диалога уже снимает уточнение — не "
    "спрашивай.\n/no_think"
)

PLAIN_SYSTEM = (
    "Ты — бот «Штурман», ассистент по устройству проекта «Штурман»: "
    "веб-стенд рассуждений (Kotlin/Ktor бэкенд, React-фронтенд, интеграции "
    "MCP) и Telegram-бот рядом с ним. Отвечай на вопросы на русском, кратко "
    "и по существу, в терминах этого проекта. "
    "Уточняющий вопрос в конце задавай только если он реально помогает и ещё "
    "не звучал в диалоге; иначе отвечай без него. "
    "Если не знаешь — так и скажи.\n/no_think"
)


def _hydrate_chunks(chunks: list[dict]) -> list[dict]:
    """Гидрация мелких заголовочных чанков полным текстом секции из корпуса.

    Поиск на коротких вопросах поднимает чанки-заголовки секций (~50 токенов,
    без содержимого), отодвигая содержательные чанки за пределы top-k.
    Если чанк короткий, подтягиваем из исходного .md его корневую секцию
    целиком — так в контент попадают списки (например, перечень прошивок).
    """
    hydrated: list[dict] = []
    for c in chunks:
        if len(c.get("content") or "") < _HYDRATE_MIN_CHARS:
            full = _section_text(c.get("source") or "", c.get("section") or "")
            if full:
                c = {**c, "content": full, "hydrated": True}
        hydrated.append(c)
    return hydrated


def _section_text(source: str, section: str, max_chars: int = 6000) -> str | None:
    """Полный текст корневой секции ``section`` из файла ``source`` корпуса.

    Секции в индексе вида «A > B > C»; берём корень A и вырезаем из файла
    всё от его заголовка до заголовка того же или более высокого уровня.
    Возвращает None, если файл/секция не найдены.
    """
    root_dir = _rag_docs_root().resolve()
    # normpath без resolve(): resolve() проходит по симлинкам корпуса
    # (docs/ru/<repo> → живое репо) и ломает проверку «внутри корня».
    path = Path(os.path.normpath(str(root_dir / source)))
    if root_dir not in path.parents or not path.is_file():
        return None
    root = (section or "").split(" > ")[0].strip()
    if not root:
        return None
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None
    start = next(
        (i for i, ln in enumerate(lines)
         if re.match(r"^#{1,6}\s+", ln) and root in ln),
        None,
    )
    if start is None:
        return None
    level = len(lines[start]) - len(lines[start].lstrip("#"))
    end = next(
        (i for i in range(start + 1, len(lines))
         if re.match(r"^#{1,6}\s+", lines[i])
         and (len(lines[i]) - len(lines[i].lstrip("#"))) <= level),
        len(lines),
    )
    text = "\n".join(lines[start:end]).strip()
    if len(text) > max_chars:
        text = text[:max_chars] + "\n…"
    return text or None


def _build_rag_block(chunks: list[dict], stable_extra: str = "",
                     wants_file: bool = False,
                     system: str = RAG_SYSTEM) -> str:
    """Собирает RAG-блок для system-промта (по образцу RagPrompts.buildRagBlock).

    Порядок «стабильное -> переменное» (правила, каталог, первоисточник — до
    чанков) даёт LM Studio переиспользовать KV-кэш префикса между запросами.
    ``wants_file`` добавляет правило <file> — только для «файловых» вопросов.
    ``system`` позволяет сменить роль (диагностика использует свой промпт).
    """
    chunks = _hydrate_chunks(chunks)
    lines = [RAG_MARKER, system, _DOC_FETCH_RULE]
    if wants_file:
        lines.append(_FILE_FETCH_RULE)
    catalog = _build_catalog_block()
    if catalog:
        lines.append("\n\n" + catalog)
    if stable_extra:
        lines.append(stable_extra)
    used = sum(len(x) for x in lines)
    for i, c in enumerate(chunks, 1):
        source = c.get("source", "")
        section = c.get("section", "")
        content = c.get("content", "")
        # «Документ N», а не «[N]»: квадратные скобки модель зеркалит в ответ
        # как маркеры-сноски, которых в ответах быть не должно.
        prefix = f"\n\nДокумент {i}: {source} · раздел: {section}\n"
        budget = MAX_RAG_BLOCK_CHARS - used - len(prefix)
        if budget < 20:
            break
        if len(content) > budget:
            content = content[: budget - 1].rstrip() + "…"
        lines.append(prefix + content)
        used += len(prefix) + len(content)
    return "".join(lines)


# --- Каталог корпуса и подтяжка первоисточника (RAG -> вики) ----------------

# Если retrieval не принёс нужных данных, модель по каталогу запрашивает
# документ маркером <doc>путь</doc>, бот подгружает полный текст страницы
# из зеркала вики и перегенерирует ответ.
_DOC_FETCH_RULE = (
    "\n\nЕсли нужных данных в приведённых ниже документах нет, но в каталоге "
    "документов выше есть подходящий по смыслу документ — последней строкой "
    "ответа выведи ровно одну строку вида <doc>путь_из_каталога</doc> и "
    "ничего после неё (уточняющий вопрос, если он уместен, — перед этой "
    "строкой). Не выдумывай пути — бери их только из каталога. Выбирай "
    "документ по смыслу ВОПРОСА, а не по найденным ниже чанкам: вопросы "
    "«кто/где может прошить/установить, контакты, мастера, сервисы» — это "
    "документ «Контакты», а не «Установка»."
)
def _contacts_doc_for(question: str) -> str:
    """Какая контактовая страница нужна: ROX — своя таблица партнёров."""
    if re.search(r"\bрокс\b|\brox\b", question, re.I):
        return "ru/contacts_rox.md"
    return "ru/contacts.md"


_DOC_USE_HINT = (
    "\n\n(Это полный текст запрошенного документа. Используй его данные в "
    "ответе конкретно: города, имена, цифры, контакты. В вопросах про "
    "контакты перечисляй ВСЕ контакты нужной строки/раздела таблицы, каждый "
    "отдельным пунктом со своей ссылкой; текст ссылки — имя или ник "
    "контакта, а не внутренний путь документа. Не пересказывай общими "
    "словами и не обрывай перечень.)"
)
_DOC_RE = re.compile(r"<doc>([^<>\n]{2,120})</doc>")
_DOC_REQUESTS_MAX = 2  # сколько документов подтягиваем за один ответ
_DOC_MAX_CHARS = 12000  # потолок полного текста одного первоисточника
# Зеркало конфига индексера [filters].exclude_paths — тестовые страницы
# не должны попадать в каталог для запросов модели.
_DOC_EXCLUDE = {
    "ru/test.md",
    "ru/test-page.md",
    "ru/test_haval_table.md",
    "ru/Test-install.md",
}
# Вопросы «кто/где может прошить/установить, контакты, мастера» — детерминированная
# подтяжка ru/contacts.md до генерации: выбор документа моделью здесь нестабилен
# (уходит в «Установку» или тестовые страницы), а контактовая страница в корпусе одна.
_CONTACTS_INTENT_RE = re.compile(
    r"кто\s+(может|прошь|делает)"
    r"|где[^.?!\n]{0,40}(прошить|установить|починить|перепрошить)"
    r"|\bконтакт|\bсервис|\bмастер|\bпартн[её]р"
    r"|(?:прошив|установк|перепрошив)[а-яё]*[^.?!\n]{0,60}(?-i:\b[Вв]о?\s+[А-ЯЁ])",
    re.I,
)
_CONTACTS_DOC = "ru/contacts.md"
# Схема таблицы контактов: модель на 0.1 порой «сжимает» одну из колонок
# (обычно MAX) — явное требование обеих колонок стабилизирует перечень.
_CONTACTS_DOC_HINT = (
    "\n\n(Таблица контактов имеет колонки: Город | Контакт в телеграмм | "
    "Контакт в MAX. Для города из вопроса перечисляй контакты ИЗ ОБЕИХ "
    "колонок — телеграм и MAX — каждый отдельным пунктом со своей ссылкой.)"
)
_CONTACTS_ROW_RE = re.compile(r"^\|([^|\n]+)\|([^|\n]+)\|([^|\n]*)\|\s*$", re.M)

# --- Выдача файлов проекта (project_files.py) -------------------------------
# Вопрос «покажи, как устроена X»: модель после RAG-ответа называет файл
# маркером <file>путь</file>, бот читает его из дерева проекта (project_files)
# и присылает документом. Правило в промпт добавляем только для «файловых»
# вопросов — без него модель начинает приписывать файлы к любым ответам.
_FILE_RE = re.compile(r"<file>([^<>\n]{2,200})</file>")
_FILE_REQUESTS_MAX = 2   # сколько файлов присылаем за один ответ
_FILE_INTENT_RE = re.compile(
    r"(?:покажи|пришли|скинь|отправь|дай)\s+(?:мне\s+)?"
    r"(?:файл\w*|исходник\w*)\s*[:.]?\s*(.*)",
    re.I,
)
_WANTS_FILE_RE = re.compile(
    r"покажи|пришли|скинь|отправь|файл\w*|исходник\w*"
    r"|как устроен\w*|как реализован\w*|реализац\w*",
    re.I,
)
_FILE_FETCH_RULE = (
    "\n\nЕсли пользователь просит показать/прислать файл или посмотреть, как "
    "устроена/реализована возможность в коде проекта, — последней строкой "
    "ответа (после строки <doc>, если она есть) выведи ровно одну строку вида "
    "<file>путь/от/корня/проекта</file> с путём к самому релевантному файлу "
    "исходников, и ничего после неё. Путь — относительно корня проекта "
    "(например: src/main/kotlin/com/example/aiweb/routes/Routes.kt); если "
    "точный путь неизвестен — укажи хотя бы верное имя файла. Не добавляй эту "
    "строку, если файл в ответе не нужен."
)
_FILE_NOT_FOUND_NOTE = "Не нашёл файл «{req}» в проекте."

# --- Интент «создай issue» из свободной фразы -------------------------------
# Глагол создания ТОЛЬКО в начале фразы и только в императиве/инфинитиве —
# «создавалась ли ошибка» не матчится. Быстрый путь: глагол + слово контекста
# (issue/задача/ошибка…) → сразу черновик. Медленный: глагол без контекста →
# LLM-классификатор ДА/НЕТ (_handle_create_check); «НЕТ» → обычный RAG-ответ,
# где раньше всех работают deps-интент и прочие.
_CREATE_VERB_RE = re.compile(
    r"^\s*(?:ну\s+)?(?P<verb>созда(?:й(?:те)?)?|создать|заве(?:ди|сти)"
    r"|оформ(?:и|ить)|зарегистрир(?:уй|овать)|поста(?:вь|вить)"
    r"|запол(?:ни|нить))\b[,!\s]*(?P<rest>.*)$",
    re.I,
)
_CREATE_CTX_RE = re.compile(
    r"issue|ишью|задач|баг|ошибк|тикет|дефект|проблем|глюк"
    r"|фич\w|улучшени|эпик|бэклог|backlog|карточк",
    re.I,
)
_CREATE_CHECK_SYSTEM = (
    "Ты — маршрутизатор команд Telegram-бота. Ответь РОВНО ОДНИМ словом: ДА "
    "или НЕТ. ДА — если пользователь просит СОЗДАТЬ новую запись/issue/"
    "задачу/баг-репорт (например: «создай тикет», «заведи карточку», «поставь "
    "задачу на рефакторинг»). НЕТ — если он спрашивает, как что-то устроено, "
    "просит показать/объяснить/найти, или создание не подразумевается. "
    "Ничего кроме ДА/НЕТ не пиши.\n/no_think"
)

# Интент «почему не работает X» (диагностика): сначала issue на GitHub,
# затем RAG-вердикт. Шаблон узкий — обычные вопросы («как работает X»,
# «что умеет X») не перехватывает.
_DIAGNOSE_INTENT_RE = re.compile(
    r"почему\s+(?:[^.?!]{0,60})?(?:не\s+)?"
    r"(?:работает|функционирует|запускается|стартует|отвечает|собирается|"
    r"компилируется|подключается|падает|ломается|виснет|умирает|"
    r"вы(?:даёт|дает)\s+ошибк|ошибк)",
    re.I,
)

# Интент «какие задачи созданы / есть ли issue»: список свежих issue GitHub
# без LLM. «Создай задачу» не перехватывает (глагол создания матчится только
# в начале фразы, а «создан» — страдательное причастие, не императив).
_ISSUES_INTENT_RE = re.compile(
    r"(?:создан\w*\s+(?:задач|issue|ишью|тикет|карточк|баг)"
    r"|задач\w*\s+(?:в\s+)?(?:гитхаб\w*|github|репозитор)"
    r"|(?:какие|есть\s+ли|покажи|покажет|список|открыт\w*|все|сколько)\s+"
    r"[^.?!]{0,30}?(?:задач|issue|ишью|тикет|карточк|баг\w*))",
    re.I,
)

# Вопрос про задачи в openspec/документации — не про GitHub-issue.
_ISSUES_INTENT_NEG_RE = re.compile(r"openspec|спека?х|документац", re.I)


# Интент «что потребуется для доработки X»: задача анализу мини-агентом
# (прочитать файлы, сказать что и как менять), а не вопрос по документации —
# LLM-классификатор такие фразы относит к RAG и честно отвечает «данных нет».
_IMPROVE_INTENT_RE = re.compile(
    r"(?:что\s+(?:нужно|потребуется|требуется)[,]?\s+"
    r"(?:для|чтобы|изменить|доработать|поменять|переработать|править)"
    r"|как\s+(?:доработать|реализовать|изменить|переделать)"
    r"|(?:распиши|опиши|перечисли|расскажи)\s+(?:что|какие)\s+"
    r"(?:(?:нужно|потребуется|требуется|необходимо|надо|следует)\s*)?"
    r"(?:изменить|доработать|поменять|поправить|переработать)"
    r"|что\s+дорабатывать|план\s+(?:доработки|реализации|изменений)"
    r"|какие\s+изменения\s+(?:нужны|потребуются))",
    re.I,
)

# Ссылка на GitHub-issue (URL) или упоминание по номеру («issue #7»,
# «задачу #7») в тексте задачи агента.
_ISSUE_URL_RE = re.compile(
    r"github\.com/([\w.-]+)/([\w.-]+)/issues/(\d+)", re.I)
_ISSUE_NUM_RE = re.compile(
    r"(?:issue|issues|задач[а-яё]*|тикет\w*)\s*#(\d+)", re.I)


async def _github_issue_block(text: str) -> str:
    """Контент GitHub-issue (заголовок + тело) для задачи агента.

    Ищет ссылку github.com/.../issues/N или «issue/задачу #N» (репо — первое
    из секции github config). Возвращает "" при отсутствии ссылки или сбое
    GitHub — задача не блокируется. Тело капится 6000 символов.
    """
    m = _ISSUE_URL_RE.search(text)
    if m:
        repo = f"{m.group(1)}/{m.group(2)}"
        num = int(m.group(3))
    else:
        m2 = _ISSUE_NUM_RE.search(text)
        if not m2:
            return ""
        gh = github_watcher.load_settings()
        repos = list(getattr(gh, "repos", None) or [])
        if not repos:
            return ""
        repo = repos[0].strip()
        num = int(m2.group(1))
    token = getattr(github_watcher.load_settings(), "token", "") or ""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        async with httpx.AsyncClient(timeout=15.0) as cl:
            resp = await cl.get(
                f"https://api.github.com/repos/{repo}/issues/{num}",
                headers=headers)
            resp.raise_for_status()
            data = resp.json()
    except (httpx.TimeoutException, httpx.HTTPError, OSError, ValueError) as exc:
        log.warning("agent: issue #%s не получен (%s) — задача без контента",
                    num, _safe_err(exc))
        return ""
    title = (data.get("title") or "").strip()
    body = (data.get("body") or "").strip()
    if not title and not body:
        return ""
    if len(body) > 6000:
        body = body[:6000] + "\n… (обрезано)"
    return f"ИССУЭ #{num} «{title}»:\n{body}".strip()


def _is_issues_intent(question: str) -> bool:
    m = _ISSUES_INTENT_RE.search(question or "")
    return bool(m and not _ISSUES_INTENT_NEG_RE.search(question or ""))


def _city_stem(s: str) -> str:
    s = s.strip().lower()
    return s[: max(4, len(s) - 2)]  # срез падежного окончания: «москве» -> «моск»


def _contacts_supplement(question: str, answer: str, doc_text: str) -> str:
    """Добор из таблицы контактов ссылок, которые модель сжала в ответе.

    Причина: длинные строки таблицы (9+ ссылок в двух колонках) модель на
    генерации урезает; здесь недостающие URL строки(ок) города из вопроса
    добавляются детерминированно, дедуп по URL.
    """
    m = re.search(r"\b(?:в|во)\s+([А-ЯЁ][а-яё\-]+(?:\s+[А-ЯЁ][а-яё\-]+)?)", question)
    city_stem = _city_stem(m.group(1)) if m else ""
    answer_urls = {u.rstrip(".,;") for u in re.findall(r"https?://[^\s)\]]+", answer)}
    lines: list[str] = []
    city_label = ""
    for city, tg_cell, max_cell in _CONTACTS_ROW_RE.findall(doc_text):
        c = city.strip().lower()
        if not c or c.startswith("---"):
            continue
        # город из вопроса фильтрует строки; без города добираем все строки
        if city_stem and not c.startswith(city_stem):
            continue
        if not city_label:
            city_label = city.strip()
        for cell in (tg_cell, max_cell):
            for text, url in re.findall(r"\[([^\]]+)\]\((https?://[^)\s]+)\)", cell):
                if url not in answer_urls:
                    answer_urls.add(url)
                    lines.append(f"- [{text}]({url})")
    if not lines:
        return ""
    scope = f"строка «{city_label}»" if city_stem else "все строки таблицы"
    head = f"\n\nПолный список контактов ({scope} таблицы «Контакты»):"
    return head + "\n" + "\n".join(lines)

_doc_catalog_cache: list[tuple[str, str, list[str]]] | None = None


def _rag_docs_root() -> Path:
    """Корень корпуса документации (первоисточник для подтяжки полных страниц)."""
    env = os.environ.get("RAG_DOCS_ROOT", "").strip()
    if env:
        return Path(env)
    return Path(__file__).resolve().parent / "docs"


def _strip_frontmatter(text: str) -> str:
    if text.startswith("---"):
        end = text.find("\n---", 3)
        if end != -1:
            return text[end + 4:].lstrip("\n")
    return text


def _doc_catalog() -> list[tuple[str, str, list[str]]]:
    """Каталог документов корпуса: (путь, H1-заголовок, первые H2-разделы)."""
    global _doc_catalog_cache
    if _doc_catalog_cache is not None:
        return _doc_catalog_cache
    root = _rag_docs_root()
    entries: list[tuple[str, str, list[str]]] = []
    if root.is_dir():
        locale_rank = {"ru": 0, "en": 1, "ar": 2}
        files = sorted(
            root.rglob("*.md"),
            key=lambda p: (locale_rank.get(p.parent.name if p.parent != root else "", 3), str(p)),
        )
        for f in files:
            rel = f.relative_to(root).as_posix()
            if rel in _DOC_EXCLUDE:
                continue
            try:
                text = _strip_frontmatter(f.read_text(encoding="utf-8", errors="replace"))
            except OSError:
                continue
            title = ""
            sections: list[str] = []
            for line in text.splitlines():
                s = line.strip()
                if not title and s.startswith("# ") and not s.startswith("##"):
                    title = s[2:].strip()
                elif s.startswith("## ") and len(sections) < 6:
                    sections.append(s[3:].strip())
            entries.append((rel, title or rel, sections))
    _doc_catalog_cache = entries
    return entries


def _catalog_paths() -> set[str]:
    return {rel for rel, _, _ in _doc_catalog()}


def _build_catalog_block() -> str:
    entries = _doc_catalog()
    if not entries:
        return ""
    lines = ["КАТАЛОГ ДОКУМЕНТОВ КОРПУСА (путь — заголовок — разделы):"]
    for rel, title, sections in entries:
        lines.append(f"- {rel} — {title} — {'; '.join(sections) if sections else '—'}")
    block = "\n".join(lines)
    if len(block) > 4500:
        block = block[:4500].rsplit("\n", 1)[0]
    return block


def _take_doc_requests(content: str, allowed: set[str]) -> tuple[str, list[str]]:
    """Вырезает маркеры <doc>путь</doc>; возвращает (чистый текст, запросы)."""
    requested: list[str] = []

    def _sub(m: re.Match) -> str:
        p = m.group(1).strip()
        if p in allowed and p not in requested:
            requested.append(p)
        return ""

    text = _DOC_RE.sub(_sub, content)
    text = re.sub(r"\n{3,}", "\n\n", text).rstrip()
    return text, requested[:_DOC_REQUESTS_MAX]


def _load_source_docs(paths: list[str]) -> str:
    """Полные тексты страниц вики из зеркала корпуса (первоисточник)."""
    root = _rag_docs_root().resolve()
    parts: list[str] = []
    for rel in paths:
        f = (root / rel).resolve()
        if root not in f.parents or not f.is_file():
            continue
        try:
            text = _strip_frontmatter(f.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            continue
        if len(text) > _DOC_MAX_CHARS:
            text = text[:_DOC_MAX_CHARS].rstrip() + "…"
        parts.append(f"\n\nПЕРВОИСТОЧНИК {rel} (полный текст страницы вики):\n{text}")
    return "".join(parts) + _DOC_USE_HINT if parts else ""


def _take_file_requests(content: str) -> tuple[str, list[str]]:
    """Вырезает маркеры <file>путь</file>; возвращает (чистый текст, запросы)."""
    requested: list[str] = []

    def _sub(m: re.Match) -> str:
        p = m.group(1).strip()
        if p and p not in requested:
            requested.append(p)
        return ""

    text = _FILE_RE.sub(_sub, content)
    text = re.sub(r"\n{3,}", "\n\n", text).rstrip()
    return text, requested[:_FILE_REQUESTS_MAX]


async def _send_project_file(chat_id, rel: str, reply_to=None) -> bool:
    """Читает файл проекта и присылает документом. False — не удалось."""
    text = project_files.read_text(rel)
    if text is None:
        return False
    await _send_document(
        chat_id,
        os.path.basename(rel),
        text,
        caption=f"Файл: {rel}",
        reply_to=reply_to,
    )
    return True


async def _send_file_intent(job: Job, query: str) -> bool:
    """Прямой интент «покажи файл <имя>» — без LLM.

    True — запрос закрыт (файл отправлен или предложен список кандидатов);
    False — по запросу ничего не нашли / имени нет, вопрос продолжается
    через RAG.
    """
    query = query.strip().strip("`\"'").strip()
    if not query:
        return False
    matches = project_files.find_files(query)
    if not matches:
        return False
    # Точное совпадение имени у первого кандидата = однозначный выбор.
    base = os.path.basename(query.lower().strip("."))
    if len(matches) == 1 or (
        os.path.basename(matches[0]).lower() == base
        and all(os.path.basename(m).lower() != base for m in matches[1:])
    ):
        if await _send_project_file(job.chat_id, matches[0], job.reply_to):
            return True
        await _send_text(
            job.chat_id,
            f"Нашёл {matches[0]}, но прочитать его не удалось "
            "(бинарный файл?).",
            job.reply_to,
        )
        return True
    lines = "\n".join(f"• {m}" for m in matches[:8])
    await _send_text(
        job.chat_id,
        "Нашёл несколько файлов — уточните, какой прислать:\n" + lines,
        job.reply_to,
    )
    return True


async def _rag_search(
    question: str, *, rerank: bool = True, k_before: int = 15
) -> list[dict]:
    async with httpx.AsyncClient(timeout=httpx.Timeout(RAG_TIMEOUT)) as client:
        resp = await client.post(
            _rag_search_url(),
            json={
                "query": question,
                "k": _rag_k(),
                "strategy": _rag_strategy(),
                "k_before": k_before,
                "rerank": rerank,
            },
        )
        resp.raise_for_status()
        data = resp.json()
        if not isinstance(data, list):
            raise ValueError("RAG: неожиданный ответ (ожидался список чанков)")
        return data


def _extract_text(choice: dict) -> tuple[str, str]:
    """Текст ответа и finish_reason из choice."""
    msg = choice.get("message") or {}
    content = msg.get("content") or ""
    return content, choice.get("finish_reason") or ""


def _accumulate_usage(usage) -> None:
    """Копит usage LM Studio (pending — для БД, session — для отчёта /admin)."""
    if not isinstance(usage, dict):
        return
    prompt = usage.get("prompt_tokens")
    completion = usage.get("completion_tokens")
    total = usage.get("total_tokens")
    p = prompt if isinstance(prompt, int) and prompt > 0 else 0
    c = completion if isinstance(completion, int) and completion > 0 else 0
    t = total if isinstance(total, int) and total > 0 else p + c
    _pending_usage["prompt_tokens"] += p
    _pending_usage["completion_tokens"] += c
    _pending_usage["total_tokens"] += t
    _session_usage["prompt_tokens"] += p
    _session_usage["completion_tokens"] += c
    _session_usage["total_tokens"] += t


# Транзиентные сбои LM Studio (сервер на миг отказал в соединении) не должны
# убивать ответ: ретраи только на транспортных ошибках, HTTP 4xx/5xx — как есть.
_LM_RETRIES = 4   # покрытие ~30 с: перезагрузка модели в LM Studio GUI
_LM_RETRY_WAIT = 2.0

#: Таймауты одного LLM-вызова в агентном цикле подряд: столько раз шаг
#: повторяется, прежде чем задача отменится (reasoning-модели GPUstack
#: иногда думают дольше таймаута провайдера).
_AGENT_TIMEOUT_RETRIES = 2


async def _post_chat(messages: list[dict], max_tokens: int):
    """Один POST /chat/completions. Возвращает (content, finish_reason)."""
    payload = {
        "model": _lm_model(),
        "messages": messages,
        "temperature": _lm_temperature(),
        # Гвард дегенерации малой модели (петля «одна строка по кругу»,
        # наблюдена 18.10.2026 на длинном ответе): мягкий штраф за повторы,
        # отключается env LM_FREQUENCY_PENALTY=0.
        "frequency_penalty": _float_env("LM_FREQUENCY_PENALTY", 0.3),
        "max_tokens": max_tokens,
    }
    last_exc: Exception | None = None
    for attempt in range(_LM_RETRIES + 1):
        try:
            resp = await _get_lm_client().post(
                f"{_lm_api_base().rstrip('/')}/chat/completions", json=payload
            )
            resp.raise_for_status()
            data = resp.json()
            _accumulate_usage(data.get("usage"))
            choices = data.get("choices") or []
            if not choices:
                return "", ""
            return _extract_text(choices[0])
        except (httpx.ConnectError, httpx.RemoteProtocolError) as exc:
            last_exc = exc
            if attempt < _LM_RETRIES:
                wait = _LM_RETRY_WAIT * (attempt + 1)
                log.warning(
                    "bot: LM недоступна (%s), повтор %d/%d через %.0f c",
                    _safe_err(exc), attempt + 1, _LM_RETRIES, wait,
                )
                await asyncio.sleep(wait)
    assert last_exc is not None
    raise last_exc


async def _chat_once(messages: list[dict], max_tokens: int,
                     provider: dict | None = None):
    """Один POST /chat/completions. Возвращает (content, finish_reason).

    provider — запись каталога agent.models: вызов идёт к её api_base/model
    (ключ из agent.api_keys/env); None — локальная LM по умолчанию.
    """
    if provider is not None:
        return await _post_chat_provider(provider, messages, max_tokens)
    return await _post_chat(messages, max_tokens)


# Достройка оборванного ответа: частичный текст возвращается модели как
# assistant-сообщение, продолжение приклеивается к нему. Кап раундов —
# предохранитель от зацикливания.
_LM_CONTINUATIONS_MAX = 2
_CONTINUATION_ASK = (
    "Твой предыдущий ответ выше оборван на середине. Продолжи его с места "
    "обрыва: если последний элемент не дописан — сначала закончи именно его; "
    "затем продолжай дальнейшее содержание до логического конца ответа. "
    "Уже перечисленные пункты не повторяй и не переписывай заново — сразу "
    "новое содержание. Вступлений о продолжении не добавляй."
)


async def _generate(
    system_prompt: str, question: str, history: list[dict] | None = None,
    provider: dict | None = None,
) -> tuple[str, str]:
    """Генерация с ретраем при пустом content и достройкой при обрыве.

    Пустой content при finish=length — ретрай с удвоенным лимитом. Непустой,
    но оборванный (finish=length) — достраивается continuation-раундами
    (не более _LM_CONTINUATIONS_MAX). history — предшествующие пары
    вопрос-ответ этого пользователя. provider — модель из каталога
    agent.models (None — локальная LM). Возвращает (content, finish_reason
    последнего вызова).
    """
    messages = [{"role": "system", "content": system_prompt}]
    messages.extend(history or [])
    messages.append({"role": "user", "content": question})
    max_tokens = _lm_max_tokens()
    if provider is not None:
        max_tokens = int(provider.get("max_tokens") or max_tokens)
    content, finished = await _chat_once(messages, max_tokens, provider)
    if not content and finished == "length":
        content, finished = await _chat_once(messages, max_tokens * 2, provider)
    rounds = 0
    while finished == "length" and content and rounds < _LM_CONTINUATIONS_MAX:
        tail, finished = await _chat_once(
            messages
            + [{"role": "assistant", "content": content},
               {"role": "user", "content": _CONTINUATION_ASK}],
            max_tokens,
            provider,
        )
        if not tail:
            break
        content += tail
        rounds += 1
        log.warning("bot: ответ достроен (раунд %d, +%d симв.)", rounds, len(tail))
    return content, finished


# ---------------------------------------------------------------------------
# «Мини-агент»: правка файлов проекта по задаче (см. openspec/changes/
# add-agent-mode-telegram). Сессия и FSM — agent_session.py, запись —
# project_edit.py; здесь — проводка: роутинг, клавиатура модели, цикл
# с гейтом подтверждения и отчёт.
# ---------------------------------------------------------------------------

AGENT_SYSTEM = (
    "Ты — «Штурман», агент по доработке проекта (репозиторий ai-challenge-9). "
    "С файлами проекта работай ТОЛЬКО текстовыми маркерами:\n"
    "<read path=\"относительный/путь\"> — запросить содержимое файла;\n"
    "<write path=\"файл\">полный новый текст файла</write> — перезаписать файл;\n"
    "<patch path=\"файл\">старый фрагмент\n=====\nновый фрагмент</patch> — заменить "
    "первое вхождение фрагмента (фрагменты разделены строкой из 5 и более "
    "символов '=' на отдельной строке);\n"
    "<report>итог работы</report> — финальная сводка, когда правки готовы.\n"
    "<shell>команда</shell> — запустить команду в корне проекта (тесты, "
    "git-статус, проверка синтаксиса). Команды из белого списка исполняются "
    "сразу, их вывод придёт тебе в следующем шаге; прочие потребуют "
    "подтверждения пользователя. Запрещены: git commit/push и чтение "
    "config.json/.env. После правок прогоняй проверку (py_compile/тесты) "
    "и учитывай результат. Конвейеры с | (grep … | …) НЕ из белого списка — "
    "они уходят в гейт и останавливают работу; для поиска по коду читай "
    "конкретные файлы маркером read, а не шеллом.\n"
    "Задача может быть АНАЛИТИЧЕСКОЙ («что потребуется для доработки X», "
    "«проанализируй») — тогда файлы НЕ меняй: прочитай нужный код (read), "
    "разберись и верни report: список доработок по файлам, как менять, "
    "риски; в конце предложи — приступать к правкам или оформить спеку.\n"
    "Правила: сначала прочитай нужные файлы (read), затем подготовь правки "
    "(write/patch), в конце ОБЯЗАТЕЛЬНО верни report. Одна правка за ответ. "
    "ФИНАЛЬНЫЙ ответ на задачу (анализ, список доработок, итог) — ТОЛЬКО "
    "в маркере <report>…</report>; обычный текст без маркера считается "
    "незавершённой работой, а не ответом.\n"
    "Пути — относительные от корня проекта. Файлы config.json, .env и каталоги "
    ".git/, node_modules/, data/, frontend/dist/, build/, backups/ "
    "запрещены — правки туда отклоняются; каталог openspec/ ДОСТУПЕН для "
    "записи спек: openspec/changes/<slug>/proposal.md, tasks.md и "
    "specs/<capability>/spec.md (требования ADDED со Scenario-блоками). "
    "Коммиты не делаешь. Отвечай на "
    "русском, кратко.\n/no_think"
)

_AGENT_MAX_ITERATIONS = 5     # итераций LLM на задачу
_AGENT_MAX_EMPTY = 2          # повторов при ответе без маркеров
_AGENT_FILE_NOTE_CHARS = 12_000  # кап содержимого файла в контексте итерации
_AGENT_NOTE_TRUNC = "... (обрезано в контексте агента)"

_AGENT_BUSY_MSG = ("Агент сейчас работает над задачей. Дождитесь отчёта или "
                   "напишите /agent отмена.")
_AGENT_USAGE = ("Запуск агента: /agent <текст задачи>\n"
                "Пример: /agent добавь поле «тема» в панель настроек\n"
                "Отмена: /agent отмена")

_CONFIRM_WORDS = frozenset({
    "применяй", "применить", "примени", "да", "давай", "ок", "окей", "хочу",
    "apply", "yes",
})
_CANCEL_WORDS = frozenset({
    "отмена", "отменить", "отмен", "нет", "стоп", "не надо", "cancel", "no",
})

# FOLLOWUP: слова-продолжения после аналитического отчёта агента («спека»,
# «делай»…). Императив в начале фразы; остальной текст — дополнение задачи.
_FOLLOWUP_RUN_RE = re.compile(
    r"^(?:ну\s+)?(?:оформи\s+спек\w*|спек\w*|спека|правки|применяй|примени"
    r"|делай|приступай|реализуй|продолжай|давай|ок(?:ей)?|хорошо|да)\b",
    re.I,
)

_agent_sessions = agent_session.SessionRegistry()
_agent_models: list[dict] = agent_session.parse_models(_config)
_shell_allow: list[str] = project_shell.parse_allow(_config)
# message_id сообщения с клавиатурой выбора модели (для editMessageReplyMarkup)
_agent_kb_msg: dict[int, int] = {}

# ---------------------------------------------------------------------------
# Универсальный выбор модели перед LLM-действием (ask/create/filediff):
# действие ставится на паузу, пользователь выбирает модель кнопками.
# ---------------------------------------------------------------------------
_LLM_PICK_TTL = 30 * 60.0
# user_id → {"job": Job, "kb_msg": int|None, "ts": float}
_llm_pending: dict[int, dict] = {}


def _job_provider(job: Job) -> dict | None:
    """Запись модели из job.provider_id (None — локальная LM по умолчанию)."""
    if not job.provider_id:
        return None
    return _agent_model_by_id(job.provider_id)


def _extinguish_kb(chat_id, msg_id) -> None:
    """Погасить inline-клавиатуру сообщения (кнопка одноразовая)."""
    if msg_id is None:
        return

    async def _run() -> None:
        try:
            await _safe_api("editMessageReplyMarkup", {
                "chat_id": chat_id, "message_id": msg_id,
                "reply_markup": {"inline_keyboard": []},
            })
        except Exception:  # noqa: BLE001 - гашение клавиатуры не критично
            pass

    try:
        asyncio.get_running_loop().create_task(_run())
    except RuntimeError:
        pass


async def _stash_llm_choice(job: Job) -> None:
    """Поставить LLM-действие на паузу: показать клавиатуру выбора модели.

    Новая задача от того же пользователя заменяет отложенную (старая
    отменяется с уведомлением). Без каталога agent.models не вызывается —
    бот работает по-старому, на локальной LM.
    """
    prev = _llm_pending.pop(job.user_id, None)
    if prev is not None:
        _extinguish_kb(prev["job"].chat_id, prev.get("kb_msg"))
        await _send_text(prev["job"].chat_id,
                         "Предыдущее действие отменено — пришла новая задача.")
    task = (job.question or "").strip()
    if len(task) > 160:
        task = task[:157] + "…"
    text = "Выберите модель для задачи:"
    if task:
        text += f"\n«{task}»"
    result = await _send_model_keyboard(job.chat_id, job.reply_to,
                                        _agent_models, cb_prefix="pick",
                                        text=text)
    msg_id = result.get("message_id") if isinstance(result, dict) else None
    _llm_pending[job.user_id] = {
        "job": job, "kb_msg": msg_id, "ts": time.monotonic(),
    }


def _agent_model_by_id(model_id: str) -> dict | None:
    """Запись каталога моделей по id или None."""
    for record in _agent_models:
        if record.get("id") == model_id:
            return record
    return None


def _resolve_api_key(provider: dict) -> str:
    """Значение API-ключа провайдера. Источники: env-переменная (имя —
    ``api_key_env``) или секция ``agent.api_keys`` config.json (gitignored).
    Значение не логировать и не печатать."""
    name = (provider.get("api_key_env") or "").strip()
    if not name:
        return ""
    val = (os.environ.get(name) or "").strip()
    if not val:
        section = _config.get("agent") if isinstance(_config, dict) else None
        keys = section.get("api_keys") if isinstance(section, dict) else None
        if isinstance(keys, dict):
            val = str(keys.get(name) or "").strip()
    return val


_provider_clients: dict[str, httpx.AsyncClient] = {}


def _get_provider_client(provider: dict) -> httpx.AsyncClient:
    """HTTP-клиент под провайдера: локальная LM — общий клиент; облако —
    свой (таймаут из записи, проверка сертификата отключена ТОЛЬКО здесь:
    шлюз GPUstack отдаёт сертификат с истёкшим сроком — как в web-версии,
    AIClient.kt)."""
    provider_id = provider.get("id") or "local"
    if provider_id == "local":
        return _get_lm_client()
    client = _provider_clients.get(provider_id)
    if client is None:
        timeout = float(provider.get("timeout_sec") or 300)
        client = httpx.AsyncClient(timeout=httpx.Timeout(timeout), verify=False)
        _provider_clients[provider_id] = client
    return client


async def _post_chat_provider(provider: dict, messages: list[dict],
                              max_tokens: int):
    """POST /chat/completions к провайдеру (параметры — из записи модели).

    Транспортные ретраи — как у _post_chat. Возвращает (content, finish_reason).
    """
    payload = {
        "model": provider.get("model") or _lm_model(),
        "messages": messages,
        "temperature": _lm_temperature(),
        "frequency_penalty": float(provider.get("frequency_penalty") or 0.0),
        "max_tokens": max_tokens,
    }
    headers = {}
    api_key = _resolve_api_key(provider)
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    base = (provider.get("api_base") or _lm_api_base()).rstrip("/")
    last_exc: Exception | None = None
    for attempt in range(_LM_RETRIES + 1):
        try:
            resp = await _get_provider_client(provider).post(
                f"{base}/chat/completions", json=payload, headers=headers
            )
            resp.raise_for_status()
            data = resp.json()
            _accumulate_usage(data.get("usage"))
            choices = data.get("choices") or []
            if not choices:
                return "", ""
            return _extract_text(choices[0])
        except (httpx.ConnectError, httpx.RemoteProtocolError) as exc:
            last_exc = exc
            if attempt < _LM_RETRIES:
                wait = _LM_RETRY_WAIT * (attempt + 1)
                log.warning(
                    "agent: провайдер %s недоступен (%s), повтор %d/%d через %.0f c",
                    provider.get("id"), _safe_err(exc), attempt + 1,
                    _LM_RETRIES, wait,
                )
                await asyncio.sleep(wait)
    assert last_exc is not None
    raise last_exc


async def _send_model_keyboard(chat_id, reply_to, models: list[dict], *,
                               cb_prefix: str = "agent:model",
                               text: str = "Задача принята. Выберите модель для работы агента:"):
    """Сообщение с inline-клавиатурой выбора модели (по кнопке на модель).

    cb_prefix — префикс callback_data: ``agent:model`` (мини-агент) или
    ``pick`` (универсальный выбор перед LLM-действием).
    Возвращает result sendMessage (message_id нужен для editMessageReplyMarkup).
    """
    rows = [[{"text": m.get("label") or m.get("id", "?"),
              "callback_data": f"{cb_prefix}:{m.get('id')}"}]
            for m in models[:8]]
    rows.append([{"text": "Отмена", "callback_data": f"{cb_prefix}:cancel"}])
    return await _api("sendMessage", {
        "chat_id": chat_id,
        "text": text,
        **({"reply_to_message_id": reply_to} if reply_to is not None else {}),
        "reply_markup": {"inline_keyboard": rows},
    })


async def _handle_callback(cb: dict) -> None:
    """callback_query от inline-клавиатуры: выбор модели агентной сессии.

    Отвечает answerCallbackQuery всегда (иначе Telegram крутит часы),
    клавиатура гасится после выбора; повторное нажатие/чужой выбор —
    «выбор уже сделан».
    """
    data = str(cb.get("data") or "")
    cb_id = cb.get("id")
    from_id = (cb.get("from") or {}).get("id")
    match = re.match(r"^(agent:model|pick):([A-Za-z0-9_\-]+)$", data)

    def _answer(text: str = "") -> None:
        payload: dict = {"callback_query_id": cb_id}
        if text:
            payload["text"] = text
        try:
            asyncio.get_running_loop().create_task(_safe_api(
                "answerCallbackQuery", payload))
        except Exception:  # noqa: BLE001 - ответ на callback не критичен
            pass

    if not match or from_id is None:
        _answer()
        return
    prefix, choice = match.group(1), match.group(2)
    if prefix == "pick":
        await _handle_pick_callback(from_id, choice, _answer)
        return

    # --- мини-агент: выбор модели в сессии ---
    if choice == "cancel":
        session = _agent_sessions.get(from_id)
        if session is not None and session.state == agent_session.TASK_SET:
            _agent_sessions.drop(from_id)
            _agent_kb_msg.pop(from_id, None)
            _answer("Отменено.")
            await _send_text(session.chat_id,
                             "Задача агента отменена, правки не применялись.")
        else:
            _answer("Выбор уже сделан.")
        return
    session = _agent_sessions.get(from_id)
    if session is None or session.state != agent_session.TASK_SET:
        _answer("Выбор уже сделан.")
        return
    provider = _agent_model_by_id(choice)
    if provider is None:
        _answer("Неизвестная модель.")
        return
    session.model_id = provider["id"]
    agent_session.set_state(session, agent_session.MODEL_SELECT)
    agent_session.log_step(session, f"выбрана модель {provider.get('label')}")
    _answer(f"Модель: {provider.get('label')}")
    # Погасить клавиатуру (кнопка одноразовая).
    msg_id = _agent_kb_msg.pop(from_id, None)
    if msg_id is not None:
        await _safe_api("editMessageReplyMarkup", {
            "chat_id": session.chat_id, "message_id": msg_id,
            "reply_markup": {"inline_keyboard": []},
        })
    await _enqueue(Job(session.chat_id, from_id, "agent_run", "", None))


async def _handle_pick_callback(from_id, choice: str, _answer) -> None:
    """callback ``pick:<model_id|cancel>``: запуск отложенного LLM-действия."""
    entry = _llm_pending.pop(from_id, None)
    if entry is None or time.monotonic() - entry["ts"] > _LLM_PICK_TTL:
        _answer("Задача не найдена или устарела — отправьте запрос заново.")
        return
    job: Job = entry["job"]
    _extinguish_kb(job.chat_id, entry.get("kb_msg"))
    if choice == "cancel":
        _answer("Отменено.")
        await _send_text(job.chat_id, "Действие отменено.", job.reply_to)
        return
    provider = _agent_model_by_id(choice)
    if provider is None:
        _answer("Неизвестная модель.")
        return
    _answer(f"Модель: {provider.get('label')}")
    requeued = Job(job.chat_id, job.user_id, job.mode, job.question,
                   job.reply_to, job.documents)
    requeued.provider_id = provider["id"]
    await _enqueue(requeued)


async def _handle_agent(job: Job) -> None:
    """/agent <задача>: создание сессии, отмена, статус."""
    arg = (job.question or "").strip()
    existing = _agent_sessions.get(job.user_id)

    if arg.lower() in _CANCEL_WORDS:
        if existing is not None:
            _agent_sessions.drop(job.user_id)
            _agent_kb_msg.pop(job.user_id, None)
            await _send_text(job.chat_id, "Задача агента отменена, правки "
                                          "не применялись.", job.reply_to)
        else:
            await _send_text(job.chat_id, "Активной задачи агента нет.",
                             job.reply_to)
        return

    if existing is not None:
        if existing.state == agent_session.AWAIT_CONFIRM:
            await _send_text(job.chat_id,
                             "Агент ждёт подтверждения правок: ответьте "
                             "«применяй» или «отмена».", job.reply_to)
            return
        if existing.state != agent_session.FOLLOWUP or not arg:
            # FOLLOWUP без аргумента — подсказка продолжения; прочие
            # состояния — «занят».
            await _send_text(job.chat_id,
                             _AGENT_BUSY_MSG if arg else
                             "Предыдущий отчёт ещё активен: «спека»/«делай» "
                             "— продолжить, /agent отмена — сброс.",
                             job.reply_to)
            return
        # FOLLOWUP + новая задача: заменяем сессию (аналитический отчёт
        # истёк смыслом — стартуем заново).
        _agent_sessions.drop(job.user_id)

    if not arg:
        await _send_text(job.chat_id, _AGENT_USAGE, job.reply_to)
        return

    if not _agent_models:
        await _send_text(job.chat_id,
                         "Агент недоступен: секция agent.models в config.json "
                         "не содержит валидных записей.", job.reply_to)
        return

    issue_block = await _github_issue_block(arg)
    task = f"{arg}\n\n{issue_block}" if issue_block else arg
    session = agent_session.new_session(job.user_id, job.chat_id, task=task)
    agent_session.set_state(session, agent_session.TASK_SET)
    agent_session.log_step(
        session, "задача принята" + (" (с контентом issue из GitHub)"
                                      if issue_block else ""))
    _agent_sessions.set(session)
    result = await _send_model_keyboard(job.chat_id, job.reply_to, _agent_models)
    msg_id = result.get("message_id") if isinstance(result, dict) else None
    if msg_id is not None:
        _agent_kb_msg[job.user_id] = msg_id


async def _handle_agent_note(job: Job) -> None:
    """Свободный текст в состоянии выбора модели: дополнение задачи."""
    session = _agent_sessions.get(job.user_id)
    if session is None:
        return
    session.task = f"{session.task}\n{job.question}".strip()
    agent_session.touch(session)
    await _send_text(job.chat_id, "Дополнил задачу. Завершите выбор модели "
                                  "кнопкой.", job.reply_to)


async def _handle_agent_followup(job: Job) -> None:
    """Свободный текст в состоянии FOLLOWUP (после аналитического отчёта).

    «Спека»/«делай»/«применяй»… — продолжение задачи: агент стартует заново
    с контекстом прошлого отчёта (session.last_report), анализ не повторяется.
    Остальной текст — дополнение задачи (как agent_note). «Отмена» — сброс.
    """
    session = _agent_sessions.get(job.user_id)
    if session is None or session.state != agent_session.FOLLOWUP:
        return
    text = (job.question or "").strip()
    if text.lower() in _CANCEL_WORDS:
        _agent_sessions.drop(job.user_id)
        _agent_kb_msg.pop(job.user_id, None)
        await _send_text(job.chat_id, "Отменено — задача агента сброшена.",
                         job.reply_to)
        return
    if _FOLLOWUP_RUN_RE.match(text):
        agent_session.touch(session)
        agent_session.set_state(session, agent_session.EXECUTE)
        await _send_text(job.chat_id,
                         "Продолжаю задачу с контекстом отчёта — ждите "
                         "результат (или /agent отмена).", job.reply_to)
        await _enqueue(Job(job.chat_id, job.user_id, "agent_run", ""))
        return
    session.task = f"{session.task}\n{text}".strip()
    agent_session.touch(session)
    await _send_text(job.chat_id,
                     "Дополнил задачу. Скажите «спека» (оформить openspec-"
                     "спеку) или «делай» (приступить к правкам) — либо "
                     "/agent отмена.", job.reply_to)


def _agent_file_block(contents: dict[str, str]) -> str:
    """Блок прочитанных файлов для контекста итерации (с капом на файл)."""
    if not contents:
        return ""
    parts: list[str] = ["ПРОЧИТАННЫЕ ФАЙЛЫ:"]
    for path, content in contents.items():
        shown = content
        if len(shown) > _AGENT_FILE_NOTE_CHARS:
            shown = shown[:_AGENT_FILE_NOTE_CHARS] + "\n" + _AGENT_NOTE_TRUNC
        parts.append(f'--- {path} ---\n{shown}')
    return "\n\n".join(parts)


def _agent_log_summary(session) -> str:
    """Лог шагов компактным списком (для контекста итерации)."""
    if not session.steps_log:
        return ""
    return "ЛОГ ШАГОВ:\n" + "\n".join(f"- {s}" for s in session.steps_log[-20:])


async def _handle_agent_run(job: Job) -> None:
    """Агентный цикл: итерации LLM с маркерами read/write/patch/report.

    Правки накапливаются В ПАМЯТИ (session.pending_edits); на диск не
    пишется — гейт подтверждения в _handle_agent_confirm.
    """
    session = _agent_sessions.get(job.user_id)
    if session is None or session.state not in (
            agent_session.MODEL_SELECT, agent_session.EXECUTE):
        return
    provider = _agent_model_by_id(session.model_id)
    if provider is None:
        await _send_text(job.chat_id, "Модель не найдена — задача отменена.")
        _agent_sessions.drop(job.user_id)
        return

    agent_session.set_state(session, agent_session.EXECUTE)
    original: dict[str, str] = {}   # путь -> исходное содержимое (для диффов)
    working: dict[str, str] = {}    # путь -> содержимое после правок
    messages: list[dict] = [{"role": "system", "content": AGENT_SYSTEM}]
    first_prompt = f"ЗАДАЧА:\n{session.task}"
    # FOLLOWUP-продолжение: прошлый аналитический отчёт — в контекст,
    # чтобы агент не начинал анализ заново.
    if session.last_report:
        rep = session.last_report
        if len(rep) > 6000:
            rep = rep[:6000] + "\n… (обрезано)"
        first_prompt += ("\n\nКОНТЕКСТ ПРЕДЫДУЩЕГО ШАГА — твой отчёт по этой "
                         "задаче (продолжай с ним, не повторяй анализ):\n" + rep)
    log_block = _agent_log_summary(session)
    if log_block:
        first_prompt += f"\n\n{log_block}"
    # Результаты команд, исполненных после «применяй» (аналитическая задача
    # продолжается: модель анализирует вывод и возвращает report).
    if session.command_outputs:
        parts = [f"$ {o['cmd']}\n{o['output'] or '(пустой вывод)'}"
                 + ("" if o["code"] == 0 else f"\n[код возврата {o['code']}]")
                 for o in session.command_outputs]
        first_prompt += ("\n\nРЕЗУЛЬТАТЫ КОМАНД (исполнены после подтверждения "
                         "пользователя):\n" + "\n\n".join(parts))
        session.command_outputs = []
    messages.append({"role": "user", "content": first_prompt})

    report_text = ""
    # Последний содержательный ответ модели без маркеров: если цикл кончился
    # без правок и report, принимаем его как отчёт вместо «не смог».
    last_plain = ""
    shell_runs: list[tuple[str, dict]] = []   # исполненные (allow) команды
    shell_denied: list[str] = []              # отвергнутые (commit/push/секреты)
    # Файлы, которые модель запросила, но их нет/нельзя читать: возвращаем
    # ей списком, иначе циклится на повторных read-маркерах (наблюдено на
    # openspec/README.md — файла не существует).
    read_failures: list[str] = []
    empty_rounds = 0
    iterations = 0
    timeouts = 0  # таймауты LLM подряд: <= _AGENT_TIMEOUT_RETRIES — ретрай шага
    while iterations < _AGENT_MAX_ITERATIONS:
        iterations += 1
        agent_session.touch(session)
        await _send_chat_action(job.chat_id, "typing")
        try:
            _step_t0 = time.monotonic()
            content, _finished = await _post_chat_provider(
                provider, messages,
                int(provider.get("max_tokens") or 2000))
            timeouts = 0
            log.info("agent: шаг %d (%s): %d симв. за %.0f с",
                     iterations, provider.get("id"),
                     len(content or ""), time.monotonic() - _step_t0)
        except httpx.TimeoutException:
            timeouts += 1
            label_t = provider.get("label") or provider.get("model")
            log.warning(
                "agent: таймаут шага %d (%s), попытка %d/%d",
                iterations, provider.get("id"), timeouts,
                _AGENT_TIMEOUT_RETRIES + 1)
            if timeouts <= _AGENT_TIMEOUT_RETRIES:
                await _send_text(
                    job.chat_id,
                    f"{label_t} думает дольше таймаута — повторяю шаг "
                    f"({timeouts}/{_AGENT_TIMEOUT_RETRIES}).")
                continue  # тот же messages, ещё одна попытка
            await _send_text(
                job.chat_id,
                f"Модель {label_t} не ответила за отведённое время "
                f"({_AGENT_TIMEOUT_RETRIES + 1} попыток по "
                f"{int(provider.get('timeout_sec') or 300)} с) — задача "
                "отменена, правки не применялись. Попробуйте другую модель.")
            _agent_sessions.drop(job.user_id)
            return
        except (httpx.HTTPError, OSError) as exc:
            log.warning("agent: провайдер %s: %s",
                        provider.get("id"), _safe_err(exc))
            await _send_text(job.chat_id,
                             "Провайдер модели недоступен — задача отменена, "
                             "правки не применялись. Попробуйте другую модель: "
                             "/agent <задача>.")
            _agent_sessions.drop(job.user_id)
            return

        if not (content or "").strip():
            empty_rounds += 1
            if empty_rounds > _AGENT_MAX_EMPTY:
                break
            messages.append({"role": "assistant", "content": content or ""})
            messages.append({"role": "user", "content":
                             "Ответ пустой. Продолжай работу над задачей "
                             "маркерами."})
            continue
        empty_rounds = 0

        markers = agent_session.parse_markers(content)
        messages.append({"role": "assistant", "content": content})
        if not markers and content.strip():
            # Модель ответила текстом без маркеров — не теряем его.
            last_plain = content.strip()

        reads_done = 0
        for marker in markers:
            kind = marker["kind"]
            if kind == "read":
                rel = project_files.resolve_request(marker["path"]) or marker["path"]
                safe = project_edit.validate_path(rel)
                if safe is None:
                    agent_session.log_step(
                        session, f"отказ на чтение: {marker['path']}")
                    read_failures.append(marker["path"])
                    continue
                text = project_files.read_text(safe)
                if text is None:
                    agent_session.log_step(session, f"не прочитан: {safe}")
                    read_failures.append(str(safe))
                    continue
                original.setdefault(safe, text)
                reads_done += 1
                agent_session.log_step(session, f"прочитан: {safe}")
            elif kind in ("write", "patch"):
                rel = marker["path"]
                safe = project_edit.validate_path(rel)
                if safe is None:
                    agent_session.log_step(
                        session, f"отказ на правку: {rel} (запрещён/вне корня)")
                    continue
                if kind == "write":
                    working[safe] = marker["text"]
                    agent_session.log_step(session, f"правка (write): {safe}")
                else:
                    base = working.get(safe) or original.get(safe)
                    if base is None:
                        text_now = project_files.read_text(safe)
                        if text_now is None:
                            agent_session.log_step(
                                session, f"patch без чтения файла: {safe}")
                            continue
                        original[safe] = text_now
                        base = text_now
                    new_text, applied = project_edit.apply_patch(
                        base, marker["old_text"], marker["new_text"])
                    if not applied:
                        agent_session.log_step(
                            session, f"patch не найден в: {safe}")
                        continue
                    working[safe] = new_text
                    agent_session.log_step(session, f"правка (patch): {safe}")
            elif kind == "shell":
                cmd = marker["cmd"]
                verdict = project_shell.classify(cmd, _shell_allow)
                if verdict == project_shell.ALLOW:
                    result = await asyncio.to_thread(
                        project_shell.run, cmd, project_edit.project_root())
                    shell_runs.append((cmd, result))
                    agent_session.log_step(
                        session,
                        f"shell ({'ok' if result['ok'] else 'код ' + str(result['code'])}): {cmd}")
                elif verdict == project_shell.GATE:
                    session.pending_commands.append(cmd)
                    agent_session.log_step(
                        session, f"shell в гейт (вне белого списка): {cmd}")
                else:
                    shell_denied.append(cmd)
                    agent_session.log_step(session, f"shell отказ: {cmd}")
            elif kind == "report":
                report_text = marker["text"]

        if report_text or iterations >= _AGENT_MAX_ITERATIONS:
            break
        # Продолжение цикла: файлы-результаты чтения и текущий статус правок.
        block = _agent_file_block(
            {p: original[p] for p in original
             if p not in working and len(original) <= 8})
        status = ""
        if working:
            status = ("\n\nПОДГОТОВЛЕННЫЕ ПРАВКИ (файлы):\n"
                      + "\n".join(f"- {p}" for p in working))
        shell_block = ""
        if shell_runs:
            parts = [f"$ {cmd}\n{result['output'] or '(пустой вывод)'}"
                     + ("" if result["ok"] else f"\n[код возврата {result['code']}]")
                     for cmd, result in shell_runs]
            shell_block = ("\n\nРЕЗУЛЬТАТЫ КОМАНД:\n" + "\n\n".join(parts))
        if shell_denied:
            shell_block += ("\n\nОТКЛОНЁННЫЕ КОМАНДЫ (не повторяй): "
                            + "; ".join(shell_denied))
        if read_failures:
            seen: list[str] = []
            for p in read_failures:
                if p not in seen:
                    seen.append(p)
            shell_block += (
                "\n\nЭТИ ФАЙЛЫ НЕ СУЩЕСТВУЮТ ИЛИ НЕДОСТУПНЫ ДЛЯ ЧТЕНИЯ "
                "(НЕ запрашивай их повторно, работай с тем, что уже есть — "
                "для спек openspec используй конвенции из инструкции):\n"
                + "\n".join(f"- {p}" for p in seen))
        read_failures.clear()
        if session.pending_commands:
            shell_block += ("\n\nКоманды вне белого списка ждут подтверждения "
                            "пользователя — не повторяй их, продолжай задачу: "
                            + "; ".join(session.pending_commands))
        shell_runs.clear()
        shell_denied.clear()
        messages.append({"role": "user", "content":
                         (f"Файлов прочитано за шаг: {reads_done}. "
                          "Продолжай работу над задачей. Если правки готовы — "
                          "верни <report>.\n\n" + block + status + shell_block)})

    if working or session.pending_commands:
        # Правки/команды готовы — гейт подтверждения. Исходное содержимое
        # сохраняется в old_text: после записи на диск read_text уже вернёт
        # новый текст, дифф для отчёта считается от old_text.
        session.pending_edits = [
            {"path": path, "kind": "write", "new_text": text,
             "old_text": original.get(path)}
            for path, text in working.items()
        ]
        diffs = []
        for path, text in working.items():
            diffs.append((path, project_edit.compute_diff(
                original.get(path), text, path)))
        agent_session.set_state(session, agent_session.AWAIT_CONFIRM)
        lines = []
        if working:
            lines += ["Подготовлены правки (ещё НЕ применены):"]
            lines += [f"• {path}" for path in working]
        if session.pending_commands:
            lines += ([f"Команды вне белого списка (исполнятся после «применяй», "
                       f"затем агент продолжит работу):"]
                      if working else
                      ["Команды вне белого списка (ещё НЕ исполнены; после "
                       "«применяй» агент продолжит анализ с их выводом):"])
            lines += [f"$ {cmd}" for cmd in session.pending_commands]
        lines += ["", "Ответьте «применяй» — выполнить, или «отмена» — сбросить."]
        await _send_text(job.chat_id, "\n".join(lines))
        for path, diff in diffs:
            if not diff:
                continue
            await _send_html(
                job.chat_id, pr_review.render_diff_html(diff))
    elif report_text:
        label = provider.get("label") or provider.get("model")
        session.last_report = report_text
        agent_session.set_state(session, agent_session.FOLLOWUP)
        await _send_text(
            job.chat_id,
            f"Агент завершил без правок (модель: {label}).\n\n{report_text}\n\n"
            "Дальше: «спека» — оформлю openspec-спеку по отчёту; «делай» — "
            "приступлю к правкам. Другой текст — дополню задачу; "
            "/agent отмена — сброс.", job.reply_to)
    else:
        if last_plain:
            # Модель вела анализ, но без маркеров — принимаю её ответ
            # как отчёт (FOLLOWUP), а не «не смог подготовить правки».
            label = provider.get("label") or provider.get("model")
            if len(last_plain) > 3500:
                last_plain = last_plain[:3500] + "\n… (обрезано)"
            session.last_report = last_plain
            agent_session.set_state(session, agent_session.FOLLOWUP)
            await _send_text(
                job.chat_id,
                f"Агент ответил без служебных маркеров — принимаю ответ как "
                f"отчёт (модель: {label}).\n\n{last_plain}\n\n"
                "Дальше: «спека» — оформлю openspec-спеку; «делай» — "
                "приступлю к правкам; /agent отмена — сброс.", job.reply_to)
        else:
            await _send_text(job.chat_id,
                             "Агент не смог подготовить правки за отведённое "
                             "число шагов — задача отменена, правки не "
                             "применялись. Попробуйте сформулировать задачу "
                             "уже или выбрать другую модель.")
            agent_session.set_state(session, agent_session.IDLE)
            _agent_sessions.drop(job.user_id)


async def _handle_agent_confirm(job: Job) -> None:
    """Гейт подтверждения: «применяй» — запись, «отмена» — сброс."""
    session = _agent_sessions.get(job.user_id)
    if session is None or session.state != agent_session.AWAIT_CONFIRM:
        return
    answer = (job.question or "").strip().lower()
    if answer in _CANCEL_WORDS:
        skipped = len(session.pending_commands)
        _agent_sessions.drop(job.user_id)
        _agent_kb_msg.pop(job.user_id, None)
        extra = (f" Команд не исполнено: {skipped}." if skipped else "")
        await _send_text(job.chat_id, "Отменено — на диск ничего не "
                                      "записано." + extra, job.reply_to)
        return
    if answer not in _CONFIRM_WORDS:
        await _send_text(job.chat_id,
                         "Ответьте «применяй» — записать правки, или "
                         "«отмена» — сбросить.", job.reply_to)
        return

    provider = _agent_model_by_id(session.model_id) or {}
    label = provider.get("label") or provider.get("model") or session.model_id
    applied: list[str] = []
    failures: list[str] = []
    for edit in session.pending_edits:
        result = project_edit.write_file(edit["path"], edit["new_text"])
        if result.get("ok"):
            applied.append(result["path"])
            agent_session.log_step(session, f"записан: {result['path']}")
        else:
            failures.append(f"{edit['path']}: {result.get('error')}")
            agent_session.log_step(
                session, f"ошибка записи {edit['path']}: {result.get('error')}")
    session.applied = applied

    # Команды вне белого списка — исполняются ТОЛЬКО здесь, после явного
    # «применяй» (проект_shell.classify положил их в pending_commands).
    cmd_results: list[tuple[str, dict]] = []
    pending = list(session.pending_commands)
    session.pending_commands = []
    for cmd in pending:
        result = await asyncio.to_thread(
            project_shell.run, cmd, project_edit.project_root())
        cmd_results.append((cmd, result))
        agent_session.log_step(
            session,
            f"shell после гейта ({'ok' if result['ok'] else 'код ' + str(result['code'])}): {cmd}")

    lines = []
    if applied:
        lines.append(f"✅ Записано файлов: {len(applied)} (модель: {label}).")
        lines += [f"• {p}" for p in applied]
    if failures:
        lines.append("⚠️ Не записано:")
        lines += [f"• {f}" for f in failures]
    if cmd_results:
        lines.append("Команды:")
        for cmd, result in cmd_results:
            outcome = "ok" if result["ok"] else f"код {result['code']}"
            lines.append(f"$ {cmd} → {outcome}")
    checks = agent_session.verify_edits(project_edit.project_root(), session)
    bad = [c for c in checks if not c.get("ok")]
    if checks:
        skipped = [c for c in checks if c.get("check") == "skipped"]
        if skipped:
            lines.append(f"Проверка синтаксиса: пропущено файлов — {len(skipped)}.")
        if bad:
            lines.append("⚠️ Проверка синтаксиса нашла ошибки:")
            lines += [f"• {c['path']}: {(c.get('error') or '')[:200]}"
                      for c in bad]
        elif applied:
            lines.append("Проверка синтаксиса: ошибок нет.")
    if not applied and not failures:
        lines.append("Правок для записи не оказалось.")
    await _send_text(job.chat_id, "\n".join(lines), job.reply_to)

    # Команды исполнены, правок не было — это аналитическая задача: вывод
    # команд возвращается агенту на продолжение (не теряется).
    if not applied and not failures and cmd_results:
        session.command_outputs = [
            {"cmd": cmd, "output": result.get("output") or "",
             "code": result.get("code", 0)}
            for cmd, result in cmd_results]
        agent_session.set_state(session, agent_session.EXECUTE)
        await _send_text(job.chat_id,
                         "Агент продолжает работу с результатами команд — "
                         "ждите отчёт (или /agent отмена).")
        await _enqueue(Job(job.chat_id, job.user_id, "agent_run", ""))
        return

    # Диффы применённых правок документами (максимум 5) — от исходника,
    # сохранённого при подготовке правки (не от уже перезаписанного файла).
    for edit in session.pending_edits[:5]:
        diff = project_edit.compute_diff(
            edit.get("old_text"), edit["new_text"], edit["path"])
        if not diff:
            continue
        base = os.path.basename(edit["path"]) or "file"
        try:
            await _send_document(
                job.chat_id, f"{base}.diff", diff,
                caption=f"Изменения: {edit['path']}",
                reply_to=job.reply_to)
        except Exception as exc:  # noqa: BLE001 - сбой вложения не роняет отчёт
            log.warning("agent: дифф %s не отправлен: %s",
                        edit["path"], _safe_err(exc))

    agent_session.set_state(session, agent_session.IDLE)
    _agent_sessions.drop(job.user_id)


# ---------------------------------------------------------------------------
# Обработка одного «хода» бота (worker — последовательная глобальная очередь)
# ---------------------------------------------------------------------------

_jobs: asyncio.Queue = asyncio.Queue()
_user_last: dict[int, float] = {}
_worker_task: asyncio.Task | None = None


class Job:
    __slots__ = ("chat_id", "user_id", "mode", "question", "reply_to", "lm_retries",
                 "documents", "provider_id")

    def __init__(self, chat_id, user_id, mode: str, question: str, reply_to=None,
                 documents: list | None = None):
        self.chat_id = chat_id
        self.user_id = user_id
        self.mode = mode
        self.question = question
        self.reply_to = reply_to
        self.lm_retries = 0
        # (file_id, filename, caption) — для режима filediff
        self.documents = documents
        # id модели из agent.models (выбор через inline-кнопки); None — дефолт
        self.provider_id: str | None = None


async def _enqueue(job: Job) -> None:
    await _jobs.put(job)


# Транспортные ошибки LM Studio (падение сервера, обрыв соединения при
# краше) — сигнал перезагрузки модели, задание повторяется на уровне воркера.
_LM_TRANSPORT = (httpx.ConnectError, httpx.RemoteProtocolError)
_LM_JOB_RETRIES = 2
_LM_JOB_WAIT = 60.0


async def _flush_usage() -> None:
    """Сбрасывает накопленный расход токенов в БД (после каждого задания)."""
    snapshot = dict(_pending_usage)
    if not any(snapshot.values()):
        return
    try:
        await _store.add_usage(
            prompt_tokens=snapshot["prompt_tokens"],
            completion_tokens=snapshot["completion_tokens"],
            total_tokens=snapshot["total_tokens"],
        )
    except Exception as exc:  # noqa: BLE001 - сбой статистики не роняет воркер
        log.warning("bot: статистика токенов не сохранена: %s", _safe_err(exc))
        return
    for key in _pending_usage:
        _pending_usage[key] -= snapshot[key]


async def _worker() -> None:
    while True:
        job = await _jobs.get()
        try:
            try:
                await _handle_job(job)
            except _LM_TRANSPORT as exc:
                if job.lm_retries >= _LM_JOB_RETRIES:
                    raise
                job.lm_retries += 1
                log.warning(
                    "responder: LM недоступна (%s), повтор задания %d/%d через %d c",
                    _safe_err(exc), job.lm_retries, _LM_JOB_RETRIES, int(_LM_JOB_WAIT),
                )
                if job.lm_retries == 1:
                    await _send_text(
                        job.chat_id,
                        "Соединение с сервисом оборвалось — повторю задание "
                        "через минуту.",
                        job.reply_to,
                    )
                await asyncio.sleep(_LM_JOB_WAIT)
                await _jobs.put(job)
        except asyncio.CancelledError:
            raise
        except _LM_TRANSPORT as exc:
            # Ретраи исчерпаны. _LM_TRANSPORT ловит ЛЮБОЙ ConnectError в job
            # (LLM, Telegram API) — не утверждаем, что виновата локальная модель.
            req = getattr(exc, "request", None)
            target = str(getattr(req, "url", "") or "?")
            log.error("responder: job: соединение оборвалось (%s) -> %s",
                      _safe_err(exc), target)
            try:
                await _send_text(
                    job.chat_id,
                    "Соединение с одним из сервисов оборвалось (LLM или "
                    "Telegram), задание не выполнено. Повторите запрос — "
                    "если повторится, проверьте сеть/LLM-провайдера.",
                    job.reply_to)
            except Exception:  # noqa: BLE001
                pass
        except Exception as exc:
            log.error("responder: job: %s", _safe_err(exc))
            try:
                await _send_text(job.chat_id, "Что-то пошло не так — попробуйте ещё раз.")
            except Exception:  # noqa: BLE001
                pass
        finally:
            try:
                await _flush_usage()
            except Exception:  # noqa: BLE001 - статистика не должна ронять воркер
                pass
            _jobs.task_done()


async def _interim_watchdog(chat_id, done: asyncio.Event) -> None:
    try:
        await asyncio.wait_for(done.wait(), timeout=INTERIM_SEC)
    except asyncio.TimeoutError:
        try:
            await _send_text(chat_id, "Думаю…")
        except Exception:
            pass


async def _handle_job(job: Job) -> None:
    if job.mode == "start":
        await _send_text(job.chat_id, _WELCOME, job.reply_to)
        return
    if job.mode == "help":
        await _send_text(job.chat_id, _HELP, job.reply_to)
        return
    if job.mode == "admin":
        await _handle_admin(job)
        return
    if job.mode == "deps":
        await _handle_deps(job)
        return
    if job.mode == "filediff":
        await _handle_filediff(job)
        return
    if job.mode == "create":
        await _handle_create(job)
        return
    if job.mode == "create_check":
        await _handle_create_check(job)
        return
    if job.mode == "agent":
        await _handle_agent(job)
        return
    if job.mode == "agent_check":
        await _handle_agent_check(job)
        return
    if job.mode == "agent_run":
        await _handle_agent_run(job)
        return
    if job.mode == "agent_confirm":
        await _handle_agent_confirm(job)
        return
    if job.mode == "agent_note":
        await _handle_agent_note(job)
        return
    if job.mode == "agent_followup":
        await _handle_agent_followup(job)
        return
    if job.mode == "agent_busy":
        await _send_text(job.chat_id, _AGENT_BUSY_MSG, job.reply_to)
        return
    if job.mode == "ask" and not job.question:
        await _send_text(
            job.chat_id,
            "Укажите вопрос после команды. Например: /ask как подключить Android Auto",
            job.reply_to,
        )
        return

    # rate-limit по пользователю (job с выбранной моделью — повтор уже
    # принятого запроса после нажатия кнопки, повторно не лимитируется)
    if job.provider_id is None:
        now = time.monotonic()
        prev = _user_last.get(job.user_id, 0.0)
        if now - prev < COOLDOWN_SEC:
            await _send_text(job.chat_id, COOLDOWN_MSG, job.reply_to)
            return
        _user_last[job.user_id] = now

    await _send_chat_action(job.chat_id, "typing")
    done = asyncio.Event()
    watchdog = asyncio.create_task(_interim_watchdog(job.chat_id, done))
    try:
        if job.mode == "ask":
            await _handle_ask(job)
        else:
            await _handle_plain(job)
    finally:
        done.set()
        watchdog.cancel()


async def _handle_plain(job: Job, provider: dict | None = None) -> None:
    try:
        # PR-контекст: список открытых PR от github-наблюдателя (может быть '')
        system = PLAIN_SYSTEM + github_watcher.context_block()
        content, _ = await _generate(
            system, job.question, _history_messages(job.user_id), provider
        )
    except httpx.TimeoutException:
        await _send_text(job.chat_id, GEN_TIMEOUT_MSG, job.reply_to)
        return
    except _LM_TRANSPORT:
        raise
    except (httpx.HTTPError, OSError):
        await _send_text(job.chat_id, LM_DOWN_MSG, job.reply_to)
        return
    if not content:
        await _send_text(job.chat_id, IDK_MSG, job.reply_to)
        return
    _remember_turn(job.user_id, job.question, content.rstrip())
    await _send_text(job.chat_id, content, job.reply_to)


def _deps_module_caption(stats: dict) -> str:
    """Подпись к модульной карте зависимостей (без LLM, ≤1024 симв.)."""
    fo = ", ".join(f"{m} ({n})" for m, n in stats["top_fanout"])
    fi = ", ".join(f"{m} ({n})" for m, n in stats["top_fanin"])
    return (
        f"Карта зависимостей модулей (Kotlin, резолвленные вызовы)\n"
        f"Модулей: {stats['modules']}, пар зависимостей: {stats['dep_pairs']}, "
        f"вызовов учтено: {stats['resolved_calls']}\n"
        f"Макс. исходящих: {fo}\nМакс. входящих: {fi}"
    )


def _deps_callers_caption(stats: dict) -> str:
    """Подпись к графу вызывающих символа."""
    prod = [c for c in stats["callers"] if not c["test"]]
    tests = [c for c in stats["callers"] if c["test"]]
    lines = [f"Вызовы {stats['symbol']}"]

    def _item(c: dict) -> str:
        who = f"{c['container']}.{c['name']}" if c["container"] else c["name"]
        return f"{who} — {c['file'].rsplit('/', 1)[-1]}:{c['line']}"

    if prod:
        lines.append("Прод:")
        lines += [f"• {_item(c)}" for c in prod[:8]]
    if tests:
        files = sorted({c["file"].rsplit("/", 1)[-1] for c in tests})
        lines.append(f"Тесты: {len(tests)} вызовов ({', '.join(files)})")
    if not prod and not tests:
        lines.append("Резолвленных вызовов не найдено (см. trace_dependencies через MCP).")
    return "\n".join(lines)


async def _handle_deps(job: Job) -> None:
    """Карта зависимостей проекта из codegraph-индекса. LLM не вызывается.

    ``/deps``            — модульная карта Kotlin-бэкенда (PNG);
    ``/deps <символ>``   — кто вызывает символ (PNG + список).
    """
    symbol = job.question.strip().strip("`\"' ")
    try:
        if symbol:
            stats, dot = deps_map.callers_graph(deps_map.codegraph_db(), symbol)
            caption = _deps_callers_caption(stats)
        else:
            stats, dot = deps_map.module_graph(deps_map.codegraph_db())
            caption = _deps_module_caption(stats)
    except LookupError as exc:
        # /deps X: аргумент может быть дизайн-термином или TS-компонентом,
        # а не символом codegraph
        term = deps_map.find_design_term(deps_map.codegraph_db(), symbol)
        if term:
            await _send_text(job.chat_id, deps_map.design_term_note(term), job.reply_to)
            return
        ts_name = deps_map.find_ts_component(deps_map.codegraph_db(), symbol)
        if ts_name:
            imported = deps_map.ts_import_graph(deps_map.codegraph_db(), ts_name)
            if imported is not None:
                stats, dot = imported
                await _send_photo(
                    job.chat_id,
                    deps_map.dot_to_bytes(dot) or b"",
                    _deps_imports_caption(stats),
                    job.reply_to,
                )
                return
        mod_pkg = deps_map.find_module(deps_map.codegraph_db(), symbol)
        if mod_pkg:
            stats, dot = deps_map.module_subgraph(deps_map.codegraph_db(), mod_pkg)
            await _send_photo(
                job.chat_id,
                deps_map.dot_to_bytes(dot) or b"",
                _deps_module_sub_caption(stats),
                job.reply_to,
            )
            return
        await _send_text(job.chat_id, f"🧭 {exc}", job.reply_to)
        return
    except sqlite3.Error as exc:
        log.warning("deps: база codegraph недоступна: %s", exc)
        await _send_text(
            job.chat_id,
            "Индекс codegraph недоступен. Пересоберите его: "
            "`codegraph index <путь проекта>`.",
            job.reply_to,
        )
        return
    png = deps_map.dot_to_bytes(dot)
    if png is None:
        await _send_text(
            job.chat_id,
            caption + "\n\n(graphviz не найден на хосте — карта текстом)",
            job.reply_to,
        )
        return
    await _send_photo(job.chat_id, png, caption, job.reply_to)


def _deps_imports_caption(stats: dict) -> str:
    """Подпись к графу импортов TS-модуля."""
    prod = [i for i in stats["importers"] if not i["test"]]
    tests = [i for i in stats["importers"] if i["test"]]
    lines = [
        f"Импорты {stats['symbol']} ({stats['file']}) — граф по import-рёбрам TS.",
    ]
    if prod:
        lines.append("Прод:")
        lines += [f"• {i['file']}:{i['line']}" for i in prod[:10]]
    if tests:
        lines.append(f"Тесты: {len(tests)} файл(ов)")
    if not prod and not tests:
        lines.append("Импортов не найдено (модуль никуда не подключён?).")
    return "\n".join(lines)


def _deps_module_sub_caption(stats: dict) -> str:
    """Подпись к точечному подграфу модуля."""
    out = ", ".join(stats["outgoing"]) or "нет"
    inn = ", ".join(stats["incoming"]) or "нет"
    return (
        f"Зависимости модуля {stats['module']} (Kotlin, резолвленные вызовы)\n"
        f"Исходящие ({len(stats['outgoing'])}): {out}\n"
        f"Входящие ({len(stats['incoming'])}): {inn}"
    )


_DEPS_INTENT_RE = re.compile(
    r"карт\w*\s+(?:зависим|вызов)"       # карта/карту/картинку зависимостей
    r"|граф\w*\s+(?:зависим|вызов)"      # граф зависимостей/вызовов
    r"|схему\s+зависим"
    r"|кто\s+вызывает"
    r"|стро\w*\s+зависим",
    re.I,
)


async def _send_deps_intent(job: Job, symbol: str | None) -> bool:
    """Картинка/справка зависимостей для интента из свободной фразы.

    True — запрос закрыт без LLM (символ найден → граф вызывающих;
    дизайн-термин → текстовая справка); False — отправлена модульная
    карта, вопрос продолжается через RAG.
    """
    try:
        if symbol:
            stats, dot = deps_map.callers_graph(deps_map.codegraph_db(), symbol)
            caption = _deps_callers_caption(stats)
            await _send_photo(job.chat_id, deps_map.dot_to_bytes(dot) or b"", caption, job.reply_to)
            return True
        # Символа нет: дизайн-термин (токен/правило DESIGN.md) — справка
        # закрывает вопрос целиком (return True), RAG не вызывается: корпус
        # — только *.md, все применения токена уже в справке.
        term = deps_map.find_design_term(deps_map.codegraph_db(), job.question)
        if term:
            await _send_text(job.chat_id, deps_map.design_term_note(term), job.reply_to)
            return True
        # Дефолтные экспорты (`export default function X`) codegraph в symbols
        # не кладёт — для фронтенд-компонента строим граф импортов по файлу.
        ts_name = deps_map.find_ts_component(deps_map.codegraph_db(), job.question)
        if ts_name:
            imported = deps_map.ts_import_graph(deps_map.codegraph_db(), ts_name)
            if imported is not None:
                stats, dot = imported
                await _send_photo(
                    job.chat_id,
                    deps_map.dot_to_bytes(dot) or b"",
                    _deps_imports_caption(stats),
                    job.reply_to,
                )
                return True
        # Имя Kotlin-модуля («routes», «memory») — подграф его соседей.
        mod_pkg = deps_map.find_module(deps_map.codegraph_db(), job.question)
        if mod_pkg:
            stats, dot = deps_map.module_subgraph(deps_map.codegraph_db(), mod_pkg)
            await _send_photo(
                job.chat_id,
                deps_map.dot_to_bytes(dot) or b"",
                _deps_module_sub_caption(stats),
                job.reply_to,
            )
            return True
        stats, dot = deps_map.module_graph(deps_map.codegraph_db())
        png = deps_map.dot_to_bytes(dot)
        if png:
            caption = (
                "Символ во фразе не опознан — отправляю карту всех модулей. "
                "Уточните имя функции/класса для точечного графа.\n\n"
                + _deps_module_caption(stats)
            )
            await _send_photo(job.chat_id, png, caption, job.reply_to)
    except (LookupError, sqlite3.Error) as exc:
        log.warning("deps-intent: %s", exc)
    return False


async def _download_document(file_id: str) -> bytes | None:
    """Содержимое присланного документа (getFile + file endpoint)."""
    info = await _api("getFile", {"file_id": file_id})
    file_path = (info or {}).get("file_path")
    if not file_path:
        return None
    resp = await _get_bot_client().get(
        f"{_api_base().rstrip('/')}/file/bot{_bot_token()}/{file_path}"
    )
    resp.raise_for_status()
    return resp.content


async def _handle_filediff(job: Job) -> None:
    """Присланные файлы -> дифф против ветки main GitHub + вердикт LLM.

    Диффы считает file_diff (difflib + GitHub API); LLM пишет только вердикт.
    Ответ уходит в тот же чат, откуда пришли файлы.
    """
    docs = job.documents or []
    if not docs:
        return
    # Выбор модели: вердикт пишет LLM — на паузу до нажатия кнопки
    # (без каталога agent.models — как раньше).
    if job.provider_id is None and _agent_models:
        await _stash_llm_choice(job)
        return
    provider = _job_provider(job)
    gh = github_watcher.load_settings()
    if not gh.token:
        await _send_text(job.chat_id, "Сравнение с GitHub недоступно: нет токена "
                                      "(секция github в config.json).", job.reply_to)
        return
    repo = (gh.repos or ["AugustoZ/ai-challenge-9"])[0]
    ref = _env_str("FILEDIFF_REF") or file_diff.DEFAULT_REF
    files: list[dict] = []
    for file_id, name, caption in docs:
        try:
            raw = await _download_document(file_id)
        except Exception as exc:  # noqa: BLE001 - сбой одного файла не роняет пакет
            log.warning("filediff: файл %s не скачан: %s", name, _safe_err(exc))
            raw = None
        if raw is None:
            await _send_text(job.chat_id, f"📄 {name} — не удалось скачать файл "
                                          "из Telegram, пропускаю.", job.reply_to)
            continue
        files.append({"name": name, "raw": raw, "caption": caption})
    if not files:
        return
    await _send_chat_action(job.chat_id, "typing")
    try:
        async def _gen(system: str, question: str, history: list[dict] | None):
            return await _generate(system, question, history, provider)

        await file_diff.handle_documents(
            files, chat=job.chat_id, repo=repo, ref=ref, token=gh.token,
            send_text=_send_text, send_html=_send_html, llm_generate=_gen,
            model_name=(provider.get("model") if provider else None) or _lm_model(),
        )
    except httpx.TimeoutException:
        await _send_text(job.chat_id, GEN_TIMEOUT_MSG, job.reply_to)
    except (httpx.HTTPError, OSError) as exc:
        log.warning("filediff: %s", _safe_err(exc))
        await _send_text(job.chat_id, "Не удалось сравнить с GitHub — сервис "
                                      "недоступен. Попробуйте позже.", job.reply_to)


# ---------------------------------------------------------------------------
# /create: GitHub-issue из контекста личного чата
# ---------------------------------------------------------------------------

async def _llm_is_create_intent(text: str) -> bool:
    """Локальная LLM решает, просит ли пользователь создать issue.

    Сбой транспорта/таймаут = «НЕТ»: классификация не должна блокировать
    обычный ответ.
    """
    try:
        content, _ = await _post_chat(
            [
                {"role": "system", "content": _CREATE_CHECK_SYSTEM},
                {"role": "user", "content": (text or "")[:500]},
            ],
            max_tokens=8,
        )
    except (httpx.TimeoutException, httpx.HTTPError, OSError):
        log.warning("create-check: классификатор недоступен — считаю «НЕТ»")
        return False
    return bool(re.search(r"\bда\b", (content or "").strip().lower()))


_AGENT_CHECK_SYSTEM = (
    "Ты маршрутизатор Telegram-бота. Определи, ЯВЛЯЕТСЯ ЛИ сообщение вопросом "
    "по документации или устройству проекта (как что устроено, почему "
    "работает/не работает, где что найти, пояснение по коду/документации). "
    "Если ДА — ответь «ДА». Вопросы вида «что потребуется для доработки X», "
    "«как доработать/реализовать X», «какие изменения нужны» — это НЕ вопрос "
    "по документации, а задача на анализ кода: ответь «НЕТ». Всё остальное "
    "(просьба что-то сделать, создать, изменить, исправить, запустить) — "
    "ответь «НЕТ». Ответ — одно слово: ДА или НЕТ.\n/no_think"
)


async def _llm_is_project_doc_question(text: str) -> bool:
    """Локальная LLM решает: это вопрос по документации/проекту?

    ДА → RAG-ответ, НЕТ → агентная сессия (агент по умолчанию).
    Сбой транспорта/таймаут/пустой ответ (reasoning-модели съедают бюджет
    max_tokens=8) = «ДА»: безопасный фолбэк — прежнее поведение (RAG).
    """
    try:
        content, _ = await _post_chat(
            [
                {"role": "system", "content": _AGENT_CHECK_SYSTEM},
                {"role": "user", "content": (text or "")[:500]},
            ],
            max_tokens=8,
        )
    except Exception:  # noqa: BLE001 - любой сбой = прежнее поведение (RAG)
        log.warning("agent-check: классификатор недоступен — считаю «ДА» (RAG)")
        return True
    answer = (content or "").strip().lower()
    if not answer:
        return True
    return bool(re.search(r"\bда\b", answer))


async def _handle_agent_check(job: Job) -> None:
    """Агент по умолчанию: вопрос по документации → RAG, иначе — агент.

    Без-LLM интенты (deps/файл) проверяются ДО классификатора — они
    дешевле классификации и закрывают запрос сами (внутри _handle_ask).
    """
    if not _agent_models:
        await _handle_ask(job)
        return
    question = job.question or ""
    # Порядок по договорённости: без-LLM интенты (deps/файл/issues) первыми —
    # «распиши карту зависимостей» не должен попадать в агента.
    if (_DEPS_INTENT_RE.search(question) or _FILE_INTENT_RE.search(question)
            or _is_issues_intent(question)):
        await _handle_ask(job)
        return
    if _IMPROVE_INTENT_RE.search(question):
        # «Что потребуется для доработки X» — сначала RAG; промах документации
        # автоматически передаётся агенту (agent_fallback в _handle_ask).
        await _handle_ask(job, agent_fallback=True)
        return
    if await _llm_is_project_doc_question(question):
        await _handle_ask(job)
        return
    # Императив — запуск агентной сессии (клавиатура модели → цикл → гейт).
    await _handle_agent(job)


async def _handle_create_check(job: Job) -> None:
    """Медленный путь интента «создай …»: LLM ДА → черновик issue, НЕТ → RAG."""
    text = job.question or ""
    if await _llm_is_create_intent(text):
        m = _CREATE_VERB_RE.match(text)
        arg = ((m.group("rest") if m else "") or "").strip() or text
        draft = Job(chat_id=job.chat_id, user_id=job.user_id, mode="create",
                    question=arg, reply_to=job.reply_to)
        await _handle_create(draft)
        return
    await _handle_ask(job)


async def _handle_create(job: Job) -> None:
    """Черновик issue: /create <описание> → файлы → /create (финализация).

    Документы при активном черновике перехватываются в _queue_document
    (issue_create.attach_doc), сюда попадает только текст команды.
    """
    arg = (job.question or "").strip()
    if arg.lower() in issue_create.CANCEL_WORDS:
        if issue_create.cancel(job.chat_id):
            await _send_text(job.chat_id, "Черновик issue отменён.", job.reply_to)
        else:
            await _send_text(job.chat_id, "Активного черновика нет.\n\n"
                                          + issue_create.USAGE_MSG, job.reply_to)
        return

    if arg:
        existed = issue_create.pending_active(job.chat_id)
        issue_create.set_text(job.chat_id, arg)
        n = issue_create.docs_count(job.chat_id)
        files_note = f"\nФайлов приложено: {n}." if n else ""
        # «Ум»: по описанию ищем в проекте упомянутые функции/модули —
        # найденные файлы прикладываются к issue при финализации.
        found = issue_locate.locate_files(arg)
        issue_create.set_auto_files(job.chat_id, found)
        note = issue_locate.auto_note(found)
        if existed:
            await _send_text(job.chat_id,
                             "Описание черновика обновлено." + files_note + note +
                             "\nПришлите файлы (до 5) или отправьте /create "
                             "для создания issue.", job.reply_to)
        else:
            await _send_text(job.chat_id,
                             "📝 Черновик issue принят." + files_note + note +
                             "\nПришлите файлы (до 5) — они будут приложены "
                             "к issue. Затем отправьте /create для создания "
                             "(или /create отмена).", job.reply_to)
        return

    # Финализация: /create без аргументов.
    gh = github_watcher.load_settings()
    if not gh.token:
        await _send_text(job.chat_id, "Создание issue недоступно: нет токена "
                                      "(секция github в config.json).", job.reply_to)
        return
    if not gh.repos:
        await _send_text(job.chat_id, "Создание issue недоступно: не задан "
                                      "репозиторий (секция github в config.json).",
                         job.reply_to)
        return
    # Выбор модели: финализация зовёт LLM — на паузу до нажатия кнопки
    # (без каталога agent.models — как раньше).
    if job.provider_id is None and _agent_models:
        await _stash_llm_choice(job)
        return
    provider = _job_provider(job)
    # Авто-пути читаются ДО take_draft — take_draft удаляет черновик.
    auto_paths = issue_create.auto_files(job.chat_id)
    draft = issue_create.take_draft(job.chat_id)
    if draft is None or not (draft.get("text") or "").strip():
        await _send_text(job.chat_id, "Черновик пуст. Сначала отправьте "
                                      "/create <описание>.\n\n"
                                      + issue_create.USAGE_MSG, job.reply_to)
        return
    repo = gh.repos[0]

    files: list[dict] = []
    for file_id, name, caption in draft["docs"]:
        try:
            raw = await _download_document(file_id)
        except Exception as exc:  # noqa: BLE001 - сбой одного файла не роняет issue
            log.warning("create: файл %s не скачан: %s", name, _safe_err(exc))
            raw = None
        if raw is None:
            await _send_text(job.chat_id, f"📄 {name} — не удалось скачать файл "
                                          "из Telegram, в issue не приложен.",
                             job.reply_to)
            continue
        files.append({"name": name, "raw": raw})

    # Автоприложка: файлы проекта, найденные по описанию. Пользовательские
    # файлы в приоритете — авто-файлы занимают оставшиеся слоты до MAX_FILES.
    auto_attached: list[str] = []
    for rel in auto_paths:
        if len(files) >= issue_create.MAX_FILES:
            break
        text = project_files.read_text(rel)
        if text is None:
            continue
        files.append({"name": rel, "raw": text.encode("utf-8")})
        auto_attached.append(rel)

    await _send_chat_action(job.chat_id, "typing")
    try:
        async def _gen(system: str, question: str, history: list[dict] | None):
            return await _generate(system, question, history, provider)

        result = await issue_create.create_from_draft(
            draft["text"], files, repo=repo, token=gh.token,
            llm_generate=_gen,
        )
    except httpx.TimeoutException:
        await _send_text(job.chat_id, GEN_TIMEOUT_MSG, job.reply_to)
        return
    except (httpx.HTTPError, OSError) as exc:
        log.warning("create: %s", _safe_err(exc))
        await _send_text(job.chat_id, "Не удалось создать issue — GitHub "
                                      "недоступен или отклонил запрос "
                                      f"({repo}). Попробуйте позже.", job.reply_to)
        return

    lines = [f"✅ Issue создан: {result['url']}",
             f"Заголовок: {result['title']}"]
    if result.get("fallback"):
        lines.append("⚠️ LLM не вернула оформление — использован исходный текст.")
    if draft.get("dropped"):
        lines.append(f"⚠️ Файлов сверх лимита {issue_create.MAX_FILES} "
                     f"пропущено: {draft['dropped']}.")
    if auto_attached:
        lines.append("🔎 Приложены файлы проекта по описанию:\n"
                     + "\n".join(f"  • {p}" for p in auto_attached))
    report = "\n".join(lines)
    # Запоминаем созданный issue в истории сессии: следующий вопрос
    # («почему … с сохранением данных?») должен видеть контекст.
    _remember_turn(job.user_id,
                   f"/create {draft['text']}".strip(),
                   report[:_HISTORY_ANSWER_CHARS])
    await _send_text(job.chat_id, report, job.reply_to)


# ---------------------------------------------------------------------------
# Пакетирование присланных документов
# ---------------------------------------------------------------------------

# Telegram присылает несколько файлов отдельными сообщениями подряд; чтобы
# увидеть пакет целиком (и отказать при >5), копим документы chat-а в окно
# _DOC_BATCH_WAIT секунд после последнего файла.
_DOC_BATCH_WAIT = 2.0
_doc_batches: dict[int, dict] = {}


async def _flush_doc_batch(chat_id) -> None:
    try:
        await asyncio.sleep(_DOC_BATCH_WAIT)
    except asyncio.CancelledError:
        return  # пришёл ещё один файл — окно перезапущено
    batch = _doc_batches.pop(chat_id, None)
    if not batch:
        return
    docs = batch["docs"]
    if len(docs) > file_diff.MAX_FILES:
        await _send_text(chat_id, file_diff.TOO_MANY_MSG)
        return
    await _enqueue(Job(batch["chat_id"], batch["user_id"], "filediff", "",
                       documents=docs))


def _queue_document(msg: dict, chat_id, user_id) -> bool:
    """Документ из сообщения в батч; True — сообщение закрыто этим путём.

    При активном черновике /create документ уходит в issue
    (issue_create.attach_doc), иначе — в filediff-батч.
    Подпись к документу проверяется на команду /create: «/create <описание> +
    файлы одним сообщением» стартует черновик и сразу прикладывает файл
    (обычный роутинг подписи не видит — документы закрывают сообщение).
    """
    doc = msg.get("document")
    if not isinstance(doc, dict) or not doc.get("file_id"):
        return False
    name = str(doc.get("file_name") or "файл")
    caption = str(msg.get("caption") or "").strip()
    # /create в подписи: приватный чат, команда /create → черновик + файл.
    cap_cmd = _CMD_RE.match(caption) if caption else None
    if (cap_cmd is not None and cap_cmd.group(1).lower() == "create"
            and str((msg.get("chat") or {}).get("type")) == "private"):
        arg = cap_cmd.group(2).strip()
        if arg.lower() in issue_create.CANCEL_WORDS:
            asyncio.create_task(_enqueue(Job(chat_id, user_id, "create", arg,
                                             msg.get("message_id"))))
            return True
        if arg:
            # Черновик должен существовать до attach_doc; повторный set_text
            # в _handle_create с тем же текстом безвреден.
            issue_create.set_text(chat_id, arg)
        if arg or issue_create.pending_active(chat_id):
            issue_create.attach_doc(chat_id, doc["file_id"], name, "")
        asyncio.create_task(_enqueue(Job(chat_id, user_id, "create", arg,
                                         msg.get("message_id"))))
        return True
    if issue_create.attach_doc(chat_id, doc["file_id"], name, caption):
        return True
    batch = _doc_batches.get(chat_id)
    if batch is None:
        batch = _doc_batches[chat_id] = {
            "docs": [], "task": None, "chat_id": chat_id, "user_id": user_id,
        }
    batch["docs"].append((doc["file_id"], name, caption))
    if batch["task"] is not None:
        batch["task"].cancel()
    batch["task"] = asyncio.create_task(_flush_doc_batch(chat_id))
    return True


async def _handle_ask(job: Job, agent_fallback: bool = False) -> None:
    """RAG-ответ; agent_fallback — при промахе документации («нет данных»)
    автоматически запустить агента по исходному вопросу."""
    contacts_intent = _CONTACTS_INTENT_RE.search(job.question) is not None
    # Интент «карта зависимостей / кто вызывает X»: строим PNG из codegraph
    # до RAG-пайплайна. Символ найден → граф вызывающих заменяет LLM-ответ
    # (файл:строка уже в подписи, экономим токены); не найден → шлём
    # модульную карту и продолжаем обычный ответ по документации.
    if _DEPS_INTENT_RE.search(job.question):
        symbol = deps_map.extract_symbol(deps_map.codegraph_db(), job.question)
        handled = await _send_deps_intent(job, symbol)
        if handled:
            return
    # Прямой интент «покажи файл <имя>»: файл из дерева проекта без LLM.
    # Не нашли/имени нет — вопрос продолжается обычным RAG-ответом.
    file_intent = _FILE_INTENT_RE.search(job.question)
    if file_intent and await _send_file_intent(job, file_intent.group(1)):
        return
    # Интент «какие задачи созданы»: список свежих issue GitHub без LLM.
    # GitHub недоступен → вопрос продолжается (RAG/диагностика) как раньше.
    if _is_issues_intent(job.question) and await _handle_issues_list(job):
        return
    # Выбор модели: далее идут только LLM-пути (диагностика/RAG/plain) —
    # действие на паузу до нажатия кнопки (без каталога agent.models — как раньше).
    if job.provider_id is None and _agent_models:
        await _stash_llm_choice(job)
        return
    provider = _job_provider(job)
    # Интент «почему не работает X»: issue на GitHub → RAG-вердикт.
    # Запрос целиком закрывается этим путём (включая фолбэки).
    if _DIAGNOSE_INTENT_RE.search(job.question):
        await _handle_diagnose(job, provider)
        return
    wants_file = _WANTS_FILE_RE.search(job.question) is not None
    try:
        # Контактовому интенту реранк не нужен: первоисточник инжектится
        # целиком — экономим ~2.7 с на кросс-энкодере.
        chunks = await _rag_search(job.question, rerank=not contacts_intent)
    except (httpx.TimeoutException, httpx.HTTPError, OSError, ValueError):
        # sidecar недоступен: не роняем вопрос, отвечаем доменным plain-фолбэком
        log.warning("RAG недоступен (%s) — фолбэк на plain", _rag_search_url())
        await _handle_plain(job, provider)
        return

    if not chunks:
        # база пуста/ничего не нашлось: лучше доменный ответ, чем отказ сервиса
        await _handle_plain(job, provider)
        return

    contacts_block = ""
    contacts_doc = _contacts_doc_for(job.question)
    if contacts_intent and contacts_doc in _catalog_paths():
        contacts_block = _load_source_docs([contacts_doc])
    stable_extra = contacts_block + _CONTACTS_DOC_HINT if contacts_block else ""
    sys_block = _build_rag_block(chunks, stable_extra, wants_file=wants_file)
    history = _history_messages(job.user_id)
    try:
        content, _ = await _generate(sys_block, job.question, history, provider)
    except httpx.TimeoutException:
        await _send_text(job.chat_id, GEN_TIMEOUT_MSG, job.reply_to)
        return
    except _LM_TRANSPORT:
        raise
    except (httpx.HTTPError, OSError):
        await _send_text(job.chat_id, LM_DOWN_MSG, job.reply_to)
        return

    if not content:
        await _send_text(job.chat_id, IDK_MSG, job.reply_to)
        return

    # Двухфазный ответ: модель может запросить документ из каталога маркером
    # <doc>путь</doc> — подтягиваем первоисточник из зеркала вики и перегенерируем.
    first, doc_reqs = _take_doc_requests(content, _catalog_paths())
    if doc_reqs:
        doc_block = _load_source_docs(doc_reqs)
        if doc_block:
            if any(r.startswith("ru/contacts") for r in doc_reqs) and not contacts_block:
                contacts_block = doc_block  # добор сработает и для <doc>-пути
            try:
                content, _ = await _generate(sys_block + doc_block, job.question, history)
                content, _ = _take_doc_requests(content, _catalog_paths())
            except (httpx.TimeoutException, httpx.HTTPError, OSError):
                log.warning("перегенерация с первоисточником не удалась — отвечаю без него")
            else:
                if not content:
                    content = first
        else:
            content = first
    else:
        content = first

    if contacts_block and content:
        supplement = _contacts_supplement(job.question, content, contacts_block)
        if supplement:
            content = content.rstrip() + "\n" + supplement

    # Двухфазная выдача файла: модель назвала <file>путь</file> — вырезаем
    # маркеры, читаем файл из дерева проекта и присылаем документом.
    content, file_reqs = _take_file_requests(content)
    notes: list[str] = []
    for req in file_reqs:
        rel = project_files.resolve_request(req)
        if rel and await _send_project_file(job.chat_id, rel, job.reply_to):
            continue
        notes.append(_FILE_NOT_FOUND_NOTE.format(req=req))
    if notes:
        content = content.rstrip() + "\n\n" + "\n".join(notes)

    # agent_fallback (improve-вопрос): RAG-ответ — только справка; вопрос
    # по определению задача анализа кода, агент запускается ВСЕГДА
    # (регекс-детектор промаха ненадёжен: RAG отвечает содержательно,
    # но не по теме запроса). Без каталога моделей — ручная подсказка.
    if agent_fallback and _agent_models:
        content = (content.rstrip()
                   + "\n\nЭто задача для разбора по коду — передаю агенту "
                     "(справка из документации выше).")
        _remember_turn(job.user_id, job.question, content.rstrip())
        await _send_text(job.chat_id, content.rstrip(), job.reply_to)
        await _handle_agent(job)
        return
    if re.search(
        r"нет данных|в приведённых документах|не упоминается|не описан",
        content[:400],
        re.I,
    ):
        content = (content.rstrip()
                   + "\n\nЕсли нужен разбор по коду — напишите "
                     "/agent <задача>, проанализирую файлы проекта и скажу, "
                     "что и как дорабатывать.")

    _remember_turn(job.user_id, job.question, content.rstrip())
    await _send_text(job.chat_id, content.rstrip(), job.reply_to)


async def _handle_issues_list(job: Job) -> bool:
    """Интент «какие задачи созданы»: свежие issue GitHub без LLM.

    True — вопрос закрыт этим путём; False — GitHub недоступен/не настроен,
    вопрос продолжается обычным RAG-ответом.
    """
    gh = github_watcher.load_settings()
    if not (gh.token and gh.repos):
        return False
    try:
        raw = await diagnose.recent_issues(gh.token, gh.repos)
    except (httpx.TimeoutException, httpx.HTTPError, OSError, ValueError) as exc:
        log.warning("issues-list: GitHub недоступен (%s) — RAG-фолбэк",
                    _safe_err(exc))
        return False
    if not raw:
        message = "Созданных issue в репозиториях пока нет."
    else:
        message = diagnose.format_issues(raw[:diagnose.LIST_ISSUES],
                                         header=diagnose.LIST_HEADER)
    _remember_turn(job.user_id, job.question, message)
    await _send_text(job.chat_id, message, job.reply_to)
    return True


async def _handle_diagnose(job: Job, provider: dict | None = None) -> None:
    """Интент «почему не работает X» (diagnose.py).

    1) GitHub по теме: сначала свежие issue REST-списком (без лага
       индексации Search API и с локальным стем-матчем под русскую
       морфологию), затем добор через Search API. Найдены релевантные
       issue → список «#N «название» (состояние) — ссылка», вопрос закрыт.
    2) Иначе — RAG по вопросу + вердикт LLM («Вердикт / Проверить»);
       модель может запросить локальный файл маркером <file> — содержимое
       уходит В промпт (project_files), вердикт перегенерируется.
    Все сбои (GitHub, RAG) деградируют в фолбэк, запрос не умирает.
    """
    gh = github_watcher.load_settings()
    topic = diagnose.extract_topic(job.question)
    raw: list[dict] = []
    if gh.token and gh.repos and topic:
        try:
            raw = await diagnose.recent_issues(gh.token, gh.repos)
        except (httpx.TimeoutException, httpx.HTTPError, OSError, ValueError) as exc:
            log.warning("diagnose: свежие issue недоступны (%s)", _safe_err(exc))
        try:
            raw += await diagnose.search_issues(gh.token, gh.repos, topic)
        except (httpx.TimeoutException, httpx.HTTPError, OSError, ValueError) as exc:
            log.warning("diagnose: поиск issue недоступен (%s) — RAG-фолбэк",
                        _safe_err(exc))
        found = diagnose.pick_issues(raw, job.question)
    else:
        found = []
    if found:
        message = diagnose.format_issues(found)
        _remember_turn(job.user_id, job.question, message)
        await _send_text(job.chat_id, message, job.reply_to)
        return

    # Issue нет (или GitHub недоступен) — RAG по теме + вердикт LLM.
    try:
        chunks = await _rag_search(job.question)
    except (httpx.TimeoutException, httpx.HTTPError, OSError, ValueError):
        log.warning("diagnose: RAG недоступен (%s) — фолбэк на plain",
                    _rag_search_url())
        await _handle_plain(job, provider)
        return
    if not chunks:
        await _handle_plain(job, provider)
        return
    sys_block = _build_rag_block(chunks, wants_file=True,
                                 system=diagnose.DIAGNOSE_SYSTEM)
    history = _history_messages(job.user_id)
    try:
        content, _ = await _generate(sys_block, job.question, history, provider)
    except httpx.TimeoutException:
        await _send_text(job.chat_id, GEN_TIMEOUT_MSG, job.reply_to)
        return
    except _LM_TRANSPORT:
        raise
    except (httpx.HTTPError, OSError):
        await _send_text(job.chat_id, LM_DOWN_MSG, job.reply_to)
        return
    if not content:
        await _send_text(job.chat_id, IDK_MSG, job.reply_to)
        return

    # Двухфазные доборы: модель может запросить первоисточник (<doc>путь</doc>)
    # или файл исходников (<file>путь</file>) — содержимое уходит В промпт,
    # вердикт перегенерируется (в чат материалы не присылаются).
    extra = ""
    stripped, doc_reqs = _take_doc_requests(content, _catalog_paths())
    if doc_reqs:
        doc_block = _load_source_docs(doc_reqs)
        if doc_block:
            extra += doc_block
            try:
                content, _ = await _generate(sys_block + extra,
                                             job.question, history, provider)
            except (httpx.TimeoutException, httpx.HTTPError, OSError):
                log.warning("diagnose: перегенерация с первоисточником "
                            "не удалась — отвечаю без него")
                content = stripped
        else:
            content = stripped

    stripped, file_reqs = _take_file_requests(content)
    if file_reqs:
        addendum = diagnose.files_block(file_reqs)
        if addendum:
            try:
                content, _ = await _generate(sys_block + extra + addendum,
                                             job.question, history, provider)
                # второй раунд доборов не делаем: маркеры просто срезаем
                content, _ = _take_file_requests(content)
                content, _ = _take_doc_requests(content, _catalog_paths())
            except (httpx.TimeoutException, httpx.HTTPError, OSError):
                log.warning("diagnose: перегенерация с файлами не удалась — "
                            "отвечаю вердиктом предыдущей фазы")
                content = stripped
        else:
            content = stripped
    _remember_turn(job.user_id, job.question, content.rstrip())
    await _send_text(job.chat_id, content.rstrip(), job.reply_to)


# ---------------------------------------------------------------------------
# Админ-панель (/admin; доступ — только админам из _admin_usernames())
# ---------------------------------------------------------------------------

def _fmt_int(n: int) -> str:
    return f"{int(n):,}".replace(",", " ")


def _fmt_duration(seconds: float) -> str:
    seconds = int(max(0.0, seconds))
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    if h:
        return f"{h} ч {m} мин"
    if m:
        return f"{m} мин {s} с"
    return f"{s} с"


def _fmt_dt(ts: float | None) -> str:
    if not ts:
        return "—"
    return datetime.fromtimestamp(
        ts, tz=timezone.utc
    ).astimezone().strftime("%d.%m.%Y %H:%M")


async def _handle_admin(job: Job) -> None:
    try:
        data = await _store.stats_summary()
    except Exception as exc:  # noqa: BLE001 - отчёт не должен ронять воркер
        log.warning("bot: admin: статистика недоступна: %s", _safe_err(exc))
        await _send_text(job.chat_id, "Не удалось собрать статистику.", job.reply_to)
        return
    totals = data["totals"]
    lines = [
        "**Админ-панель «Штурмана»**",
        "",
        f"Вопросов обработано (всего): {_fmt_int(totals['questions'])}",
        "Токенов потрачено (всего): "
        f"{_fmt_int(totals['total_tokens'])} "
        f"(prompt {_fmt_int(totals['prompt_tokens'])}, "
        f"completion {_fmt_int(totals['completion_tokens'])})",
        "Токенов за текущую сессию: "
        f"{_fmt_int(_session_usage['total_tokens'])} "
        f"(prompt {_fmt_int(_session_usage['prompt_tokens'])}, "
        f"completion {_fmt_int(_session_usage['completion_tokens'])})",
        f"Время работы (текущая сессия): {_fmt_duration(time.time() - _START_TS)}",
        f"Первый запуск: {_fmt_dt(data['first_start'])}",
    ]
    users = data["recent_users"]
    if users:
        lines.append("")
        lines.append(f"**Последние пользователи ({len(users)}):**")
        for u in users:
            uname = f"@{u['username']}" if u.get("username") else ""
            name = u.get("display_name") or uname or str(u.get("user_id") or "?")
            tail = f" ({uname})" if uname else ""
            lines.append(
                f"- {name}{tail} — {_fmt_int(u['questions'])} вопр., "
                f"{_fmt_dt(u['last_seen'])}"
            )
    top = data["top_questions"]
    if top:
        lines.append("")
        lines.append(f"**Топ-{len(top)} вопросов:**")
        for i, item in enumerate(top, 1):
            q = " ".join((item["question"] or "").split())
            if len(q) > 120:
                q = q[:119] + "…"
            lines.append(f"{i}. ({item['count']}x) {q}")
    if not users and not top:
        lines.append("")
        lines.append("Пока нет ни одного заданного вопроса.")
    await _send_text(job.chat_id, "\n".join(lines), job.reply_to)


# ---------------------------------------------------------------------------
# Поллер (единственный владелец getUpdates)
# ---------------------------------------------------------------------------

_poller_offset = 0


async def _init_offset() -> None:
    global _poller_offset
    raw = await _store.get_meta("poller_offset")
    if raw and raw.isdigit():
        _poller_offset = int(raw)


async def _persist_offset() -> None:
    await _store.set_meta("poller_offset", str(_poller_offset))


async def _ensure_first_start() -> None:
    """Фиксирует момент первого запуска бота (для отчёта /admin)."""
    existing = await _store.get_meta("bot_first_start")
    if not existing:
        await _store.set_meta("bot_first_start", str(time.time()))


async def _get_updates(offset: int) -> list:
    resp = await _get_bot_client().post(
        _method_url("getUpdates"),
        json={
            "offset": offset,
            "timeout": LONG_POLL_TIMEOUT,
            # callback_query обязателен: без него Telegram не доставляет
            # нажатия inline-кнопок (выбор модели) — кнопки выглядят мёртвыми.
            "allowed_updates": ["message", "channel_post", "callback_query"],
        },
    )
    resp.raise_for_status()
    data = resp.json()
    return data.get("result") or []


async def _poller_loop(bot_username: str) -> None:
    global _poller_offset
    await _init_offset()
    backoff = 1.0
    while True:
        try:
            updates = await _get_updates(_poller_offset)
            for upd in updates:
                _poller_offset = max(_poller_offset, int(upd.get("update_id", 0)) + 1)
                await _persist_offset()
                # callback_query от inline-клавиатур (выбор модели агента):
                # обрабатывается в поллере ДО текстового роутинга — ответ на
                # callback нужен Telegram сразу.
                cb = upd.get("callback_query")
                if isinstance(cb, dict):
                    await _handle_callback(cb)
                    continue
                msg = upd.get("message")
                if not isinstance(msg, dict):
                    continue
                entry = _entry_from_message(msg)
                if entry is not None:
                    await _store.insert(entry)
                chat_id = (msg.get("chat") or {}).get("id")
                user_id = (msg.get("from") or {}).get("id") or chat_id
                # Присланные документы — дифф против GitHub main: сообщение
                # закрывается этим путём (подпись не роутится как вопрос).
                if _queue_document(msg, chat_id, user_id):
                    continue
                route = _route_message(msg, bot_username)
                if route is None:
                    continue
                if route.mode == "ask" and route.question:
                    sender = msg.get("from") or {}
                    try:
                        await _store.record_question(
                            ts=time.time(),
                            user_id=sender.get("id"),
                            username=sender.get("username"),
                            display_name=author_name(msg),
                            question=route.question,
                        )
                    except Exception as exc:  # noqa: BLE001 - статистика не роняет поллер
                        log.warning("bot: вопрос не записан: %s", _safe_err(exc))
                await _enqueue(Job(chat_id, user_id, route.mode, route.question,
                                   msg.get("message_id")))
            backoff = 1.0
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.error("poll: %s (бэкофф %.1fs)", _safe_err(exc), backoff)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, POLLER_BACKOFF_MAX)


# ---------------------------------------------------------------------------
# Справка
# ---------------------------------------------------------------------------

_WELCOME = (
    "Привет! Я «Штурман».\n"
    "Вопрос по документации проекта — просто напишите текстом, отвечу по "
    "базе знаний (принудительно: /ask <вопрос>).\n"
    "Просьба что-то сделать в проекте («создай ветку…», «добавь поле…») — "
    "тоже просто текстом: сработает мини-агент (выбор модели, правки после "
    "«применяй», команды с подтверждением). Принудительно: /agent <задача>.\n"
    "Справка: /help"
)

_HELP = (
    "Команды:\n"
    "/ask <вопрос> — ответ по базе знаний проекта (локальный RAG)\n"
    "/agent <задача> — мини-агент: правка файлов проекта (выбор модели, "
    "диффы на подтверждение, отчёт с файлами)\n"
    "/agent отмена — отменить задачу агента\n"
    "/deps — карта зависимостей модулей проекта (картинка)\n"
    "/deps <символ> — кто вызывает функцию/класс (картинка + список)\n"
    "/help — эта справка\n"
    "/start — приветствие\n"
    "/create <описание> — черновик GitHub-issue (в личке); файлы (до 5), "
    "присланные до финализации, прикладываются к issue; /create — создать\n"
    "«Какие задачи созданы?» — список свежих issue из GitHub\n"
    "«Создай issue на <ошибку>» (в личке) — то же, что /create: распознаю "
    "фразу и заведу черновик\n\n"
    "Просто напишите текстом: вопрос по документации — отвечу по базе "
    "знаний (RAG); просьба изменить проект — запущу мини-агента (правки "
    "только после «применяй»); /ask и /agent — принудительные варианты.\n"
    "«Покажи файл <имя>» — пришлю файл из проекта документом.\n"
    "«Покажи, как устроена <возможность>» — разберу по документации и пришлю "
    "самый релевантный файл исходников.\n"
    "«Почему не работает <функция>» — проверю issue на GitHub; если их нет, "
    "разберу документацию проекта и дам предполагаемый вердикт.\n"
    "Пришлите мне файлы (до 5 за раз) — сравню их с одноимёнными файлами "
    "ветки main на GitHub и пришлю диффы + вердикт.\n"
    "О новых PR на GitHub сообщаю в чат сам и знаю их список — "
    "можете спросить «что за PR?»."
)


# ---------------------------------------------------------------------------
# Утилиты
# ---------------------------------------------------------------------------

def _safe_err(exc: Exception) -> str:
    if isinstance(exc, httpx.HTTPStatusError):
        return f"HTTP {exc.response.status_code}"
    if isinstance(exc, httpx.TimeoutException):
        return "timeout"
    if isinstance(exc, httpx.HTTPError):
        return type(exc).__name__
    return type(exc).__name__


def _split_message(text: str) -> list[str]:
    # после конвертации в HTML текст может подрасти (<b> и экранирование),
    # поэтому режем сырой markdown с запасом до лимита Telegram 4096
    limit = MAX_MSG_CHARS - 300
    if len(text) <= limit:
        return [text]
    parts = []
    remaining = text
    while len(remaining) > limit:
        cut = remaining.rfind("\n", 0, limit)
        if cut <= 0:
            cut = limit
        parts.append(remaining[:cut])
        remaining = remaining[cut:]
    parts.append(remaining)
    return parts


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

async def _main() -> None:
    token = _bot_token()
    if not token:
        log.error("Введение: TG_BOT_TOKEN не задан (и не найден в %s)", _CONFIG_PATH)
        sys.exit(1)

    me = None
    attempt = 0
    while me is None:
        attempt += 1
        try:
            me = await _get_me()
        except AuthError:
            log.error("Введение: токен отклонён Telegram (401/403) — проверьте TG_BOT_TOKEN")
            sys.exit(1)
        if me is None:
            wait = min(10 * attempt, 30.0)
            log.warning("getMe не удался (попытка %d), повтор через %.0f c", attempt, wait)
            await asyncio.sleep(wait)
    bot_username = (me.get("username") or "shturman_mcp_bot")
    bot_name = me.get("first_name") or "Штурман"
    log.info("bot: %s (username @%s), чат %s", bot_name, bot_username, _chat())
    log.info("bot: LM API %s, модель %s; RAG %s (k=%d, strategy=%s)",
             _lm_api_base(), _lm_model(), _rag_search_url(), _rag_k(), _rag_strategy())
    try:
        await _ensure_first_start()
    except Exception as exc:  # noqa: BLE001 - стартовая статистика не критична
        log.warning("bot: не удалось зафиксировать первый запуск: %s", _safe_err(exc))

    _worker_task = asyncio.create_task(_worker(), name="bot-worker")
    poller = asyncio.create_task(_poller_loop(bot_username), name="bot-poller")
    # Наблюдатель GitHub PR: уведомления о новых PR + контекст LLM. При пустой
    # секции github в config.json молча не активен (лог INFO, выход).
    gh_task = asyncio.create_task(
        github_watcher.watcher_loop(
            _store, _send_text, default_chats=[_chat()],
            review_hook=pr_review.make_hook(
                store=_store,
                send_text=_send_text,
                send_doc=_send_document,
                llm_generate=_generate,
                get_admin_names=_admin_usernames,
                model_name=_lm_model(),
                send_html=_send_html,
            ),
        ),
        name="github-watcher",
    )
    try:
        await poller
    finally:
        gh_task.cancel()
        _worker_task.cancel()
        try:
            await _flush_usage()
        except Exception:  # noqa: BLE001 - статистика не критична на выходе
            pass
        _store.close()


if __name__ == "__main__":
    logging.basicConfig(stream=sys.stderr, level=logging.INFO)
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        log.info("bot: остановлен")
