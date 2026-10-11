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
from collections import Counter
from contextlib import asynccontextmanager

import httpx

from common import build_server
from storage import (
    DEFAULT_BUFFER_PATH,
    DEFAULT_DB_PATH,
    TgStore,
    chat_matches_chat,
    entry_chat_matches as _entry_matcher,
    entry_from_message,
    chat_label as _chat_label_for,
)

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

# Корень проекта: standalone shturman-bot.
_REPO_ROOT = os.path.dirname(os.path.abspath(__file__))

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

def _chat_matches(chat: dict) -> bool:
    """Совпадение чата: числовой TG_CHAT -> по chat.id; иначе по username."""
    return chat_matches_chat(chat, _chat())


def _chat_label(msg: dict) -> str:
    """Идентификатор чата для поля `chat` в буфере: id при числовом TG_CHAT,
    иначе username (а если username нет — id)."""
    return _chat_label_for(msg.get("chat") or {}, _chat())


def _entry_chat_matches(entry: dict) -> bool:
    """Совпадение `chat` записи буфера с настроенным TG_CHAT."""
    return _entry_matcher(entry, _chat())


def _entry_from_message(msg: dict) -> dict | None:
    """Строит запись буфера из сообщения или None (чужой чат / сервисное)."""
    return entry_from_message(msg, chat_match=_chat_matches, chat_labeller=_chat_label)


def _format_line(entry: dict) -> str:
    """'[YYYY-MM-DD HH:MM UTC] author: text'."""
    ts = entry["_dt"].strftime("%Y-%m-%d %H:%M")
    return f"[{ts} UTC] {entry['author']}: {entry['text']}"



_store = TgStore(_db_path(), _retention_days(), _buffer_path(), chat_matcher=_entry_chat_matches)


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
