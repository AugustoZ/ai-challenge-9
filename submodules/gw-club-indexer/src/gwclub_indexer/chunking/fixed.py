"""Fixed-size token-window chunker with sentence/table boundaries (Task 8).

The ``fixed`` strategy is the baseline for strategy comparison: it ignores
document structure and packs "atomic units" greedily up to a token budget,
carrying a small overlap tail between consecutive chunks so the surrounding
context is preserved.

Unit model (parsed in document order from ``Document.content``):

* a markdown heading line (``^#{1,6} ``) is its own unit, kept verbatim;
* a table block (consecutive lines starting with ``|``) is ONE atomic unit of
  ANY size — overflow beyond ``max_tokens`` is allowed, a table is never split
  mid-rows;
* a list block (consecutive lines starting with ``-``/``*``/``N.``) is one unit;
* any other text forms a paragraph, split into sentence units (sentences end at
  ``[.!?…]`` followed by whitespace); a paragraph whose *first* sentence alone
  exceeds ``max_tokens`` falls back to word-window splitting (each window is at
  most ``max_tokens`` words).

Packing: greedily accumulate units; a chunk closes when adding the next unit
would exceed ``max_tokens``. An empty chunk always accepts the next unit even if
it is oversized. Overlap: the next chunk begins with the trailing units of the
previous chunk whose combined token count is <= ``overlap_tokens`` (whole units
from the end; if none fit, start fresh). Overlap units ar intentionally
duplicated.

The fixed strategy has no section awareness, so ``section`` is always
``NO_SECTION`` ("(документ)").

Public API::

    from gwclub_indexer.chunking.fixed import FixedChunker
"""

from __future__ import annotations

import re

from gwclub_indexer.chunking.base import NO_SECTION, Chunk, make_chunk_id
from gwclub_indexer.chunking.tokens import Tokenizer
from gwclub_indexer.config import FixedChunkingConfig
from gwclub_indexer.loader import Document

__all__ = ["FixedChunker"]

_STRATEGY = "fixed"

#: Markdown ATX heading: 1-6 ``#`` followed by whitespace, at line start.
_HEADING_RE = re.compile(r"^#{1,6}\s")
#: Numbered or bullet list item start (``-``, ``*``, ``N.``) followed by space.
_LIST_RE = re.compile(r"^(?:[-*]\s|\d+\.\s)")
#: Sentence boundary: a terminator followed by whitespace.
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?…])\s+")


class FixedChunker:
    """Split a :class:`Document` into fixed-budget, overlapping chunks."""

    def __init__(self, cfg: FixedChunkingConfig, tokenizer: Tokenizer) -> None:
        self.cfg = cfg
        self.tokenizer = tokenizer

    def chunk(self, document: Document) -> list[Chunk]:
        units = self._parse_units(document.content)
        packed = self._pack(units)
        return self._build_chunks(document, packed)

    # ------------------------------------------------------------------ #
    # Unit model
    # ------------------------------------------------------------------ #
    def _tokens(self, text: str) -> int:
        return self.tokenizer.count_tokens(text)

    def _parse_units(self, content: str) -> list[str]:
        """Split markdown body into atomic units in document order."""
        lines = content.split("\n")
        units: list[str] = []
        i = 0
        n = len(lines)
        while i < n:
            line = lines[i]
            stripped = line.strip()
            if stripped == "":
                i += 1
                continue
            if _HEADING_RE.match(line):
                units.append(line.rstrip())
                i += 1
                continue
            if stripped.startswith("|"):
                block: list[str] = []
                while i < n and lines[i].strip().startswith("|"):
                    block.append(lines[i].rstrip())
                    i += 1
                units.append("\n".join(block))
                continue
            if _LIST_RE.match(line.lstrip()):
                block = []
                while i < n and _LIST_RE.match(lines[i].lstrip()):
                    block.append(lines[i].rstrip())
                    i += 1
                units.append("\n".join(block))
                continue
            # Paragraph: consecutive text lines (soft-wrapped) -> one paragraph.
            para_lines: list[str] = []
            while i < n:
                ln = lines[i]
                s = ln.strip()
                if s == "":
                    break
                if (
                    _HEADING_RE.match(ln)
                    or s.startswith("|")
                    or _LIST_RE.match(ln.lstrip())
                ):
                    break
                para_lines.append(ln.strip())
                i += 1
            units.extend(self._split_paragraph(" ".join(para_lines)))
        return units

    def _split_paragraph(self, paragraph: str) -> list[str]:
        paragraph = paragraph.strip()
        if not paragraph:
            return []
        sentences = self._sentences(paragraph)
        if sentences and self._tokens(sentences[0]) > self.cfg.max_tokens:
            return self._word_windows(paragraph)
        return sentences

    def _sentences(self, text: str) -> list[str]:
        """Split text into whole sentences; terminators stay with their sentence."""
        text = text.strip()
        if not text:
            return []
        parts = _SENTENCE_SPLIT_RE.split(text)
        return [p.strip() for p in parts if p.strip()]

    def _word_windows(self, text: str) -> list[str]:
        """Fallback for a monster sentence: word windows of <= max_tokens words."""
        words = text.split()
        if not words:
            return []
        windows: list[str] = []
        cur: list[str] = []
        cur_tokens = 0
        for word in words:
            if cur and cur_tokens + 1 > self.cfg.max_tokens:
                windows.append(" ".join(cur))
                cur = []
                cur_tokens = 0
            cur.append(word)
            cur_tokens += 1
        if cur:
            windows.append(" ".join(cur))
        return windows

    # ------------------------------------------------------------------ #
    # Packing + overlap
    # ------------------------------------------------------------------ #
    def _pack(self, units: list[str]) -> list[list[str]]:
        """Greedily pack units into chunks with whole-unit overlap."""
        n = len(units)
        if n == 0:
            return []
        token_counts = [self._tokens(u) for u in units]
        chunks: list[list[str]] = []
        start = 0
        while start < n:
            end = start
            cur_tokens = 0
            while end < n:
                t = token_counts[end]
                if end > start and cur_tokens + t > self.cfg.max_tokens:
                    break
                cur_tokens += t
                end += 1
            chunks.append(units[start:end])
            if end >= n:
                break
            # Overlap: trailing units of this chunk whose combined tokens <= overlap.
            acc = 0
            ov = end
            while ov > start:
                t = token_counts[ov - 1]
                if acc + t <= self.cfg.overlap_tokens:
                    acc += t
                    ov -= 1
                else:
                    break
            # If the whole chunk fit within overlap_tokens, `ov` walked back to
            # `start` making no row progress -> fall back to no overlap.
            start = ov if ov > start else end
        return chunks

    # ------------------------------------------------------------------ #
    # Chunk assembly
    # ------------------------------------------------------------------ #
    def _build_chunks(self, document: Document, packed: list[list[str]]) -> list[Chunk]:
        chunks: list[Chunk] = []
        for index, unit_list in enumerate(packed):
            content = "\n".join(unit_list)
            chunks.append(
                Chunk(
                    chunk_id=make_chunk_id(document.source, _STRATEGY, index),
                    source=document.source,
                    title=document.title,
                    section=NO_SECTION,
                    locale=document.locale,
                    chunk_index=index,
                    strategy=_STRATEGY,
                    token_count=self._tokens(content),
                    char_count=len(content),
                    content=content,
                )
            )
        return chunks
