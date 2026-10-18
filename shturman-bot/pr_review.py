"""AI-ревью PR для «Штурмана»: диффы + инструкции проекта -> локальная LLM.

Запускается наблюдателем (`github_watcher`) при появлении нового PR. Поток:
  1) уведомление админам в личку: найден PR + ссылка;
  2) список файлов PR -> текстовые диффы; каждый файл — отдельным сообщением
     в личку админам с подсветкой: удалённые строки зачёркнуты (<s>),
     добавленные курсивом (<i>), контекст — моноширинный (<code>);
  3) AGENTS.md / SKILL.md из ветки PR (если есть) — документами;
  4) сопровождающий текст «…передаю на обработку для <модель>»;
  5) LLM-ревью: диффы + описание PR + AGENTS.md/SKILL.md -> вердикт админам.

Админы: env ``BOT_ADMIN_USERNAMES`` (логины или числовые id). Логины
резолвятся по таблице ``bot_users`` — Telegram-бот не может писать
пользователю, который не начинал с ним диалог; нерезолвленные логируются.

Ограничения контекста LLM: кап на дифф файла и на документ инструкции
(env ``PR_PATCH_CAP`` / ``PR_DOC_CAP``, по умолчанию 8000/6000 симв.) —
при обрезке в промпте ставится явная пометка.
"""

from __future__ import annotations

import asyncio
import html
import logging
import os
from typing import Awaitable, Callable, Iterable

import httpx

import github_watcher

log = logging.getLogger("pr_review")

DEFAULT_PATCH_CAP = 8000   # симв. текстового диффа одного файла в промпте
DEFAULT_DOC_CAP = 6000     # симв. AGENTS.md / SKILL.md в промпте
PR_FILES_PER_PAGE = 50     # файлов на запрос (лимит GitHub API — 3000)
HTTP_TIMEOUT = 60.0

DocFetcher = Callable[[str, str], Awaitable[str | None]]  # (path, sha) -> content


def _env_int(name: str, default: int) -> int:
    try:
        return int((os.environ.get(name) or "").strip() or default)
    except ValueError:
        return default


# ---------------------------------------------------------------------------
# Чистые функции: подсветка диффа, сборка промпта, резолв админов
# ---------------------------------------------------------------------------

def render_diff_html(patch: str) -> str:
    """Текстовый patch -> Telegram HTML: удалённые <s>, добавленные <i>.

    Строки-заголовки (@@) выделяются жирным, остальной контекст — <code>.
    HTML экранируется до разметки.
    """
    out: list[str] = []
    for line in patch.splitlines() or [""]:
        esc = html.escape(line, quote=False) or " "
        if line.startswith("@@"):
            out.append(f"<b>{esc}</b>")
        elif line.startswith("-"):
            out.append(f"<s>{esc}</s>")
        elif line.startswith("+"):
            out.append(f"<i>{esc}</i>")
        else:
            out.append(f"<code>{esc}</code>")
    return "\n".join(out)


def _cap_block(text: str, cap: int, what: str) -> str:
    if len(text) <= cap:
        return text
    return text[:cap] + f"\n… [обрезано: {what} показан(ы) не полностью ({cap} симв.)]"


def build_review_prompt(
    pr: dict,
    patches: list[dict],
    docs: dict[str, str],
    patch_cap: int = DEFAULT_PATCH_CAP,
    doc_cap: int = DEFAULT_DOC_CAP,
) -> str:
    """Пользовательская часть промпта ревью: описание PR + диффы + документы."""
    parts: list[str] = [
        f"PR {pr['repo']}#{pr['number']}: «{pr['title']}»",
        f"Автор: {pr['author']}; ветка {pr['branch']} → {pr['base']}",
        f"Описание: {pr.get('body') or '(нет)'}",
        "",
    ]
    for p in patches:
        if not p.get("patch"):
            parts.append(f"=== ФАЙЛ: {p['filename']} — текстовый дифф недоступен "
                         f"(бинарный/переименование) ===")
            continue
        add, rem = p.get("additions", "?"), p.get("deletions", "?")
        parts.append(f"=== ФАЙЛ: {p['filename']} (+{add}/-{rem}) ===")
        parts.append(_cap_block(p["patch"], patch_cap, f"дифф {p['filename']}"))
        parts.append("")
    for name in ("AGENTS.md", "SKILL.md"):
        if name in docs:
            parts.append(f"=== {name} (инструкции проекта, ветка PR) ===")
            parts.append(_cap_block(docs[name], doc_cap, name))
            parts.append("")
    return "\n".join(parts)


REVIEW_SYSTEM = (
    "Ты — строгий ревьюер кода. Тебе дают описание PR, текстовые диффы "
    "изменённых файлов и инструкции проекта (AGENTS.md/SKILL.md), если есть.\n"
    "Проверь:\n"
    "1) корректность изменений: баги, ошибки логики/типов, сломанные контракты;\n"
    "2) полноту выполнения: решает ли PR заявленную задачу из описания, "
    "ничего не упущено;\n"
    "3) соответствие инструкциям AGENTS.md/SKILL.md (конвенции, запреты, стиль).\n"
    "Ответ на русском, строго в формате:\n"
    "Резюме: 1–2 предложения.\n"
    "Проблемы: нумерованный список по убыванию важности (файл:строка — суть). "
    "Если проблем нет — «Не обнаружено».\n"
    "Соответствие инструкциям: коротко, со ссылкой на конкретные пункты, "
    "если применимо; если инструкций нет — так и напиши.\n"
    "Вердикт: «ОК» или «Требует правок»."
)


def resolve_admin_ids(admin_names: Iterable[str], known_users: dict[str, int]) -> list[int]:
    """BOT_ADMIN_USERNAMES -> chat_id: числа напрямую, логины по bot_users."""
    ids: list[int] = []
    for raw in admin_names:
        name = raw.strip().lstrip("@")
        if not name:
            continue
        if name.lstrip("-").isdigit():
            ids.append(int(name))
        elif (uid := known_users.get(name.lower())) is not None:
            ids.append(int(uid))
        else:
            log.warning("pr-review: админ %s не найден в bot_users — бот не может "
                        "писать ему в личку (нужен первый диалог с ботом)", name)
    return list(dict.fromkeys(ids))  # без дублей, порядок сохранён


def summary_text(pr: dict) -> str:
    """Первое сообщение админам: найден PR + ссылка."""
    return (
        f"🔎 Найден новый PR в {pr['repo']}: "
        f"[#{pr['number']} «{pr['title']}»]({pr['url']})\n"
        f"Автор: {pr['author']} · ветка {pr['branch']} → {pr['base']}"
    )


def handoff_text(docs_found: list[str], model_name: str) -> str:
    """Сопроводительный текст перед передачей в LLM."""
    docs_part = (f"Обнаружены {' и '.join(docs_found)} — " if docs_found
                 else "AGENTS.md/SKILL.md не обнаружены — ")
    return f"📎 Состав PR с дифами выше. {docs_part}передаю на обработку для {model_name}."


# ---------------------------------------------------------------------------
# GitHub API: файлы PR + инструкции из ветки
# ---------------------------------------------------------------------------

async def fetch_pr_files(client: httpx.AsyncClient, repo: str, number: int) -> list[dict]:
    """GET /pulls/{n}/files -> нормализованные записи {filename, patch, …}."""
    url = f"{github_watcher.GITHUB_API}/repos/{repo}/pulls/{number}/files"
    resp = await client.get(url, params={"per_page": PR_FILES_PER_PAGE})
    resp.raise_for_status()
    out: list[dict] = []
    for it in resp.json() or []:
        out.append({
            "filename": str(it.get("filename") or "?"),
            "patch": str(it.get("patch") or ""),
            "additions": it.get("additions", 0),
            "deletions": it.get("deletions", 0),
            "changes": it.get("changes", 0),
        })
    return out


async def fetch_branch_doc(
    client: httpx.AsyncClient, repo: str, path: str, ref: str
) -> str | None:
    """Содержимое файла из ветки PR (raw), None — если файла нет."""
    url = f"{github_watcher.GITHUB_API}/repos/{repo}/contents/{path}"
    resp = await client.get(url, params={"ref": ref},
                            headers={"Accept": "application/vnd.github.raw+json"})
    if resp.status_code == 404:
        return None
    resp.raise_for_status()
    return resp.text


# ---------------------------------------------------------------------------
# Оркестрация: полный поток от найденного PR до вердикта LLM
# ---------------------------------------------------------------------------

def make_hook(
    store,
    send_text: Callable,
    send_doc: Callable,
    llm_generate: Callable,
    get_admin_names: Callable[[], Iterable[str]],
    model_name: str,
    settings: github_watcher.Settings | None = None,
    send_html: Callable | None = None,
) -> Callable[[dict], Awaitable[None]]:
    """Хук для github_watcher: (pr) -> весь поток ревью. Ошибки не роняют вотчер.

    ``send_html`` — отправитель готового Telegram-HTML (диффы с подсветкой);
    None -> диффы идут через ``send_text`` (подсветка будет видна тегами).
    """

    async def hook(pr: dict) -> None:
        try:
            await handle_new_pr(
                pr, store=store, send_text=send_text, send_doc=send_doc,
                llm_generate=llm_generate, get_admin_names=get_admin_names,
                model_name=model_name, settings=settings, send_html=send_html,
            )
        except Exception as exc:  # noqa: BLE001 - ревью не должно ронять вотчер
            log.error("pr-review: поток упал на PR %s#%s: %s",
                      pr.get("repo"), pr.get("number"), type(exc).__name__)

    return hook


async def handle_new_pr(
    pr: dict,
    *,
    store,
    send_text: Callable,
    send_doc: Callable,
    llm_generate: Callable,
    get_admin_names: Callable[[], Iterable[str]],
    model_name: str,
    settings: github_watcher.Settings | None = None,
    send_html: Callable | None = None,
) -> None:
    """Уведомить админов, разослать диффы и инструкции, получить вердикт LLM."""
    s = settings or github_watcher.load_settings()
    try:
        known = await store.known_users_map()
    except Exception:  # noqa: BLE001 - БД не должна блокировать ревью
        known = {}
    admin_ids = resolve_admin_ids(get_admin_names(), known)
    if not admin_ids:
        log.warning("pr-review: нет ни одного адресуемого админа — ревью выполнится, "
                    "но уведомления не уйдут")
    log.info("pr-review: PR %s#%s от %s; админы: %s",
             pr["repo"], pr["number"], pr["author"],
             admin_ids or ["(не найдены)"])

    # 1) уведомление о найденном PR
    for chat_id in admin_ids:
        try:
            await send_text(chat_id, summary_text(pr))
        except Exception as exc:  # noqa: BLE001
            log.warning("pr-review: уведомление %s не ушло: %s", chat_id, type(exc).__name__)

    patch_cap = _env_int("PR_PATCH_CAP", DEFAULT_PATCH_CAP)
    doc_cap = _env_int("PR_DOC_CAP", DEFAULT_DOC_CAP)

    headers = {**github_watcher._HEADERS, "Authorization": f"Bearer {s.token}"}
    async with httpx.AsyncClient(timeout=HTTP_TIMEOUT, headers=headers) as client:
        patches = await fetch_pr_files(client, pr["repo"], pr["number"])
        docs: dict[str, str] = {}
        for doc_name in ("AGENTS.md", "SKILL.md"):
            content = await fetch_branch_doc(client, pr["repo"], doc_name, pr["head_sha"])
            if content:
                docs[doc_name] = content

    # 2) каждый файл с диффом — отдельным сообщением (готовый HTML без
    #    markdown-конвертации, иначе теги подсветки экранируются)
    diff_sender = send_html or send_text
    for p in patches:
        if p["patch"]:
            body = f"📄 <b>{html.escape(p['filename'])}</b> " \
                   f"(+{p['additions']}/-{p['deletions']})\n{render_diff_html(p['patch'])}"
        else:
            body = f"📄 <b>{html.escape(p['filename'])}</b> — текстовый дифф недоступен " \
                   "(бинарный файл или переименование)"
        for chat_id in admin_ids:
            try:
                await diff_sender(chat_id, body)
            except Exception as exc:  # noqa: BLE001
                log.warning("pr-review: дифф %s админу %s не ушёл: %s",
                            p["filename"], chat_id, type(exc).__name__)

    # 3) AGENTS.md / SKILL.md документами
    for doc_name, content in docs.items():
        for chat_id in admin_ids:
            try:
                await send_doc(chat_id, doc_name, content)
            except Exception as exc:  # noqa: BLE001
                log.warning("pr-review: документ %s админу %s не ушёл: %s",
                            doc_name, chat_id, type(exc).__name__)

    # 4) сопровождающий текст
    for chat_id in admin_ids:
        try:
            await send_text(chat_id, handoff_text(list(docs.keys()), model_name))
        except Exception as exc:  # noqa: BLE001
            log.warning("pr-review: сопроводительный текст %s не ушёл: %s",
                        chat_id, type(exc).__name__)

    # 5) LLM-ревью
    prompt = build_review_prompt(pr, patches, docs, patch_cap, doc_cap)
    content, _ = await llm_generate(REVIEW_SYSTEM, prompt, [])
    log.info("pr-review: вердикт по %s#%s получен (%d симв.)",
             pr["repo"], pr["number"], len(content or ""))
    verdict = content.strip() or "Модель не вернула вердикт (пустой ответ)."
    for chat_id in admin_ids:
        try:
            await send_text(chat_id,
                            f"🧠 Ревью PR [{pr['repo']}#{pr['number']}]({pr['url']}):\n\n"
                            + verdict)
        except Exception as exc:  # noqa: BLE001
            log.warning("pr-review: вердикт админу %s не ушёл: %s",
                        chat_id, type(exc).__name__)
