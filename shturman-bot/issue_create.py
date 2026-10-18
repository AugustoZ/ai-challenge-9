"""Команда /create: GitHub-issue из контекста, присланного боту в личку.

Протокол (только личный чат):
  /create <описание> — описание принимается в черновик (TTL 60 мин).
    Пока черновик активен, присланные документы (до 5) прикладываются
    к нему вместо штатного сравнения с main (filediff).
  /create — финализация: LLM формирует заголовок и тело issue из
    описания, приложенные файлы встраиваются в тело кодовыми блоками
    (REST API GitHub не позволяет грузить произвольные аттачменты к
    issue), затем issue создаётся в первом репозитории из секции
    ``github`` config.json методом POST /repos/{repo}/issues.
  /create отмена — сброс черновика.

LLM только оформляет текст (заголовок/тело); файлы в тело добавляет код:
содержимое модели не передаётся (капы и целостность важнее «мнения» модели).
Если LLM не вернула разбор формата TITLE:/BODY:, используется исходный
текст пользователя (фолбэк), о чём бот сообщает в чате.

Токен — из секции ``github`` config.json / env (github_watcher.load_settings).
Токен не логируется и не попадает в вывод.
"""

from __future__ import annotations

import logging
import re
import time
from typing import Awaitable, Callable

import httpx

import file_diff
import github_watcher

log = logging.getLogger("issue_create")

MAX_FILES = file_diff.MAX_FILES            # 5 приложений к одному issue
MAX_ATTACHMENT_BYTES = 200_000             # сырой размер файла из Telegram
ATTACH_CHAR_CAP = 8000                     # симв. одного файла в теле issue
BODY_TOTAL_CAP = 55_000                    # запас до лимита GitHub (65536)
TITLE_MAX = 80
DRAFT_TTL = 60 * 60.0                      # сек жизни черновика
HTTP_TIMEOUT = 60.0

CANCEL_WORDS = {"отмена", "cancel"}

CREATE_SYSTEM = (
    "Ты — помощник, который оформляет GitHub-issue по сырому описанию "
    "пользователя проекта «Штурман». Ответь строго в формате:\n"
    "TITLE: <короткий заголовок, до 80 символов, по-русски>\n"
    "BODY:\n"
    "<тело issue в Markdown: суть, детали/шаги, ожидаемое и наблюдаемое "
    "поведение, если они следуют из описания. По-русски. Не выдумывай "
    "фактов, которых нет в описании; если данных не хватает — отметь это "
    "в теле отдельным пунктом «Уточнить».>\n"
    "/no_think"
)

USAGE_MSG = (
    "Формат команды /create (только в личке):\n"
    "/create <описание> — принять описание в черновик issue;\n"
    "пришлите файлы (до 5), пока черновик активен — они будут приложены;\n"
    "/create — создать issue из черновика;\n"
    "/create отмена — отменить черновик."
)


# ---------------------------------------------------------------------------
# Черновики (in-memory, per-chat)
# ---------------------------------------------------------------------------

_drafts: dict[int, dict] = {}


def _now() -> float:
    return time.monotonic()


def _get_draft(chat_id) -> dict | None:
    draft = _drafts.get(chat_id)
    if draft is None:
        return None
    if _now() - draft["ts"] > DRAFT_TTL:
        _drafts.pop(chat_id, None)
        return None
    return draft


def pending_active(chat_id) -> bool:
    """Активен ли черновик /create в чате (документы идут в него, не в filediff)."""
    return _get_draft(chat_id) is not None


def set_text(chat_id, text: str) -> dict:
    """Создать или обновить черновик (документы сохраняются)."""
    draft = _get_draft(chat_id)
    if draft is None:
        draft = _drafts[chat_id] = {
            "text": "", "docs": [], "dropped": 0, "auto": [], "ts": _now(),
        }
    draft["text"] = (text or "").strip()
    draft["ts"] = _now()
    return draft


def set_auto_files(chat_id, paths: list[str]) -> None:
    """Запомнить файлы проекта, найденные автопоиском по описанию."""
    draft = _drafts.get(chat_id)
    if draft is not None:
        draft["auto"] = list(paths or [])


def auto_files(chat_id) -> list[str]:
    """Файлы проекта из автопоиска активного черновика ([] — нет)."""
    draft = _get_draft(chat_id)
    return list(draft.get("auto") or []) if draft else []


def attach_doc(chat_id, file_id: str, name: str, caption: str) -> bool:
    """Документ в черновик; True — сообщение закрыто этим путём.

    Лишние (сверх MAX_FILES) не теряются молча: считаются в ``dropped``.
    Нет активного черновика → False (документ пойдёт в filediff-батч).
    """
    draft = _get_draft(chat_id)
    if draft is None:
        return False
    draft["ts"] = _now()
    if len(draft["docs"]) >= MAX_FILES:
        draft["dropped"] += 1
    else:
        draft["docs"].append((file_id, name, caption))
    return True


def docs_count(chat_id) -> int:
    draft = _get_draft(chat_id)
    return len(draft["docs"]) if draft else 0


def cancel(chat_id) -> bool:
    return _drafts.pop(chat_id, None) is not None


def take_draft(chat_id) -> dict | None:
    """Забрать черновик целиком (для финализации)."""
    return _drafts.pop(chat_id, None)


# ---------------------------------------------------------------------------
# Чистые функции (без сети) — покрыты inline-смоками
# ---------------------------------------------------------------------------

def fallback_title(text: str) -> str:
    """Заголовок-фолбэк: первая строка описания, свёрнутая до 80 симв."""
    line = (text or "").strip().splitlines()
    raw = line[0].strip() if line else ""
    raw = re.sub(r"\s+", " ", raw).strip(" #*")
    if not raw:
        raw = "Issue из Telegram"
    return raw[:TITLE_MAX].strip()


def parse_llm_issue(content: str, fb_title: str) -> tuple[str, str]:
    """Разбор ответа LLM: строки TITLE: и (опционально) BODY:.

    Возвращает (title, body); нет TITLE → фолбэк-заголовок, тело — весь
    остаток ответа.
    """
    text = (content or "").strip()
    if not text:
        return fb_title, ""
    m = re.search(
        r"(?im)^[ \t]*(?:\*\*)?title(?:\*\*)?[ \t]*[:\-][ \t]*(.+?)[ \t]*$",
        text,
    )
    title = m.group(1).strip() if m else ""
    rest = text[m.end():].strip() if m else text
    mb = re.search(
        r"(?im)^[ \t]*(?:\*\*)?body(?:\*\*)?[ \t]*[:\-]?(?:\*\*)?[ \t]*$", rest
    )
    if mb:
        rest = rest[mb.end():].strip()
    title = title.strip("*#` ").strip()
    if not title:
        title = fb_title
    return title[:TITLE_MAX].strip(), rest


def attachment_block(files: list[dict], skipped: list[str]) -> str:
    """Markdown-блок приложений: содержимое файлов кодовыми блоками.

    files: [{name, text}] — текст уже декодирован (utf-8/replace);
    skipped: ['имя — причина'].
    """
    parts: list[str] = []
    for f in files:
        text = f.get("text") or ""
        note = ""
        if len(text) > ATTACH_CHAR_CAP:
            text = text[:ATTACH_CHAR_CAP] + "\n… [файл показан не полностью]"
            note = ""
        parts.append(f"### {f.get('name') or 'файл'}\n````text\n{text}\n````")
    if skipped:
        parts.append(
            "### Не приложены\n" + "\n".join(f"- {s}" for s in skipped)
        )
    if not parts:
        return ""
    return "\n\n## Приложенные файлы\n\n" + "\n\n".join(parts)


def compose_body(llm_body: str, files: list[dict], skipped: list[str]) -> str:
    """Тело issue: текст LLM + блок приложений, общий кап BODY_TOTAL_CAP."""
    body = (llm_body or "").strip()
    block = attachment_block(files, skipped)
    if block:
        body = (body + "\n" + block).strip()
    if len(body) > BODY_TOTAL_CAP:
        body = body[:BODY_TOTAL_CAP] + "\n… [тело обрезано: лимит GitHub API]"
    return body


def build_prompt(draft_text: str, file_names: list[str]) -> str:
    """Пользовательская часть промпта: описание + имена приложений."""
    parts = ["Описание пользователя (сырой текст):", draft_text.strip()]
    if file_names:
        parts.append(
            "К issue будут приложены файлы (в тело включать их не нужно): "
            + ", ".join(file_names)
        )
    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Сеть: LLM + GitHub API
# ---------------------------------------------------------------------------

async def create_from_draft(
    draft_text: str,
    files: list[dict],
    *,
    repo: str,
    token: str,
    llm_generate: Callable[..., Awaitable[tuple[str, object]]],
) -> dict:
    """Полный поток финализации: LLM → тело → POST /repos/{repo}/issues.

    files: [{name, raw}] — уже скачанные из Telegram; бинарные и слишком
    большие отсекаются здесь же (возвращаются в ``skipped``).
    Возвращает {url, title, fallback, skipped}.
    """
    skipped: list[str] = []
    attachments: list[dict] = []
    for f in files:
        raw = f.get("raw") or b""
        if file_diff.looks_binary(raw):
            skipped.append(f"{f.get('name')} — бинарный файл, в тело не включён")
            continue
        if len(raw) > MAX_ATTACHMENT_BYTES:
            skipped.append(
                f"{f.get('name')} — больше {MAX_ATTACHMENT_BYTES // 1000} КБ, "
                "в тело не включён"
            )
            continue
        attachments.append({
            "name": f.get("name"),
            "text": raw.decode("utf-8", errors="replace"),
        })

    fb = fallback_title(draft_text)
    prompt = build_prompt(draft_text, [a["name"] or "файл" for a in attachments])
    content, _ = await llm_generate(CREATE_SYSTEM, prompt, [])
    title, llm_body = parse_llm_issue(content, fb)
    fallback = (content or "").strip() == "" or title == fb
    body = compose_body(llm_body, attachments, skipped)

    headers = {**github_watcher._HEADERS, "Authorization": f"Bearer {token}"}
    async with httpx.AsyncClient(timeout=HTTP_TIMEOUT, headers=headers) as client:
        resp = await client.post(
            f"{github_watcher.GITHUB_API}/repos/{repo}/issues",
            json={"title": title, "body": body},
        )
        resp.raise_for_status()
        url = str(resp.json().get("html_url") or "")
    return {"url": url, "title": title, "fallback": fallback, "skipped": skipped}


def reset_state() -> None:
    """Сброс черновиков (для проверок)."""
    _drafts.clear()
