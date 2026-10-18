"""Дифф присланных файлов против ветки main GitHub-репозитория.

Пользователь присылает боту документы (1–5). Для каждого берётся имя файла,
в дереве ``main`` (git trees API, кеш 300 с) ищется одноимённый файл,
содержимое скачивается (``GET /contents/{path}?ref=<ref>``, raw) и считается
локальный unified-дифф (difflib). Диффы уходят в чат готовым HTML
(``render_diff_html`` из pr_review — зачёркнутые удалённые, курсивные
добавленные), затем LLM выдаёт вердикт в стиле PR-ревью
(Резюме / Проблемы / Вердикт).

Диффы всегда считает код: LLM только интерпретирует их.

Реф по умолчанию — ``main``; override — env ``FILEDIFF_REF``.
Токен — из секции ``github`` config.json / env (github_watcher.load_settings).
Токен не логируется и не попадает в вывод.
"""

from __future__ import annotations

import difflib
import html
import logging
import os
import re
import time
from typing import Awaitable, Callable

import httpx

import github_watcher
import pr_review

log = logging.getLogger("file_diff")

MAX_FILES = 5
TOO_MANY_MSG = "Пожалуйста, отправьте не более 5 файлов за раз."
DEFAULT_REF = "main"
SEND_DIFF_CAP = 6000   # симв. диффа одного файла в сообщении чата
PROMPT_DIFF_CAP = 8000  # симв. диффа одного файла в промпте LLM
TREE_TTL = 300.0        # сек: кеш дерева репозитория
HTTP_TIMEOUT = 60.0

# -----------------------------------------------------------------------
# Чистые функции (без сети) — покрыты inline-смоками
# -----------------------------------------------------------------------

def match_paths(tree_paths: list[str], filename: str) -> list[str]:
    """Одноимённые пути в дереве: сначала точное совпадение полного пути,
    затем по имени файла (без учёта регистра)."""
    name = (filename or "").strip().strip("/")
    if not name or not tree_paths:
        return []
    direct = [p for p in tree_paths if p.strip("/") == name]
    if direct:
        return sorted(direct, key=len)
    base = name.rsplit("/", 1)[-1].lower()
    hits = [p for p in tree_paths if p.rsplit("/", 1)[-1].lower() == base]
    return sorted(hits, key=lambda p: (len(p), p))


def looks_binary(raw: bytes) -> bool:
    """Грубая детекция бинарного содержимого (NUL-байт в первых 4 КБ)."""
    return b"\x00" in (raw or b"")[:4096]


def unified_diff_text(base: str, sent: str, a_label: str, b_label: str) -> str:
    """Unified-дифф main -> присланный файл без заголовков ---/+++.

    Строки с '+' есть в присланном файле, с '−' — в версии main.
    Пустая строка — содержимое идентично.
    """
    diff = "".join(difflib.unified_diff(
        base.splitlines(keepends=True),
        sent.splitlines(keepends=True),
        fromfile=a_label,
        tofile=b_label,
    ))
    lines = [
        ln for ln in diff.splitlines()
        if not ln.startswith("--- ") and not ln.startswith("+++ ")
    ]
    return "\n".join(lines).rstrip("\n")


def diff_stats(diff: str) -> tuple[int, int]:
    """(+добавлено, −удалено) по строкам диффа."""
    add = sum(1 for ln in diff.splitlines() if ln.startswith("+") and not ln.startswith("+++"))
    rem = sum(1 for ln in diff.splitlines() if ln.startswith("-") and not ln.startswith("---"))
    return add, rem


def _cap(text: str, cap: int) -> str:
    if len(text) <= cap:
        return text
    return text[:cap] + f"\n… [дифф показан не полностью ({cap} симв.)]"


def too_many_message(n: int) -> str:
    return TOO_MANY_MSG


FILEDIFF_SYSTEM = (
    "Ты — строгий ревьюер изменений проекта «Штурман» (репозиторий {repo}). "
    "Пользователь прислал один или несколько файлов; ниже для каждого — "
    "дифф между версией в ветке {ref} и присланным файлом: строки с '+' "
    "есть в присланном файле (добавлены или изменены), строки с '−' есть в "
    "версии {ref}, но в присланном файле отсутствуют.\n"
    "Проверь:\n"
    "1) корректность присланного: баги, ошибки логики/синтаксиса;\n"
    "2) риск потери: не пропала ли важная функциональность версии {ref};\n"
    "3) целостность: файл самодостаточен, ничего не обрезано случайно.\n"
    "Ответ на русском, строго в формате:\n"
    "Резюме: 1–3 предложения по всем файлам.\n"
    "Проблемы: нумерованный список по убыванию важности (файл:строка — "
    "суть). Если проблем нет — «Не обнаружено».\n"
    "Вердикт: «ОК» или «Требует правок».\n/no_think"
)


def build_prompt(repo: str, ref: str, items: list[dict]) -> str:
    """Пользовательская часть промпта вердикта: перечисление файлов и диффов.

    items — записи handle: {name, path, status, diff, caption}.
    """
    parts = [f"Репозиторий: {repo}, ветка сравнения: {ref}", ""]
    for it in items:
        status = it.get("status")
        name = it.get("name") or "?"
        if status == "not_found":
            parts.append(f"=== ФАЙЛ: {name} — одноимённый файл в {ref} не найден ===")
        elif status == "binary":
            parts.append(f"=== ФАЙЛ: {name} (в {ref}: {it['path']}) — бинарный, "
                         "текстовый дифф недоступен ===")
        elif status == "identical":
            continue  # идентичные файлы в вердикте не участвуют
        else:
            add, rem = diff_stats(it.get("diff") or "")
            parts.append(f"=== ФАЙЛ: {name} (в {ref}: {it.get('path')}) "
                         f"(+{add}/-{rem}) ===")
            parts.append(_cap(it.get("diff") or "", PROMPT_DIFF_CAP))
        caption = (it.get("caption") or "").strip()
        if caption:
            parts.append(f"Комментарий пользователя к файлу: {caption}")
        parts.append("")
    return "\n".join(parts).strip()


# -----------------------------------------------------------------------
# GitHub API (read-only)
# -----------------------------------------------------------------------

_tree_cache: dict[tuple[str, str], tuple[float, list[str]]] = {}


async def fetch_tree_paths(client: httpx.AsyncClient, repo: str, ref: str) -> list[str]:
    """Все blob-пути дерева ref (кеш TREE_TTL секунд на (repo, ref))."""
    key = (repo, ref)
    cached = _tree_cache.get(key)
    now = time.monotonic()
    if cached and now - cached[0] < TREE_TTL:
        return cached[1]
    resp = await client.get(
        f"{github_watcher.GITHUB_API}/repos/{repo}/git/trees/{ref}",
        params={"recursive": "1"},
    )
    resp.raise_for_status()
    data = resp.json()
    paths = [it["path"] for it in data.get("tree") or []
             if it.get("type") == "blob" and it.get("path")]
    _tree_cache[key] = (now, paths)
    return paths


async def fetch_ref_file(
    client: httpx.AsyncClient, repo: str, path: str, ref: str
) -> str | None:
    """Содержимое файла из ref (raw); None — если файла нет."""
    url = f"{github_watcher.GITHUB_API}/repos/{repo}/contents/{path}"
    resp = await client.get(url, params={"ref": ref},
                            headers={"Accept": "application/vnd.github.raw+json"})
    if resp.status_code == 404:
        return None
    resp.raise_for_status()
    return resp.text


# -----------------------------------------------------------------------
# Оркестрация полного потока
# -----------------------------------------------------------------------

async def handle_documents(
    files: list[dict],
    *,
    chat,
    repo: str,
    ref: str,
    token: str,
    send_text: Callable,
    send_html: Callable,
    llm_generate: Callable,
    model_name: str = "",
) -> None:
    """files: [{name, raw, caption}] (raw — bytes из Telegram).

    Отправляет в ``chat``: по файлу — статус/дифф, затем handoff и вердикт LLM.
    Ошибки сети/API не глушатся целиком: статус файла ставится best-effort,
    а финальный сбой уходит вызывающему (хендлер бота отправит «Что-то пошло
    не так»).
    """
    headers = {**github_watcher._HEADERS, "Authorization": f"Bearer {token}"}
    items: list[dict] = []
    async with httpx.AsyncClient(timeout=HTTP_TIMEOUT, headers=headers) as client:
        tree = await fetch_tree_paths(client, repo, ref)
        for f in files:
            name = str(f.get("name") or "файл")
            caption = str(f.get("caption") or "")
            raw = f.get("raw") or b""
            matches = match_paths(tree, name)
            if not matches:
                items.append({"name": name, "path": None, "status": "not_found",
                              "diff": "", "caption": caption})
                continue
            path = matches[0]
            if looks_binary(raw):
                items.append({"name": name, "path": path, "status": "binary",
                              "diff": "", "caption": caption})
                continue
            sent_text = raw.decode("utf-8", errors="replace")
            base_text = await fetch_ref_file(client, repo, path, ref)
            if base_text is None:
                items.append({"name": name, "path": path, "status": "not_found",
                              "diff": "", "caption": caption})
                continue
            diff = unified_diff_text(base_text, sent_text, f"a/{path}", f"b/{name}")
            items.append({
                "name": name, "path": path,
                "status": "identical" if not diff else "ok",
                "diff": diff, "caption": caption,
            })

    # 1) по файлу — статус или дифф (готовый HTML, как диффы PR)
    diff_sender = send_html or send_text
    for it in items:
        name_html = html.escape(it["name"])
        status = it["status"]
        if status == "not_found":
            body = (f"📄 <b>{name_html}</b> — одноимённый файл в "
                    f"{html.escape(repo)}@{html.escape(ref)} не найден")
        elif status == "binary":
            body = (f"📄 <b>{name_html}</b> (в {ref}: {html.escape(it['path'])}) — "
                    "бинарный файл, текстовый дифф недоступен")
        elif status == "identical":
            body = (f"📄 <b>{name_html}</b> — совпадает с {html.escape(repo)}@{ref} "
                    f"({html.escape(it['path'])}), изменений нет")
        else:
            add, rem = diff_stats(it["diff"])
            body = (f"📄 <b>{name_html}</b> (в {ref}: {html.escape(it['path'])}) "
                    f"(+{add}/-{rem})\n{pr_review.render_diff_html(_cap(it['diff'], SEND_DIFF_CAP))}")
        await diff_sender(chat, body)

    # 2) сопровождающий текст + 3) вердикт LLM
    comparable = [it for it in items if it["status"] in ("ok", "binary", "not_found")]
    if comparable:
        model_part = f" для {model_name}" if model_name else ""
        await send_text(
            chat,
            f"📎 Диффы выше — сравнение с {repo}@{ref}. "
            f"Передаю на обработку{model_part}.",
        )
        prompt = build_prompt(repo, ref, comparable)
        content, _ = await llm_generate(
            FILEDIFF_SYSTEM.format(repo=repo, ref=ref), prompt, []
        )
        verdict = (content or "").strip() or "Модель не вернула вердикт (пустой ответ)."
        await send_text(chat, f"🧠 Сверка с {repo}@{ref}:\n\n{verdict}")
    else:
        await send_text(chat, f"Все присланные файлы совпадают с {repo}@{ref} — "
                              "различий нет, вердикт не требуется.")


def reset_state() -> None:
    """Сброс in-memory кешей (для проверок)."""
    _tree_cache.clear()
