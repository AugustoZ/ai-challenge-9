"""MCP-сервер Wiki.js для «Штурмана» (задача 8 плана mcp-integration-shturman).

Три тула (больше — нельзя по guardrail'ам плана):
    - create_faq_page  — идемпотентное создание скрытой FAQ-страницы (faq-admin/*)
    - read_faq_page    — чтение FAQ-страницы (отсутствие страницы — НЕ ошибка)
    - append_faq_page  — добавление секции с датой-заголовком (авто-создание)

Транспорт: stdio (FastMCP из common.build_server); stdout = только JSON-RPC,
все логи — stderr. HTTP: httpx к `${WIKI_BASE_URL}/graphql`, заголовок
`Authorization: Bearer ${WIKI_API_KEY}`, таймаут 20s на каждый запрос.

Проверенные допущения схемы Wiki.js v2.5.315 (важно!):
    1. План/инструкция упоминали `responseResult { status errorCode message }`
       со статусом "SUCCESS". В реальной схеме Wiki.js v2.5.315 (тег requarks/wiki
       v2.5.315, server/graph/schemas/common.graphql) тип ResponseStatus —
       `{ succeeded: Boolean!, errorCode: Int!, slug: String!, message: String }`:
       поля `status` НЕ существует, и запрос с ним упал бы GraphQL-валидацией.
       Поэтому успех = `succeeded == true`, ошибка = errorCode + message
       (семантика плана «non-SUCCESS -> ok=false с errorCode/message» сохранена).
       Проверено также живым запросом к wiki.gwm-club.ru/graphql (unauth,
       без ключей): singleByPath отдаёт PageNotFound (code 6003) при отсутствии
       страницы, интроспекция на сервере отключена.
    2. `pages.create` (схема v2.5.315): content!, description!, editor!,
       isPublished!, isPrivate!, locale!, path!, tags!, title! — ровно набор
       из плана. Возвращает PageResponse { responseResult, page { id path } }.
    3. `pages.update` (схема v2.5.315): обязателен только `id: Int!`; чтобы
       поведение было предсказуемым, обратно передаём текущие значения
       title/path/locale/editor/isPublished/isPrivate/description страницы
       (поведенчески нейтрально); `tags` не передаём — не трогаем существующие.
"""

import json
import logging
import os
import re
from datetime import datetime, timezone

import httpx

from common import build_server

log = logging.getLogger("wiki_server")

DEFAULT_BASE_URL = "https://wiki.gwm-club.ru"
LOCALE = "ru"
DEFAULT_PATH = "faq-admin/chastye-voprosy"
DEFAULT_TITLE = "Частые вопросы"
DEFAULT_DESCRIPTION = "FAQ клуба GW-CLUB"
ALLOWED_PREFIX = "faq-admin/"
TIMEOUT_S = 20.0

mcp = build_server("shturman-wiki")


class WikiToolError(Exception):
    """Читаемая ошибка тула: попадает в ответ как ok=false."""

    def __init__(self, message: str, error_code: int | None = None):
        super().__init__(message)
        self.message = message
        self.error_code = error_code


def _result(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False)


def _error_result(exc: "WikiToolError") -> str:
    err: dict = {"ok": False, "error": exc.message}
    if exc.error_code is not None:
        err["errorCode"] = exc.error_code
    return json.dumps(err, ensure_ascii=False)


# --------------------------------------------------------------------------
# Конфигурация (читается на вызов тула, не на импорт: boot без ключа — OK)
# --------------------------------------------------------------------------

def _base_url() -> str:
    return (os.environ.get("WIKI_BASE_URL") or DEFAULT_BASE_URL).rstrip("/")


def _api_key() -> str:
    key = (os.environ.get("WIKI_API_KEY") or "").strip()
    if not key:
        raise WikiToolError(
            "API-ключ не задан: установите переменную окружения WIKI_API_KEY "
            "(Wiki.js → Administration → API Keys, fullAccess)."
        )
    return key


def _page_url(path: str) -> str:
    return f"{_base_url()}/{LOCALE}/{path}"


# --------------------------------------------------------------------------
# Нормализация пути: trim '/', lowercase, только латиница, отказ при '..',
# только внутри faq-admin/*.
# --------------------------------------------------------------------------

def _normalize_path(path: str | None) -> str:
    p = (path or DEFAULT_PATH).strip().strip("/")
    if ".." in p.split("/"):
        raise WikiToolError("Недопустимый путь: '..' запрещён.")
    p = p.lower()
    if not p.isascii():
        raise WikiToolError(
            "Недопустимый путь: только латиница (ASCII); кириллицу транслитерируйте вручную."
        )
    if not p.startswith(ALLOWED_PREFIX):
        raise WikiToolError(
            f"Недопустимый путь: разрешены только страницы внутри '{ALLOWED_PREFIX}*'."
        )
    if not re.fullmatch(r"[a-z0-9][a-z0-9/\-]*", p) or "//" in p:
        raise WikiToolError(
            "Недопустимый путь: используйте латинские буквы, цифры, '-' и '/'."
        )
    return p


# --------------------------------------------------------------------------
# GraphQL-клиент (httpx, Bearer, таймаут 20s; секреты не логируются)
# --------------------------------------------------------------------------

def gql_str(value: str) -> str:
    """JSON-литерал строки — валидный строковый литерал GraphQL
    (правильное экранирование кавычек/переводов строк/unicode)."""
    return json.dumps(value)  # ensure_ascii=True -> \uXXXX, безопасно


def _graphql(query: str) -> tuple[dict, list]:
    """POST ${WIKI_BASE_URL}/graphql. Возвращает (data, errors) сырого ответа:
    решение «страницы нет vs ошибка» принимает вызывающий код по содержимому
    ошибок (см. _fetch_page)."""
    url = f"{_base_url()}/graphql"
    headers = {
        "Authorization": f"Bearer {_api_key()}",
        "Content-Type": "application/json",
    }
    try:
        resp = httpx.post(url, json={"query": query}, headers=headers, timeout=TIMEOUT_S)
    except httpx.TimeoutException as exc:
        log.error("Wiki request timeout after %ss: %s", TIMEOUT_S, type(exc).__name__)
        raise WikiToolError(
            f"Таймаут соединения с Wiki ({TIMEOUT_S}s): {type(exc).__name__}."
        ) from exc
    except httpx.ConnectError as exc:
        log.error("Wiki connect failed: %s", type(exc).__name__)
        raise WikiToolError(
            f"Не удалось установить connection с Wiki ({url}): connection error — "
            f"{type(exc).__name__}: {exc}"
        ) from exc
    except httpx.HTTPError as exc:
        log.error("Wiki HTTP error: %s", type(exc).__name__)
        raise WikiToolError(
            f"Ошибка соединения с Wiki ({url}): {type(exc).__name__}: {exc}"
        ) from exc

    if resp.status_code in (401, 403):
        raise WikiToolError(
            f"API-ключ неверен или не имеет прав (HTTP {resp.status_code}): "
            "проверьте WIKI_API_KEY (нужен fullAccess-ключ)."
        )
    if resp.status_code != 200:
        raise WikiToolError(f"Wiki вернула HTTP {resp.status_code} на {url}.")

    try:
        payload = resp.json()
    except ValueError as exc:
        raise WikiToolError("Wiki вернула не-JSON ответ на /graphql.") from exc

    errors = payload.get("errors") or []
    data = payload.get("data")
    if not isinstance(data, dict):
        # Нет частичных данных — любая ошибка фатальна для запроса.
        if errors:
            first = errors[0] if isinstance(errors, list) else {}
            ext = first.get("extensions") or {}
            code = ext.get("exception", {}).get("code") or ext.get("code")
            raise WikiToolError(
                f"Ошибка GraphQL: {first.get('message', 'unknown error')} (code={code})",
                error_code=code if isinstance(code, int) else None,
            )
        raise WikiToolError("Некорректный ответ GraphQL: отсутствует 'data'.")
    return data, errors


def _raise_graphql_errors(errors: list) -> None:
    if not errors:
        return
    first = errors[0] if isinstance(errors, list) else {}
    ext = first.get("extensions") or {}
    code = ext.get("exception", {}).get("code") or ext.get("code")
    raise WikiToolError(
        f"Ошибка GraphQL: {first.get('message', 'unknown error')} (code={code})",
        error_code=code if isinstance(code, int) else None,
    )


def _response_status(data: dict, errors: list, action: str) -> dict:
    """Достаёт responseResult/page из ответа мутации; non-succeeded -> ошибка."""
    _raise_graphql_errors(errors)
    resp = ((data.get("pages") or {}).get(action) or {})
    rr = resp.get("responseResult") or {}
    if not rr.get("succeeded"):
        raise WikiToolError(
            f"Wiki отклонила мутацию pages.{action}: {rr.get('message') or 'без сообщения'} "
            f"(errorCode={rr.get('errorCode')})",
            error_code=rr.get("errorCode") if isinstance(rr.get("errorCode"), int) else None,
        )
    return resp.get("page") or {}


# --------------------------------------------------------------------------
# Запросы к страницам
# --------------------------------------------------------------------------

_PAGE_MIN_FIELDS = "id path title content updatedAt"
_PAGE_FULL_FIELDS = (
    "id path title description content updatedAt editor isPublished isPrivate locale"
)


def _fetch_page(path: str, fields: str) -> dict | None:
    """singleByPath; None = страницы нет (PageNotFound), иначе ошибка тула."""
    query = (
        "query { pages { singleByPath(path: "
        f"{gql_str(path)}, locale: {gql_str(LOCALE)}"
        f") {{ {fields} }} }} }}"
    )
    data, errors = _graphql(query)
    page = (data.get("pages") or {}).get("singleByPath")
    if page:
        return page
    absent_markers = ("does not exist", "PageNotFound")
    if errors and any(
        m in (e.get("message", "") + (e.get("extensions", {}).get("exception", {}) or {}).get("message", ""))
        for e in errors
        for m in absent_markers
    ):
        return None
    _raise_graphql_errors(errors)
    return None


def _create_mutation(path: str, title: str, content: str, description: str) -> str:
    # Схема v2.5.315: create(content!, description!, editor!, isPublished!,
    # isPrivate!, locale!, path!, tags!, title!) -> PageResponse.
    return (
        "mutation { pages { create("
        f"content: {gql_str(content)}, "
        f"description: {gql_str(description)}, "
        'editor: "markdown", '
        "isPublished: true, "
        "isPrivate: true, "
        f"locale: {gql_str(LOCALE)}, "
        f"path: {gql_str(path)}, "
        "tags: [], "
        f"title: {gql_str(title)}"
        ") { responseResult { succeeded errorCode slug message } page { id path } } } }"
    )


def _update_mutation(page: dict, new_content: str) -> str:
    # Схема v2.5.315: обязателен только id; остальные поля возвращаем как есть
    # (нейтрально), tags не трогаем (не передаём).
    return (
        "mutation { pages { update("
        f"id: {int(page['id'])}, "
        f"content: {gql_str(new_content)}, "
        f"description: {gql_str(page.get('description') or DEFAULT_DESCRIPTION)}, "
        f"editor: {gql_str(page.get('editor') or 'markdown')}, "
        f"isPublished: {'true' if page.get('isPublished', True) else 'false'}, "
        f"isPrivate: {'true' if page.get('isPrivate', True) else 'false'}, "
        f"locale: {gql_str(page.get('locale') or LOCALE)}, "
        f"path: {gql_str(page.get('path') or '')}, "
        f"title: {gql_str(page.get('title') or DEFAULT_TITLE)}"
        ") { responseResult { succeeded errorCode slug message } page { id path } } } }"
    )


def _create_page(path: str, title: str, content: str, description: str) -> dict:
    data, errors = _graphql(_create_mutation(path, title, content, description))
    return _response_status(data, errors, "create")


def _update_page(page: dict, new_content: str) -> dict:
    data, errors = _graphql(_update_mutation(page, new_content))
    return _response_status(data, errors, "update")


# --------------------------------------------------------------------------
# Тулы (описания короткие — идут в промпт LLM)
# --------------------------------------------------------------------------

@mcp.tool()
def create_faq_page(path: str = DEFAULT_PATH, title: str = DEFAULT_TITLE, content: str = "") -> str:
    """Создаёт скрытую FAQ-страницу в Wiki (faq-admin/*); идемпотентно, без перезаписи."""
    try:
        norm = _normalize_path(path)
        log.info("create_faq_page path=%s", norm)
        existing = _fetch_page(norm, "id")
        if existing is not None:
            return _result({
                "ok": True,
                "url": _page_url(norm),
                "path": norm,
                "created": False,
                "pageId": existing.get("id"),
            })
        page = _create_page(norm, title or DEFAULT_TITLE, content or "", DEFAULT_DESCRIPTION)
        log.info("create_faq_page created id=%s", page.get("id"))
        return _result({
            "ok": True,
            "url": _page_url(norm),
            "path": norm,
            "created": True,
            "pageId": page.get("id"),
        })
    except WikiToolError as exc:
        log.error("create_faq_page failed: %s", exc.message)
        return _error_result(exc)


@mcp.tool()
def read_faq_page(path: str = DEFAULT_PATH) -> str:
    """Читает FAQ-страницу Wiki (faq-admin/*); отсутствующая страница — exists:false, не ошибка."""
    try:
        norm = _normalize_path(path)
        log.info("read_faq_page path=%s", norm)
        page = _fetch_page(norm, _PAGE_MIN_FIELDS)
        if page is None:
            return _result({"ok": True, "exists": False, "url": _page_url(norm), "path": norm})
        return _result({
            "ok": True,
            "exists": True,
            "url": _page_url(norm),
            "path": page.get("path") or norm,
            "title": page.get("title"),
            "content": page.get("content") or "",
            "updatedAt": page.get("updatedAt"),
        })
    except WikiToolError as exc:
        log.error("read_faq_page failed: %s", exc.message)
        return _error_result(exc)


@mcp.tool()
def append_faq_page(content: str, section_title: str = "", path: str = DEFAULT_PATH) -> str:
    """Добавляет секцию с датой-заголовком в FAQ-страницу Wiki; при отсутствии создаёт её."""
    try:
        if not (content or "").strip():
            raise WikiToolError("Пустой content: добавлять нечего.")
        norm = _normalize_path(path)
        header_date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        header = f"## {header_date} {section_title}".rstrip() if section_title else f"## {header_date}"
        log.info("append_faq_page path=%s date=%s", norm, header_date)
        page = _fetch_page(norm, _PAGE_FULL_FIELDS)
        if page is None:
            created = _create_page(norm, DEFAULT_TITLE, content, DEFAULT_DESCRIPTION)
            log.info("append_faq_page auto-created id=%s", created.get("id"))
            return _result({
                "ok": True,
                "url": _page_url(norm),
                "path": norm,
                "created": True,
                "pageId": created.get("id"),
                "newSize": len(content),
            })
        old_content = page.get("content") or ""
        new_content = f"{old_content}\n\n{header}\n\n{content}"
        updated = _update_page(page, new_content)
        log.info("append_faq_page updated id=%s", updated.get("id"))
        return _result({
            "ok": True,
            "url": _page_url(norm),
            "path": norm,
            "created": False,
            "pageId": page.get("id"),
            "oldSize": len(old_content),
            "newSize": len(new_content),
        })
    except WikiToolError as exc:
        log.error("append_faq_page failed: %s", exc.message)
        return _error_result(exc)


if __name__ == "__main__":
    # stdio по умолчанию: stdout остаётся чистым JSON-RPC.
    mcp.run()
