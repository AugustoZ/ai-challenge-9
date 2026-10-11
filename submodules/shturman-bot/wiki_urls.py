"""Маппинг исходников корпуса gw-club-docs на страницы вики wiki.gwm-club.ru.

Зачем: индекс RAG хранит только относительные пути файлов (например,
``ru/firmware-list.md``), а пользователю нужны полноценные ссылки на вики.
У каждой страницы корпуса во frontmatter есть ``locale`` и ``path`` — из них
собирается URL вида ``https://wiki.gwm-club.ru/{locale}/{path}``.

Дополнительно утилиты:
    - rewrite_wiki_links: внутренние ссылки корпуса (``</path>``,
      ``](file.md)``) -> абсолютные URL вики.
    - section_text: полный текст секции по (файл, корень секции) — для
      гидрации маленьких заголовочных чанков (см. bot_responder).
"""

from __future__ import annotations

import logging
import os
import re
from pathlib import Path

log = logging.getLogger("wiki_urls")

WIKI_BASE = os.environ.get("WIKI_BASE", "https://wiki.gwm-club.ru")


def _discover_docs_root() -> Path | None:
    # Поиск корпуса обходом вверх: работает на любой глубине размещения
    # (хост, контейнер) и не падает, если родителей меньше трёх.
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "gw-club-docs"
        if candidate.is_dir():
            return candidate
    return None


DOCS_ROOT = Path(
    os.environ.get("DOCS_ROOT")
    or _discover_docs_root()
    or Path(__file__).resolve().parent / "gw-club-docs"
)

_FRONTMATTER = re.compile(r"\A---\s*\n(.*?)\n---\s*\n", re.DOTALL)
_MD_LINK = re.compile(r"\]\(([^)#\s]+\.md)(#[^)]*)?\)")
_WIKI_REF = re.compile(r"</([a-zA-Z0-9_\-/]+)>")


def _parse_frontmatter(text: str) -> dict[str, str]:
    m = _FRONTMATTER.match(text)
    if not m:
        return {}
    meta: dict[str, str] = {}
    for line in m.group(1).splitlines():
        if ":" in line:
            key, _, value = line.partition(":")
            meta[key.strip()] = value.strip().strip('"')
    return meta


def build_map() -> dict[str, str]:
    """source (путь относительно DOCS_ROOT, ``/``-разделители) -> URL вики.

    Отсутствующий корпус — не ошибка: возвращается пустая карта, а бот
    продолжает показывать имена файлов.
    """
    result: dict[str, str] = {}
    if not DOCS_ROOT.is_dir():
        log.warning("DOCS_ROOT не найден (%s) — ссылки на вики недоступны", DOCS_ROOT)
        return result
    for md in DOCS_ROOT.rglob("*.md"):
        rel = md.relative_to(DOCS_ROOT).as_posix()
        try:
            meta = _parse_frontmatter(md.read_text(encoding="utf-8", errors="replace")[:2048])
        except OSError as exc:
            log.warning("не прочитан %s: %s", rel, exc)
            continue
        page_path, locale = meta.get("path"), meta.get("locale", "ru")
        if page_path:
            result[rel] = f"{WIKI_BASE}/{locale}/{page_path.lstrip('/')}"
    log.info("wiki_urls: %d страниц корпуса смаппировано на %s", len(result), WIKI_BASE)
    return result


def source_url(source: str, url_map: dict[str, str]) -> str | None:
    return url_map.get(source.strip())


def _wiki_page_exists(url_map: dict[str, str], page_path: str) -> bool:
    suffix = f"/{page_path.lstrip('/')}"
    return any(url.endswith(suffix) for url in url_map.values())


def rewrite_wiki_links(content: str, source: str, url_map: dict[str, str]) -> str:
    """``</path>`` и ``](file.md)`` -> абсолютные URL (только известные страницы).

    Относительные ``file.md``-ссылки резолвятся от каталога файла ``source``.
    """

    def sub_ref(m: re.Match[str]) -> str:
        page = m.group(1)
        return f"<{WIKI_BASE}/ru/{page.lstrip('/')}>" if _wiki_page_exists(url_map, page) else m.group(0)

    src_dir = os.path.dirname(source.strip())

    def sub_md(m: re.Match[str]) -> str:
        target, anchor = m.group(1), m.group(2) or ""
        joined = os.path.normpath(os.path.join(src_dir, target)).replace(os.sep, "/").lstrip("./")
        if joined in url_map:
            return f"]({url_map[joined]}{anchor})"
        return m.group(0)

    content = _WIKI_REF.sub(sub_ref, content)
    return _MD_LINK.sub(sub_md, content)


def section_text(source: str, section: str, max_chars: int = 6000) -> str | None:
    """Полный текст корневой секции ``section`` из файла ``source`` корпуса.

    Секции в индексе вида ``«A > B > C»``; берём корень ``A`` и вырезаем из
    файла всё от его заголовка до заголовка того же или более высокого
    уровня. Возвращает None, если файл/секция не найдены.
    """
    if not DOCS_ROOT.is_dir():
        return None
    path = DOCS_ROOT / source
    if not path.is_file():
        return None
    root = (section or "").split(" > ")[0].strip()
    if not root:
        return None
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None
    start = next((i for i, ln in enumerate(lines) if re.match(r"^#{1,6}\s+", ln) and root in ln), None)
    if start is None:
        return None
    level = len(lines[start]) - len(lines[start].lstrip("#"))
    end = next(
        (i for i in range(start + 1, len(lines)) if re.match(r"^#{1,6}\s+", lines[i]) and (len(lines[i]) - len(lines[i].lstrip("#"))) <= level),
        len(lines),
    )
    text = "\n".join(lines[start:end]).strip()
    if len(text) > max_chars:
        text = text[:max_chars] + "\n…"
    return text or None
