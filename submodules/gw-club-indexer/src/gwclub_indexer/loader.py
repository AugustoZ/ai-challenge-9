"""Document loader: walk the ru/ corpus and parse markdown into Documents (Task 3).

Public API::

    from gwclub_indexer.loader import Document, load_documents

    docs = load_documents(cfg)  # sorted by ``Document.source``

Behaviour:
- Only ``<docs_root>/ru/**/*.md`` is read (en/, ar/, assets/ are never touched).
- YAML frontmatter is recognized only when the first non-blank line is ``---``
  and a closing ``---`` line follows. A ``---`` horizontal rule anywhere later
  in the body is preserved in ``content`` (real corpus case:
  ``ru/tank_300_ultra_multimedia_firmware_guide.md``).
- Title resolution: frontmatter ``title`` -> first H1 -> file stem.
- Locale: frontmatter ``locale`` -> ``"ru"``.
- Filters (applied in order, skip file if): exact ``exclude_paths`` match on
  the relative source path, ``exclude_draft`` with ``draft == True``,
  ``len(content.strip()) < min_chars``.
- No content "cleaning" is performed beyond frontmatter stripping.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from gwclub_indexer.config import Config

__all__ = ["Document", "load_documents", "parse_frontmatter"]

_H1_RE = re.compile(r"^# (.+?)\s*$")
_DELIMITER = "---"


@dataclass(frozen=True)
class Document:
    """One markdown source document from the ru/ corpus."""

    source: str  # relative POSIX path, e.g. "ru/home.md"
    title: str
    section_h1: str  # first H1 text, or "" if absent
    locale: str
    content: str  # markdown body, frontmatter stripped
    frontmatter: dict = field(default_factory=dict)


def parse_frontmatter(text: str) -> tuple[dict, str]:
    """Split ``text`` into ``(frontmatter, body)``.

    Frontmatter exists only when the first non-blank line is ``---`` and a
    closing ``---`` line follows before any non-metadata content. On any
    fallback (absent or unparseable YAML) returns ``({}, text)`` so the whole
    original text stays the body.
    """
    lines = text.splitlines(keepends=True)

    start = 0
    while start < len(lines) and lines[start].strip() == "":
        start += 1
    if start >= len(lines) or lines[start].rstrip() != _DELIMITER:
        return {}, text

    for end in range(start + 1, len(lines)):
        if lines[end].rstrip() == _DELIMITER:
            block = "".join(lines[start + 1 : end])
            # Frontmatter strip: drop the delimiter-adjacent leading newlines
            # only; the body itself (including its H1) is kept verbatim.
            body = "".join(lines[end + 1 :]).lstrip("\n")
            try:
                data = yaml.safe_load(block)
            except yaml.YAMLError:
                return {}, text
            return (data if isinstance(data, dict) else {}), body

    return {}, text  # opening --- with no closing ---: not frontmatter


def _first_h1(content: str) -> str:
    for line in content.splitlines():
        match = _H1_RE.match(line)
        if match:
            return match.group(1).strip()
    return ""


def _load_one(path: Path, docs_root: Path) -> Document:
    text = path.read_text(encoding="utf-8")
    frontmatter, content = parse_frontmatter(text)

    title = frontmatter.get("title")
    if not isinstance(title, str) or not title.strip():
        h1 = _first_h1(content)
        title = h1 if h1 else path.stem

    locale = frontmatter.get("locale")
    if not isinstance(locale, str) or not locale.strip():
        locale = "ru"

    return Document(
        source=path.relative_to(docs_root).as_posix(),
        title=title.strip(),
        section_h1=_first_h1(content),
        locale=locale.strip(),
        content=content,
        frontmatter=frontmatter,
    )


def load_documents(cfg: Config) -> list[Document]:
    """Load, parse and filter every ``ru/**/*.md`` document under docs_root.

    Returns documents sorted by ``source`` for deterministic order.
    """
    docs_root = Path(cfg.paths.docs_root)
    ru_root = docs_root / "ru"
    if not ru_root.is_dir():
        return []

    excluded = set(cfg.filters.exclude_paths)
    docs: list[Document] = []
    for path in sorted(ru_root.rglob("*.md")):
        if not path.is_file():
            continue
        source = path.relative_to(docs_root).as_posix()
        if source in excluded:
            continue

        doc = _load_one(path, docs_root)
        if cfg.filters.exclude_draft and doc.frontmatter.get("draft") is True:
            continue
        if len(doc.content.strip()) < cfg.filters.min_chars:
            continue
        docs.append(doc)

    docs.sort(key=lambda d: d.source)
    return docs
