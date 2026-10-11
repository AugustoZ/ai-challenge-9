"""Deterministic ground-truth generator for the gw-club-docs indexer (Task 12).

This module produces a small, human-curatable list of ``(query, source, section,
origin)`` items used to evaluate the retrieval pipeline. It is a pure
rule-based generator: **no LLM/API is called**.

Two item kinds are produced:

* ``heading`` -- a document heading (H2/H3) re-used as a query. The ``section``
  is the heading trail (H1 excluded) **including** the heading itself.
* ``faq`` -- a human-authored question from one of the three FAQ files
  (matched by basename): an H2/H3 heading or a list item ending in ``?``.

Drafts are skipped regardless of the loader's filtering: the generator checks
``doc.frontmatter.get("draft") is True`` itself.

Public API::

    from gwclub_indexer.eval.groundtruth import (
        GroundTruthItem, generate_ground_truth, save_ground_truth, load_ground_truth,
    )

    items = generate_ground_truth(cfg, docs)
    save_ground_truth(items, Path("eval/groundtruth.yaml"))
    items = load_ground_truth(Path("eval/groundtruth.yaml"))
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from pathlib import Path

import yaml

from gwclub_indexer.chunking.base import normalize_heading, section_to_trail
from gwclub_indexer.config import Config
from gwclub_indexer.loader import Document

__all__ = [
    "HEADER_COMMENT",
    "GroundTruthItem",
    "generate_ground_truth",
    "load_ground_truth",
    "save_ground_truth",
]

#: Basenames (not full paths) of the FAQ documents whose questions become
#: ``origin="faq"`` items. ``faq.md`` is a draft and is therefore excluded.
FAQ_BASENAMES: frozenset[str] = frozenset(
    {"FAQ-all.md", "FAQ_Tank300.md", "FaqHaval.md"}
)

#: Valid values for ``GroundTruthItem.origin``.
ORIGINS: frozenset[str] = frozenset({"heading", "faq"})

#: Selection tuning knobs (see :func:`generate_ground_truth`).
TARGET_ITEMS: int = 25
MAX_ITEMS: int = 30

#: Heading-as-query minimums (skip headings shorter than 8 chars or <2 words).
MIN_HEADING_CHARS: int = 8
MIN_HEADING_WORDS: int = 2
#: FAQ questions must be at least this many chars (after stripping markers).
MIN_FAQ_CHARS: int = 15

#: Header comment written by :func:`save_ground_truth`.
HEADER_COMMENT: str = (
    "# Сгенерировано автоматически (gwclub groundtruth). "
    "Поправьте/дополните вручную — eval читает этот файл."
)

_HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$")
_LIST_RE = re.compile(r"^\s*(?:[-*]|\d+[.)])\s+(.+)$")
_WS_RE = re.compile(r"\s+")


@dataclass(frozen=True)
class GroundTruthItem:
    """One ground-truth query bound to a ``(source, section)`` location.

    ``origin`` is ``"heading"`` (a document heading reused as a query) or
    ``"faq"`` (a human-authored FAQ question). Relevance is intentionally bound
    at the ``(source, section)`` level -- not to a ``chunk_id`` -- because the
    eval layer maps sections to chunks.
    """

    query: str
    source: str
    section: str
    origin: str


def _normalize_query(query: str) -> str:
    """Lowercase + collapse whitespace; used for deterministic dedup."""
    return " ".join(_WS_RE.sub(" ", query).strip().lower().split())


def _is_faq_source(source: str) -> bool:
    """Return True if ``source``'s basename is one of the FAQ documents."""
    return Path(source).name in FAQ_BASENAMES


def _iter_walk(doc: Document):
    """Yield ``(kind, level, text, trail)`` tuples for a document body.

    ``kind`` is ``"heading"`` (markdown heading line) or ``"list"`` (a list
    item). ``level`` is the heading level for headings and ``None`` for list
    items. ``text`` is the normalized heading/list text. ``trail`` is the list
    of open heading texts (H1 excluded); for a heading it includes the heading
    itself, for a list item it is the open parent chain.
    """
    stack: list[tuple[int, str]] = []
    for raw_line in doc.content.splitlines():
        line = raw_line.rstrip()
        head = _HEADING_RE.match(line)
        if head:
            level = len(head.group(1))
            if level == 1:
                stack = []  # H1 excluded from the trail
                continue
            text = normalize_heading(head.group(2))
            while stack and stack[-1][0] >= level:
                stack.pop()
            stack.append((level, text))
            yield ("heading", level, text, [t for _, t in stack])
            continue
        list_match = _LIST_RE.match(line)
        if list_match:
            text = normalize_heading(list_match.group(1))
            yield ("list", None, text, [t for _, t in stack])


def _meets_heading_criteria(text: str) -> bool:
    """Heading-as-query acceptance: >=MIN_HEADING_CHARS chars and >=2 words."""
    words = [w for w in text.split() if w]
    return len(text) >= MIN_HEADING_CHARS and len(words) >= MIN_HEADING_WORDS


def _heading_items(doc: Document) -> list[GroundTruthItem]:
    """Build ``origin="heading"`` items from a document's H2/H3 headings."""
    items: list[GroundTruthItem] = []
    for kind, level, text, trail in _iter_walk(doc):
        if kind != "heading" or level not in (2, 3):
            continue
        if not _meets_heading_criteria(text):
            continue
        items.append(
            GroundTruthItem(
                query=text,
                source=doc.source,
                section=section_to_trail(trail),
                origin="heading",
            )
        )
    return items


def _faq_items(doc: Document) -> list[GroundTruthItem]:
    """Build ``origin="faq"`` items from a FAQ document.

    A FAQ item is an H2/H3 heading, or a list item, whose normalized text ends
    with ``?`` and is at least :data:`MIN_FAQ_CHARS` long. Its ``section`` is
    the current heading trail (H1 excluded).
    """
    items: list[GroundTruthItem] = []
    for kind, level, text, trail in _iter_walk(doc):
        if kind == "heading":
            if level not in (2, 3):
                continue
        elif kind != "list":
            continue
        if not text.endswith("?"):
            continue
        if len(text) < MIN_FAQ_CHARS:
            continue
        items.append(
            GroundTruthItem(
                query=text,
                source=doc.source,
                section=section_to_trail(trail),
                origin="faq",
            )
        )
    return items


def _dedup(items: list[GroundTruthItem]) -> list[GroundTruthItem]:
    """Dedup on normalized query; first occurrence wins."""
    seen: set[str] = set()
    out: list[GroundTruthItem] = []
    for item in items:
        key = _normalize_query(item.query)
        if key in seen:
            continue
        seen.add(key)
        out.append(item)
    return out


def _select(items: list[GroundTruthItem]) -> list[GroundTruthItem]:
    """Select 15-30 items: all FAQ first (up to MAX_ITEMS), then heading items
    round-robin over distinct sources until TARGET_ITEMS is reached.

    ``items`` must already be deduped, with FAQ items before heading items (the
    FAQ version wins for any shared normalized query).
    """
    selected: list[GroundTruthItem] = []

    # 1. Take every FAQ item, capped so the total never exceeds MAX_ITEMS.
    for item in items:
        if item.origin != "faq":
            break  # pool is ordered: all faq items first
        if len(selected) >= MAX_ITEMS:
            break
        selected.append(item)

    # 2. Fill remaining slots (up to TARGET_ITEMS) with heading items,
    #    round-robin across distinct sources so one file can't dominate.
    if len(selected) < TARGET_ITEMS:
        by_source: dict[str, list[GroundTruthItem]] = {}
        for item in items:
            if item.origin != "heading":
                continue
            by_source.setdefault(item.source, []).append(item)

        sources = sorted(by_source)
        index = 0
        while len(selected) < TARGET_ITEMS:
            added = False
            for source in sources:
                if len(selected) >= TARGET_ITEMS:
                    break
                bucket = by_source[source]
                if index < len(bucket):
                    selected.append(bucket[index])
                    added = True
            index += 1
            if not added:
                break

    # 3. Deterministic final order.
    selected.sort(key=lambda it: (it.source, it.section, it.query))
    return selected


def generate_ground_truth(cfg: Config, docs: list[Document]) -> list[GroundTruthItem]:
    """Generate the ground-truth item list from documents.

    The generator is deterministic and does **not** call any LLM. It
    independently skips any document whose frontmatter declares
    ``draft: true`` -- it does not rely on the loader having already filtered
    drafts. FAQ files are recognised by basename. Heading items are taken from
    every document; FAQ items from the FAQ files only.

    Returns between 15 and 30 items (fewer only if the corpus is too small).
    """
    faq: list[GroundTruthItem] = []
    heading: list[GroundTruthItem] = []

    for doc in sorted(docs, key=lambda d: d.source):
        if doc.frontmatter.get("draft") is True:
            continue
        heading.extend(_heading_items(doc))
        if _is_faq_source(doc.source):
            faq.extend(_faq_items(doc))

    # Dedup with FAQ first so a question that is both a heading and an FAQ
    # question survives as the "faq" item.
    ordered = _dedup(faq + heading)
    return _select(ordered)


def save_ground_truth(items: list[GroundTruthItem], path: Path) -> None:
    """Write the ground-truth items to ``path`` as YAML with a header comment.

    The header comment tells a human the file is auto-generated and editable.
    Lists are dumped with ``allow_unicode=True`` and ``sort_keys=False`` so
    field order (query, source, section, origin) is preserved.
    """
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    body = yaml.safe_dump(
        [asdict(item) for item in items],
        allow_unicode=True,
        sort_keys=False,
        default_flow_style=False,
    )
    path.write_text(HEADER_COMMENT + "\n" + body, encoding="utf-8")


def load_ground_truth(path: Path) -> list[GroundTruthItem]:
    """Load ground-truth items from a YAML file written by :func:`save_ground_truth`.

    Accepts either a top-level list or a mapping with an ``items`` key. An
    ``origin`` outside ``{heading, faq}`` raises :class:`ValueError`.
    """
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if isinstance(data, dict):
        items = data.get("items", [])
    elif isinstance(data, list):
        items = data
    else:
        raise TypeError(f"Unexpected ground-truth YAML shape: {type(data).__name__}")

    result: list[GroundTruthItem] = []
    for entry in items:
        origin = entry.get("origin")
        if origin not in ORIGINS:
            raise ValueError(f"Unknown ground-truth origin: {origin!r}")
        result.append(
            GroundTruthItem(
                query=entry["query"],
                source=entry["source"],
                section=entry["section"],
                origin=origin,
            )
        )
    return result
