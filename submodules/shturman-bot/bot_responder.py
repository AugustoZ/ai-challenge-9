"""Долгоживущий сервис-ответчик Telegram-бота @shturman_mcp_bot (Дни 27–28 SDD).

Единственный владелец ``getUpdates``-поллера на токен: накапливает сообщения
в общую SQLite/FTS5 базу (``TG_DB_FILE``) и отвечает пользователям через
локальную LLM (LM Studio, ``:1235``). Команда ``/ask`` делает RAG-ответ по
sidecar'у ``gwclub serve`` (``:18700``) с локальными источниками.

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
    свободный текст      — обычный вопрос без RAG (личный чат или @упоминание)
"""

from __future__ import annotations

import asyncio
import html
import json
import logging
import os
import re
import sys
import time
from collections import deque
from datetime import datetime, timezone
from pathlib import Path

import httpx

import wiki_urls
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
DEFAULT_LM_API_BASE = "http://localhost:1235/v1"
DEFAULT_LM_MODEL = "qwen3-8b-mlx"
# 2048, а не 1024 (рекомендация Дня 29 для кратких фактовых ответов): полные
# перечни («какие прошивки есть?») с reasoning qwen3 в 1024 не помещаются —
# finish=length, ответ обрывается на первой марке. Факт. расход на таком вопросе
# ~770 токенов (finish=stop), потолок лишнего не тратит.
DEFAULT_LM_MAX_TOKENS = 2048
DEFAULT_LM_TEMPERATURE = 0.1  # оптимизация Дня 29: reports/optimization.md (judge 9.5, t_gen 6.95с)
DEFAULT_RAG_SEARCH_URL = "http://127.0.0.1:18700/search"
DEFAULT_RAG_K = 5
DEFAULT_RAG_STRATEGY = "structure"

_HYDRATE_MIN_CHARS = 400
_WIKI_URL_MAP: dict[str, str] = {}

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

_CMD_RE = re.compile(r"^/(ask|start|help|admin)(?:@[\w]+)?[ \t]*(.*)$", re.I)
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
        return Route(mode)
    if text.startswith("/"):
        # неизвестная команда: в личке подскажем help, в группе — молчим
        return Route("help") if chat_type == "private" else None

    if chat_type == "private":
        # Любой свободный вопрос — через RAG: бот должен отвечать по базе
        # знаний GW-CLUB, а не «из головы» (фолбэк на plain внутри _handle_ask).
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
    "приведённые документы; ссылку на источник вставляй прямо в предложение "
    "как обычный URL. Маркеры-сноски вида [1] не оставляй. "
    "Если в документах есть перечень (например, список прошивок с названиями) — "
    "перечисляй его полностью и не пиши «список не указан», когда перечень есть "
    "в контексте. Для каждой прошивки указывай ссылку на её страницу вики, "
    "если она есть в документе (строки «Стоимость и описание: URL» и ссылки-"
    "префиксы </path> — это и есть страницы вики; приводи URL как обычную "
    "ссылку). Ссылки вида </path> в документах — это страницы вики. "
    "Не выводи собственных разделов «Источники:» и «Цитаты:» — ответ должен "
    "состоять только из текста ответа со ссылками в предложениях. "
    "Если документов недостаточно для точного ответа, "
    "честно скажи об этом (например: «Не знаю — в приведённых документах об "
    "этом нет данных»), а не домысливай факты. В конце ответа задай ОДИН "
    "короткий уточняющий вопрос, только если он реально помогает и ещё не "
    "звучал в диалоге (пример уместного первого: про марку/модель и год — "
    "HAVAL Dargo, HAVAL H6, TANK 300, GWM Rox). Если контекст диалога уже "
    "снимает уточнение — не спрашивай.\n/no_think"
)

PLAIN_SYSTEM = (
    "Ты — бот «Штурман» сообщества GW-CLUB (wiki.gwm-club.ru): прошивки "
    "мультимедиа для HAVAL Dargo/H6, TANK 300 и GWM Rox — Lite, Full, Ultra, "
    "Assist, Max и бесплатные. Отвечай на вопросы на русском, кратко и по "
    "существу; уточнения задавай только в терминах этих автомобилей и "
    "прошивок GW-CLUB, а не про смартфоны/планшеты/маршрутизаторы. "
    "Уточняющий вопрос в конце задавай только если он реально помогает и ещё "
    "не звучал в диалоге; иначе отвечай без него. "
    "Если не знаешь — так и скажи и предложи заглянуть на wiki.gwm-club.ru.\n/no_think"
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
            full = wiki_urls.section_text(c.get("source") or "", c.get("section") or "")
            if full:
                c = {**c, "content": full, "hydrated": True}
        hydrated.append(c)
    return hydrated


def _build_rag_block(chunks: list[dict], stable_extra: str = "") -> str:
    """Собирает RAG-блок для system-промта (по образцу RagPrompts.buildRagBlock).

    Порядок «стабильное -> переменное» (правила, каталог, первоисточник — до
    чанков) даёт LM Studio переиспользовать KV-кэш префикса между запросами.
    """
    chunks = _hydrate_chunks(chunks)
    lines = [RAG_MARKER, RAG_SYSTEM, _DOC_FETCH_RULE]
    catalog = _build_catalog_block()
    if catalog:
        lines.append("\n\n" + catalog)
    if stable_extra:
        lines.append(stable_extra)
    used = sum(len(x) for x in lines)
    for i, c in enumerate(chunks, 1):
        source = c.get("source", "")
        section = c.get("section", "")
        content = wiki_urls.rewrite_wiki_links(c.get("content", ""), source, _WIKI_URL_MAP)
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
    """Корень зеркала вики (первоисточник для подтяжки полных страниц)."""
    env = os.environ.get("RAG_DOCS_ROOT", "").strip()
    if env:
        return Path(env)
    # Поиск обходом вверх — работает на любой глубине (хост/контейнер).
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "gw-club-docs"
        if candidate.is_dir():
            return candidate
    return Path(__file__).resolve().parent / "gw-club-docs"


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
        text = wiki_urls.rewrite_wiki_links(text, rel, _WIKI_URL_MAP)
        if len(text) > _DOC_MAX_CHARS:
            text = text[:_DOC_MAX_CHARS].rstrip() + "…"
        parts.append(f"\n\nПЕРВОИСТОЧНИК {rel} (полный текст страницы вики):\n{text}")
    return "".join(parts) + _DOC_USE_HINT if parts else ""


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


async def _post_chat(messages: list[dict], max_tokens: int):
    """Один POST /chat/completions. Возвращает (content, finish_reason)."""
    payload = {
        "model": _lm_model(),
        "messages": messages,
        "temperature": _lm_temperature(),
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


async def _chat_once(messages: list[dict], max_tokens: int):
    """Один POST /chat/completions. Возвращает (content, finish_reason)."""
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
    system_prompt: str, question: str, history: list[dict] | None = None
) -> tuple[str, str]:
    """Генерация с ретраем при пустом content и достройкой при обрыве.

    Пустой content при finish=length — ретрай с удвоенным лимитом. Непустой,
    но оборванный (finish=length) — достраивается continuation-раундами
    (не более _LM_CONTINUATIONS_MAX). history — предшествующие пары
    вопрос-ответ этого пользователя. Возвращает (content, finish_reason
    последнего вызова).
    """
    messages = [{"role": "system", "content": system_prompt}]
    messages.extend(history or [])
    messages.append({"role": "user", "content": question})
    max_tokens = _lm_max_tokens()
    content, finished = await _chat_once(messages, max_tokens)
    if not content and finished == "length":
        content, finished = await _chat_once(messages, max_tokens * 2)
    rounds = 0
    while finished == "length" and content and rounds < _LM_CONTINUATIONS_MAX:
        tail, finished = await _post_chat(
            messages
            + [{"role": "assistant", "content": content},
               {"role": "user", "content": _CONTINUATION_ASK}],
            max_tokens,
        )
        if not tail:
            break
        content += tail
        rounds += 1
        log.warning("bot: ответ достроен (раунд %d, +%d симв.)", rounds, len(tail))
    return content, finished


# ---------------------------------------------------------------------------
# Обработка одного «хода» бота (worker — последовательная глобальная очередь)
# ---------------------------------------------------------------------------

_jobs: asyncio.Queue = asyncio.Queue()
_user_last: dict[int, float] = {}
_worker_task: asyncio.Task | None = None


class Job:
    __slots__ = ("chat_id", "user_id", "mode", "question", "reply_to", "lm_retries")

    def __init__(self, chat_id, user_id, mode: str, question: str, reply_to=None):
        self.chat_id = chat_id
        self.user_id = user_id
        self.mode = mode
        self.question = question
        self.reply_to = reply_to
        self.lm_retries = 0


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
                        "Локальная модель недоступна — вероятно, LM Studio "
                        "перезагружается. Повторю ваш вопрос через минуту.",
                        job.reply_to,
                    )
                await asyncio.sleep(_LM_JOB_WAIT)
                await _jobs.put(job)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.error("responder: job: %s", _safe_err(exc))
            try:
                await _send_text(job.chat_id, "Что-то пошло не так — попробуйте ещё раз.")
            except Exception:
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
    if job.mode == "ask" and not job.question:
        await _send_text(
            job.chat_id,
            "Укажите вопрос после команды. Например: /ask как подключить Android Auto",
            job.reply_to,
        )
        return

    # rate-limit по пользователю
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


async def _handle_plain(job: Job) -> None:
    try:
        content, _ = await _generate(
            PLAIN_SYSTEM, job.question, _history_messages(job.user_id)
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


async def _handle_ask(job: Job) -> None:
    contacts_intent = _CONTACTS_INTENT_RE.search(job.question) is not None
    try:
        # Контактовому интенту реранк не нужен: первоисточник инжектится
        # целиком — экономим ~2.7 с на кросс-энкодере.
        chunks = await _rag_search(job.question, rerank=not contacts_intent)
    except (httpx.TimeoutException, httpx.HTTPError, OSError, ValueError):
        # sidecar недоступен: не роняем вопрос, отвечаем доменным plain-фолбэком
        log.warning("RAG недоступен (%s) — фолбэк на plain", _rag_search_url())
        await _handle_plain(job)
        return

    if not chunks:
        # база пуста/ничего не нашлось: лучше доменный ответ, чем отказ сервиса
        await _handle_plain(job)
        return

    contacts_block = ""
    contacts_doc = _contacts_doc_for(job.question)
    if contacts_intent and contacts_doc in _catalog_paths():
        contacts_block = _load_source_docs([contacts_doc])
    stable_extra = contacts_block + _CONTACTS_DOC_HINT if contacts_block else ""
    sys_block = _build_rag_block(chunks, stable_extra)
    history = _history_messages(job.user_id)
    try:
        content, _ = await _generate(sys_block, job.question, history)
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
            "allowed_updates": ["message", "channel_post"],
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
                msg = upd.get("message")
                if not isinstance(msg, dict):
                    continue
                entry = _entry_from_message(msg)
                if entry is not None:
                    await _store.insert(entry)
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
                chat_id = (msg.get("chat") or {}).get("id")
                user_id = (msg.get("from") or {}).get("id") or chat_id
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
    "Привет! Я «Штурман» — отвечаю через локальную LLM (без облака).\n"
    "Напишите вопрос, а для ответа по базе знаний используйте /ask <вопрос>.\n"
    "Справка: /help"
)

_HELP = (
    "Команды:\n"
    "/ask <вопрос> — ответ по базе знаний GW-CLUB (локальный RAG)\n"
    "/help — эта справка\n"
    "/start — приветствие\n\n"
    "Просто напишите вопрос текстом — найду ответ по базе знаний "
    "wiki.gwm-club.ru (прошивки HAVAL / TANK 300 / Rox)."
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
    _WIKI_URL_MAP.update(wiki_urls.build_map())
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
    try:
        await poller
    finally:
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
