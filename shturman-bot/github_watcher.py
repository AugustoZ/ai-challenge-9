"""Наблюдатель GitHub PR для «Штурмана»: опрос открытых PR по расписанию.

Раз в ``interval_sec`` (по умолчанию 30 c) опрашивает REST API GitHub
(``GET /repos/{owner}/{repo}/pulls?state=open&sort=created``) и:
  - новые PR (нет в таблице ``github_prs``) записывает в БД и шлёт
    уведомление в настроенные чаты Telegram;
  - держит в памяти короткий список недавних PR: ``context_block()``
    подмешивается bot_responder'ом в system-промпт обычных ответов, чтобы
    локальная LLM «знала» про PR и могла отвечать на вопросы о них.

Первый проход после старта — базовый: открытые PR фиксируются в БД БЕЗ
уведомления (иначе каждый рестарт бота спамит старыми PR). Обнаруживаются
только «создание» и всё, что открыто в момент опроса: закрытый между
опросами PR уведомления не получит (осознанное ограничение поллера).

Конфиг — секция ``github`` в config.json (см. config.example.json):

    "github": {
      "token": "ghp_...",
      "repos": ["AugustoZ/ai-challenge-9"],
      "chats": ["@tank_dargo_h6_chat"],
      "interval_sec": 30
    }

Env-override: ``GH_TOKEN`` / ``GITHUB_PERSONAL_ACCESS_TOKEN``,
``GITHUB_REPOS`` (через запятую), ``GH_CHATS``, ``GH_POLL_INTERVAL``.
Токен не логируется и не попадает в вывод.
"""

from __future__ import annotations

import asyncio
import logging
import os
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Iterable

import httpx

log = logging.getLogger("github_watcher")

GITHUB_API = "https://api.github.com"
DEFAULT_INTERVAL_SEC = 30.0
BACKOFF_MAX = 300.0          # сек: максимум бэкоффа при ошибках API
PER_PAGE = 20                # открытых PR за запрос достаточно для дедупа
RECENT_CAP = 12              # сколько PR держим в памяти для контекста LLM
HTTP_TIMEOUT = 30.0

_REPO_ROOT = os.path.dirname(os.path.abspath(__file__))
_CONFIG_PATH = os.path.join(_REPO_ROOT, "config.json")

_HEADERS = {
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "shturman-bot",
}

# Недавние PR (нормализованные записи) — источник блока контекста для LLM.
_recent: list[dict] = []


# ---------------------------------------------------------------------------
# Конфигурация
# ---------------------------------------------------------------------------

@dataclass
class Settings:
    token: str = ""
    repos: list[str] = field(default_factory=list)
    chats: list[str] = field(default_factory=list)
    interval_sec: float = DEFAULT_INTERVAL_SEC


def _env_str(name: str) -> str:
    return (os.environ.get(name) or "").strip()


def _split_csv(raw: str) -> list[str]:
    return [part.strip() for part in raw.split(",") if part.strip()]


def load_settings(
    config_path: str = _CONFIG_PATH,
    default_chats: Iterable[str] = (),
) -> Settings:
    """Секция ``github`` из config.json + env-override (env сильнее)."""
    gh: dict = {}
    try:
        import json
        with open(config_path, "r", encoding="utf-8") as f:
            gh = json.load(f).get("github") or {}
    except (OSError, ValueError):
        pass
    if not isinstance(gh, dict):
        gh = {}

    token = _env_str("GH_TOKEN") or _env_str("GITHUB_PERSONAL_ACCESS_TOKEN")
    if not token:
        token = str(gh.get("token") or "").strip()

    repos_env = _split_csv(_env_str("GITHUB_REPOS"))
    repos = repos_env or [str(r).strip() for r in gh.get("repos") or [] if str(r).strip()]

    chats_env = _split_csv(_env_str("GH_CHATS"))
    chats = chats_env or [str(c).strip() for c in gh.get("chats") or [] if str(c).strip()]
    if not chats:
        chats = [c for c in default_chats if c]

    raw_interval = _env_str("GH_POLL_INTERVAL") or str(gh.get("interval_sec") or "")
    try:
        interval = float(raw_interval) if raw_interval else DEFAULT_INTERVAL_SEC
    except ValueError:
        interval = DEFAULT_INTERVAL_SEC
    interval = max(interval, 5.0)  # ниже 5 c GitHub API не нужен и невежлив

    return Settings(token=token, repos=repos, chats=chats, interval_sec=interval)


# ---------------------------------------------------------------------------
# Чистые функции (разбор/дедуп/форматирование) — удобно проверять без сети
# ---------------------------------------------------------------------------

def parse_pulls(repo: str, items: list[dict]) -> list[dict]:
    """Ответ API -> нормализованные записи PR (новые первыми)."""
    out: list[dict] = []
    for it in items or []:
        try:
            out.append({
                "repo": repo,
                "number": int(it["number"]),
                "title": str(it.get("title") or "").strip() or "(без названия)",
                "author": str(((it.get("user") or {}).get("login")) or "?"),
                "url": str(it.get("html_url") or ""),
                "branch": str(((it.get("head") or {}).get("ref")) or "?"),
                "base": str(((it.get("base") or {}).get("ref")) or "?"),
                "created_at": str(it.get("created_at") or ""),
                "head_sha": str(((it.get("head") or {}).get("sha")) or ""),
                "body": str(it.get("body") or "").strip(),
            })
        except (KeyError, TypeError, ValueError):
            log.warning("github-наблюдатель: пропущен некорректный PR в ответе %s", repo)
    return out


def pick_new(prs: list[dict], known: set[tuple[str, int]]) -> list[dict]:
    """Только PR, которых нет в известном множестве (repo, number)."""
    return [p for p in prs if (p["repo"], p["number"]) not in known]


def format_message(pr: dict) -> str:
    """Уведомление в чат. Markdown-ссылку конвертирует _md_to_telegram_html."""
    return (
        f"🔧 Новый PR в {pr['repo']}: [#{pr['number']} «{pr['title']}»]({pr['url']})\n"
        f"Автор: {pr['author']} · ветка {pr['branch']} → {pr['base']}\n"
        f"Создан: {pr['created_at'].replace('T', ' ')[:16]} UTC"
    )


def update_recent(prs: list[dict]) -> None:
    """Обновляет in-memory список недавних PR (для context_block)."""
    global _recent
    by_key: dict[tuple[str, int], dict] = {(p["repo"], p["number"]): p for p in prs}
    merged = {**by_key, **{(p["repo"], p["number"]): p for p in _recent}}
    _recent = sorted(
        merged.values(), key=lambda p: p.get("created_at") or "", reverse=True
    )[:RECENT_CAP]


def recent() -> list[dict]:
    return list(_recent)


def context_block(prs: list[dict] | None = None, limit: int = 8) -> str:
    """Блок контекста для system-промпта; '' — если PR нет."""
    items = _recent if prs is None else prs
    if not items:
        return ""
    lines = [
        f"- {p['repo']}#{p['number']} «{p['title']}» — {p['author']}, "
        f"{(p.get('created_at') or '')[:10]}"
        for p in items[:limit]
    ]
    return (
        "\n\n[Служебный контекст: открытые PR на GitHub. Если вопрос касается "
        "PR, кода или репозитория — опирайся на этот список и не выдумывай "
        "номера PR:]\n" + "\n".join(lines)
    )


def chat_target(raw: str) -> str | int:
    """Строка чата -> chat_id для sendMessage (число или @username)."""
    raw = (raw or "").strip()
    if raw.lstrip("-").isdigit():
        return int(raw)
    return raw if raw.startswith("@") else f"@{raw}"


# ---------------------------------------------------------------------------
# Цикл наблюдателя
# ---------------------------------------------------------------------------

async def _fetch_open_pulls(client: httpx.AsyncClient, repo: str) -> list[dict]:
    url = f"{GITHUB_API}/repos/{repo}/pulls"
    resp = await client.get(
        url,
        params={"state": "open", "sort": "created", "direction": "desc",
                "per_page": PER_PAGE},
    )
    resp.raise_for_status()
    return parse_pulls(repo, resp.json())


async def watcher_loop(
    store,
    send_text: Callable,
    settings: Settings | None = None,
    default_chats: Iterable[str] = (),
    review_hook: Callable[[dict], Awaitable[None]] | None = None,
) -> None:
    """Долгоживущий цикл: опрос API -> дедуп по БД -> уведомления в чаты.

    ``send_text(chat_id, text)`` — отправитель Telegram (из bot_responder).
    ``review_hook(pr)`` — поток AI-ревью нового PR (pr_review.make_hook);
    запускается отдельной задачей и не блокирует цикл опроса.
    """
    s = settings or load_settings(default_chats=default_chats)
    if not s.token:
        log.info("github-наблюдатель: выключен (нет токена — секция github в config.json)")
        return
    if not s.repos:
        log.info("github-наблюдатель: выключен (список repos пуст)")
        return
    log.info("github-наблюдатель: репозитории %s, интервал %.0f c, чаты %s",
             s.repos, s.interval_sec, s.chats or ["(не заданы)"])

    try:
        known: set[tuple[str, int]] = await store.known_prs()
    except Exception as exc:  # noqa: BLE001 - БД не должна валить наблюдателя
        log.warning("github-наблюдатель: не прочитал известные PR (%s), старт с пустого",
                    type(exc).__name__)
        known = set()

    headers = {**_HEADERS, "Authorization": f"Bearer {s.token}"}
    async with httpx.AsyncClient(timeout=HTTP_TIMEOUT, headers=headers) as client:
        first = True
        backoff = 1.0
        while True:
            try:
                all_prs: list[dict] = []
                for repo in s.repos:
                    all_prs.extend(await _fetch_open_pulls(client, repo))

                new = pick_new(all_prs, known)
                notified = 0
                for pr in new:
                    try:
                        await store.record_pr(pr)
                    except Exception as exc:  # noqa: BLE001
                        log.warning("github-наблюдатель: PR #%d не записан: %s",
                                    pr["number"], type(exc).__name__)
                    known.add((pr["repo"], pr["number"]))
                    update_recent([pr])
                    if first:
                        continue  # базовый проход: фиксируем без уведомления
                    for chat in s.chats:
                        try:
                            await send_text(chat_target(chat), format_message(pr))
                            notified += 1
                        except Exception as exc:  # noqa: BLE001
                            log.warning("github-наблюдатель: уведомление в %s не ушло: %s",
                                        chat, type(exc).__name__)
                    if review_hook is not None:
                        # ревью — отдельной задачей: не блокируем цикл опроса
                        asyncio.create_task(review_hook(pr))
                update_recent(all_prs)

                if first:
                    log.info("github-наблюдатель: baseline — %d открытых PR зафиксировано "
                             "без уведомления", len(new))
                    first = False
                elif notified:
                    log.info("github-наблюдатель: новых PR: %d (уведомлений: %d)",
                             len(new), notified)
                backoff = 1.0
            except asyncio.CancelledError:
                raise
            except httpx.HTTPStatusError as exc:
                log.error("github-наблюдатель: HTTP %d (%s), бэкофф %.0f c",
                          exc.response.status_code, exc.request.url.host, backoff)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, BACKOFF_MAX)
            except (httpx.HTTPError, OSError, ValueError) as exc:
                log.error("github-наблюдатель: %s, бэкофф %.0f c",
                          type(exc).__name__, backoff)
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, BACKOFF_MAX)

            await asyncio.sleep(s.interval_sec)


def reset_state() -> None:
    """Сброс in-memory состояния (для проверок)."""
    global _recent
    _recent = []
