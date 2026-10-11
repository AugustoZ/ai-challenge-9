"""Chunk model and heading/trail utilities for the indexing pipeline (Task 4)."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass

__all__ = [
    "CHUNK_STRATEGIES",
    "NO_SECTION",
    "Chunk",
    "make_chunk_id",
    "normalize_heading",
    "section_to_trail",
]

#: Valid values for Chunk.strategy.
CHUNK_STRATEGIES: tuple[str, ...] = ("fixed", "structure")

#: Canonical fallback section label for documents without headings (used in eval/display).
NO_SECTION = "(документ)"

_HEADING_MARKER_RE = re.compile(r"^#{1,6}\s*")
# A backslash before a non-word, non-space character is a markdown escape (e.g. \-).
_MARKDOWN_ESCAPE_RE = re.compile(r"\\([^\w\s])")
_WHITESPACE_RE = re.compile(r"\s+")


def make_chunk_id(source: str, strategy: str, chunk_index: int) -> str:
    """Deterministic 16-char chunk id from (source, strategy, chunk_index) only.

    Content is deliberately excluded so ids survive content edits, and no
    random/time-based element is used so ids are stable across processes.
    """
    digest = hashlib.sha256(f"{source}|{strategy}|{chunk_index}".encode()).hexdigest()
    return digest[:16]


def normalize_heading(text: str) -> str:
    """Normalize a markdown heading into a plain section label.

    Strips leading ``#`` markers, unescapes backslash escapes (``\\-`` -> ``-``,
    ``\\\\`` -> ``\\``), collapses whitespace, and strips. Non-escape
    backslashes (e.g. ``\\n`` before a letter) are left intact.
    """
    t = text.strip()
    t = _HEADING_MARKER_RE.sub("", t)
    t = _MARKDOWN_ESCAPE_RE.sub(r"\1", t)
    t = _WHITESPACE_RE.sub(" ", t)
    return t.strip()


def section_to_trail(headings: list[str]) -> str:
    """Join a heading trail with ``" > "``; empty list -> ``(документ)``."""
    if not headings:
        return NO_SECTION
    return " > ".join(normalize_heading(h) for h in headings)


@dataclass
class Chunk:
    """A single indexable text chunk with stable identity and provenance metadata.

    ``strategy`` is one of ``"fixed"`` or ``"structure"`` (see CHUNK_STRATEGIES).
    """

    chunk_id: str
    source: str
    title: str
    section: str
    locale: str
    chunk_index: int
    strategy: str
    token_count: int
    char_count: int
    content: str
