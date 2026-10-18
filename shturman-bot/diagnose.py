"""Диагностика «почему не работает X»: issue GitHub → RAG-вердикт.

Интент «почему не работает …» (перехват в bot_responder._handle_ask до
обычного RAG-пайплайна) обрабатывается так:
  1) по теме вопроса ищутся issues в репозиториях секции ``github``:
     сначала REST-список свежих issue (без лага индексации, локальный
     стем-матч понимает русскую морфологию), затем добор через GitHub
     Search API (read-only). Найдено — бот отвечает списком
     «#N «название» (открыт/закрыт) — ссылка», вопрос закрывается;
  2) не найдено (или GitHub недоступен) — тема уходит в RAG-поиск по
     корпусу документации, найденные чанки передаются локальной LLM,
     которая строит вердикт «Вердикт / Проверить». Если документов
     недостаточно, модель запрашивает файл маркером <file>путь</file> —
     бот читает его из дерева проекта (project_files) и передаёт
     СОДЕРЖИМОЕ в промпт для перегенерации вердикта (в чат файл не идёт).

Модуль в стиле file_diff.py: чистые функции без сети + один read-only
сетевой вызов. Токен — из секции ``github`` config.json / env
(github_watcher.load_settings) — передаётся вызывающим. Токен не логируется
и не попадает в вывод.
"""

from __future__ import annotations

import re
from typing import Awaitable  # noqa: F401 (стиль единообразен с file_diff)

import httpx

import github_watcher
import project_files

MAX_ISSUES = 3             # сколько issue показываем в ответе
LIST_ISSUES = 10           # сколько issue в списке «какие задачи созданы»
ISSUES_HEADER = "По вашей проблеме есть созданные issue:"
LIST_HEADER = "Созданные issue (свежие сверху):"
SEARCH_PER_PAGE = 20       # выдача Search API для фильтрации релевантности
SEARCH_TIMEOUT = 30.0      # сек: таймаут Search API
BODY_SNIPPET_CHARS = 2000  # сколько тела issue участвует в матче стемов
FILE_ITEM_CAP = 6000       # симв. одного локального файла в промпте
FILE_BLOCK_CAP = 12_000    # симв. на весь добор-блок файлов


DIAGNOSE_SYSTEM = (
    "Ты — бот «Штурман», диагност проекта. Пользователь спрашивает, почему "
    "не работает функция/функционал. Известных issue по этой проблеме на "
    "GitHub не найдено; ниже — документы проекта (возможно, и файлы "
    "исходников). Проанализируй их и выскажи предполагаемый вердикт: "
    "наиболее вероятная причина сбоя и что проверить. Ответ на русском, "
    "строго в формате:\n"
    "Вердикт: 1–3 предложения — наиболее вероятная причина (со ссылкой на "
    "документ или файл: раздел).\n"
    "Проверить: 2–4 пункта — конкретные шаги диагностики.\n"
    "Опирайся только на приведённые материалы; если их недостаточно для "
    "даже предположения, последней строкой ответа выведи ровно одну строку "
    "вида <file>путь/от/корня/проекта</file> с путём к самому релевантному "
    "файлу исходников — его содержимое приложат и попросят перегенерировать "
    "вердикт. Причины не выдумывай. Ссылайся на источники ПУТЁМ ФАЙЛА "
    "(например: «см. frontend/AGENTS.md»), а не «документ N» — номера "
    "документов пользователю ничего не говорят.\n/no_think"
)


# ---------------------------------------------------------------------------
# Чистые функции (без сети) — покрыты inline-смоками
# ---------------------------------------------------------------------------

# Служебные слова вопроса, не несущие темы (в поиск и матч не идут).
# Сюда же — шаблон сбоя («не работает», «ошибка»…): он есть в ЛЮБОМ вопросе
# интента и в большинстве заголовков issue, поэтому без дискриминации —
# с ним любой issue «Не работает X» ложно релевантен.
_SKIP_TOKENS = {
    "почему", "это", "не", "нет", "никак", "вообще", "больше", "снова",
    "опять", "бот", "бота", "штурман", "пожалуйста", "мне", "меня", "для",
    "при", "что", "как", "уже", "ещё", "еще", "the", "and",
    "работает", "функционирует", "запускается", "стартует", "отвечает",
    "собирается", "компилируется", "подключается", "падает", "падают",
    "ломается", "виснет", "умирает", "выдаёт", "выдает", "даёт", "дает",
    "ошибка", "ошибку", "ошибки", "ошибка",
}


def extract_topic(question: str) -> str:
    """Тема сбоя: вопрос без вводного «почему …».

    «почему не работает импорт preferences» → «не работает импорт preferences».
    Шаблонные слова сбоя («не работает» и т.п.) СОХРАНЯЕМ: issue-заголовки
    обычно звучат так же, а для точности выдачи они не мешают (pick_issues
    матчит только значимые токены). Если после срезов пусто — вопрос целиком.
    """
    q = (question or "").strip().rstrip("?.!").strip()
    m = re.search(r"(?i)\bпочему\b", q)
    if m:
        q = q[m.end():]
    q = q.strip(" \t\n-–—:;,.,").strip()
    return q or (question or "").strip()


def topic_tokens(topic: str) -> list[str]:
    """Значимые токены темы (длина ≥3, без служебных), до 8 штук."""
    raw = re.findall(r"[a-zA-Zа-яёА-ЯЁ0-9_.\-]+", topic or "")
    out = []
    for t in raw:
        t = t.strip(".-").lower()
        if len(t) >= 3 and t not in _SKIP_TOKENS:
            out.append(t)
    return out[:8]


def _stem(token: str) -> str:
    """Грубый стем для русской морфологии: срез падежного окончания."""
    return token[: max(4, len(token) - 2)]


def _item_number(it: dict) -> int:
    try:
        return int(it.get("number") or 0)
    except (TypeError, ValueError):
        return 0


def pick_issues(items: list[dict], question: str, max_n: int = MAX_ISSUES) -> list[dict]:
    """Релевантные issue из сырой выдачи Search API / REST-списка.

    Матч: стемы токенов вопроса встречаются в названии или теле (≥1).
    Ранг: больше совпавших токенов → открытые раньше закрытых → свежее.
    Дубль по номеру (REST и Search могут вернуть один issue) — один раз.
    Возвращает [{number, title, url, state}].
    """
    toks = topic_tokens(question)
    if not toks:
        return []
    scored: list[tuple] = []
    seen: set[int] = set()
    for it in items or []:
        if not isinstance(it, dict) or it.get("pull_request"):
            continue
        num = _item_number(it)
        if num and num in seen:
            continue
        title = str(it.get("title") or "")
        body = str(it.get("body") or "")[:BODY_SNIPPET_CHARS].lower()
        hay = title.lower() + "\n" + body
        hits = {t for t in toks if _stem(t) in hay}
        if not hits:
            continue
        if num:
            seen.add(num)
        state = str(it.get("state") or "")
        scored.append((
            -len(hits),
            0 if state == "open" else 1,
            -num,
            it,
        ))
    scored.sort(key=lambda r: r[:3])
    out: list[dict] = []
    for *_, it in scored[:max_n]:
        out.append({
            "number": int(it.get("number") or 0),
            "title": str(it.get("title") or "").strip(),
            "url": str(it.get("html_url") or ""),
            "state": str(it.get("state") or ""),
        })
    return out


def _md_link(text: str, url: str) -> str:
    """Markdown-ссылка с чистым текстом (скобки в названии issue вырезаем)."""
    clean = re.sub(r"[\[\]()]", "", text or "").strip() or "issue"
    return f"[{clean}]({url})"


def format_issues(issues: list[dict], header: str = ISSUES_HEADER) -> str:
    """Сообщение чата: список найденных issue (название, состояние, ссылка)."""
    lines = [header]
    for it in issues or []:
        state = "открыт" if it.get("state") == "open" else "закрыт"
        title = str(it.get("title") or "").strip()
        # html_url — веб-ссылка (github.com); url — API-ссылка (api.github.com).
        link = _md_link(f"#{it.get('number')} {title}",
                        str(it.get("html_url") or it.get("url") or ""))
        lines.append(f"- {link} ({state})")
    return "\n".join(lines)


def files_block(paths: list[str], total_cap: int = FILE_BLOCK_CAP) -> str:
    """Блок содержимого локальных файлов для перегенерации вердикта.

    Каждый путь резолвится через project_files (containment, без симлинк-
    ловушек); бинарные/нечитаемые/нерезолвимые пропускаются. Общий объём
    ограничен ``total_cap``.
    """
    parts: list[str] = []
    used = 0
    for req in paths or []:
        rel = project_files.resolve_request(req)
        if not rel:
            continue
        text = project_files.read_text(rel) or ""
        if not text:
            continue
        if len(text) > FILE_ITEM_CAP:
            text = text[:FILE_ITEM_CAP] + "\n… (обрезано)"
        if used + len(text) > total_cap:
            break
        used += len(text)
        parts.append(f"\n\n(Локальный файл проекта {rel}:\n```text\n{text}\n```)")
    return "".join(parts)


# ---------------------------------------------------------------------------
# GitHub Search API (read-only)
# ---------------------------------------------------------------------------

async def recent_issues(
    token: str, repos: list[str], per_page: int = 50
) -> list[dict]:
    """Свежие issue (state=all) через REST — первоисточник поиска.

    Search API индексирует новые issue с задержкой (минуты) и не стеммит
    русскую морфологию («сохранением» ≠ «сохранение»), поэтому список
    последних issue репозитория, отфильтрованный локально по стемам
    (pick_issues), находит то, что Search API пропускает. PR вырезаются.
    HTTP-ошибки пробрасываются (вызывающий решает фолбэк). Токен не логируется.
    """
    if not token or not repos:
        return []
    headers = {**github_watcher._HEADERS, "Authorization": f"Bearer {token}"}
    items: list[dict] = []
    async with httpx.AsyncClient(timeout=SEARCH_TIMEOUT, headers=headers) as client:
        for r in repos:
            if not r.strip():
                continue
            resp = await client.get(
                f"{github_watcher.GITHUB_API}/repos/{r.strip()}/issues",
                params={"state": "all", "per_page": per_page,
                        "sort": "created", "direction": "desc"},
            )
            resp.raise_for_status()
            data = resp.json()
            if isinstance(data, list):
                items.extend(i for i in data
                             if isinstance(i, dict) and not i.get("pull_request"))
    return items


async def search_issues(
    token: str, repos: list[str], topic: str
) -> list[dict]:
    """Сырая выдача Search API по теме во всех репо секции ``github``.

    Пустой список — ничего не найдено; HTTP-ошибки пробрасываются
    (вызывающий решает фолбэк). Токен не логируется.
    """
    clean = (topic or "").strip()
    if not clean or not repos:
        return []
    query = " ".join([f"repo:{r}" for r in repos if r.strip()] + ["is:issue", clean])
    headers = {**github_watcher._HEADERS, "Authorization": f"Bearer {token}"}
    async with httpx.AsyncClient(timeout=SEARCH_TIMEOUT, headers=headers) as client:
        resp = await client.get(
            f"{github_watcher.GITHUB_API}/search/issues",
            params={"q": query, "per_page": SEARCH_PER_PAGE},
        )
        resp.raise_for_status()
        data = resp.json()
    items = data.get("items") if isinstance(data, dict) else None
    return items if isinstance(items, list) else []
