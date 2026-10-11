"""Structure-aware chunker: split on markdown headings with heading trails (Task 9).

Splits a document into chunks on heading boundaries. Every heading of level >= 2
starts a new logical section whose ``section`` is the full heading trail
(H2..Hn only -- the H1 / document title lives in ``Document.title``). Content
before the first H2 (e.g. an intro paragraph directly under the H1) forms a
root section with the canonical ``NO_SECTION`` fallback.

Behaviour highlights:
- Heading lines are kept at the top of their chunk content (the eval relevance
  mapping relies on content-level heading matches).
- ``---`` horizontal rules are ordinary content, never section boundaries.
- Consecutive ``|``-prefixed lines form one table unit; a table larger than
  ``max_tokens`` is split into row batches under ``max_tokens``, each batch
  repeating the table header rows (columns context) — so individual rows of
  giant contact/location tables stay searchable.
- A section larger than ``max_tokens`` is recursively sub-split by
  paragraph/sentence units with overlap; every sub-chunk inherits the SAME
  section trail and repeats the heading line as its first line.
- ``merge_short_sections`` appends short sections (< ``min_section_tokens``)
  to the previous chunk, which keeps its own trail.

The tokenizer is duck-typed: only ``count_tokens(text) -> int`` is used. The
real model tokenizer is wired in by the indexer task; unit tests use a
whitespace word counter.
"""

from __future__ import annotations

import re

from gwclub_indexer.chunking.base import (
    NO_SECTION,
    Chunk,
    make_chunk_id,
    normalize_heading,
    section_to_trail,
)
from gwclub_indexer.config import StructureChunkingConfig
from gwclub_indexer.loader import Document

_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+)$")
_SENTENCE_RE = re.compile(r"(?<=[.!?])\s+")


class StructureChunker:
    """Chunk a markdown document into heading-scoped chunks."""

    def __init__(self, cfg: StructureChunkingConfig, tokenizer) -> None:
        self.cfg = cfg
        self.tokenizer = tokenizer

    # ------------------------------------------------------------------ #
    # Public API
    # ------------------------------------------------------------------ #
    def chunk(self, document: Document) -> list[Chunk]:
        """Return structure chunks for ``document`` in document order."""
        sections = self._parse_sections(document.content)
        groups = self._group_for_merge(sections)
        chunks: list[Chunk] = []
        for group in groups:
            for content in self._chunk_group(group):
                chunk_index = len(chunks)
                chunks.append(
                    Chunk(
                        chunk_id=make_chunk_id(
                            document.source, "structure", chunk_index
                        ),
                        source=document.source,
                        title=document.title,
                        section=group[0][0],
                        locale=document.locale,
                        chunk_index=chunk_index,
                        strategy="structure",
                        token_count=self.tokenizer.count_tokens(content),
                        char_count=len(content),
                        content=content,
                    )
                )
        return chunks

    # ------------------------------------------------------------------ #
    # Section parsing
    # ------------------------------------------------------------------ #
    def _parse_sections(self, content: str) -> list[tuple[str, list[str]]]:
        """Walk ``content`` into logical sections ``(trail, lines)``.

        Heading lines (H1..H6) open a new section; ``---`` rules are ordinary
        body lines. H1 resets the heading stack (title lives in metadata) and
        is excluded from the trail. Content before the first H2 (including an
        intro directly under the H1) lands in a root ``NO_SECTION`` section.
        """
        sections: list[tuple[str, list[str]]] = []
        stack: list[tuple[int, str]] = []  # (level, normalized_label)

        cur_level: int | None = None
        cur_trail: str = NO_SECTION
        cur_heading: str | None = None
        cur_body: list[str] = []

        def finalize() -> None:
            nonlocal cur_heading, cur_body, cur_level
            if cur_heading is None and not cur_body:
                return
            # H1-only block (title already in Document.title) -> not a section.
            if cur_level == 1 and not any(ln.strip() for ln in cur_body):
                cur_heading = None
                cur_body = []
                return
            blk = ([cur_heading] if cur_heading is not None else []) + cur_body
            sections.append((cur_trail, blk))
            cur_heading = None
            cur_body = []

        for line in content.splitlines():
            m = _HEADING_RE.match(line)
            if m is not None:
                level = len(m.group(1))
                label = normalize_heading(m.group(2).strip())
                finalize()
                if level == 1:
                    stack.clear()
                    cur_trail = NO_SECTION
                else:
                    while stack and stack[-1][0] >= level:
                        stack.pop()
                    stack.append((level, label))
                    cur_trail = section_to_trail([lbl for _, lbl in stack])
                cur_level = level
                cur_heading = line
                cur_body = []
            else:
                cur_body.append(line)
        finalize()
        return sections

    def _group_for_merge(self, sections) -> list[list[tuple[str, list[str]]]]:
        """Group sections for ``merge_short_sections``.

        A section with ``total < min_section_tokens`` is appended to the
        previous group (the first section always starts its own group so a
        short first section gets its own chunk).
        """
        groups: list[list[tuple[str, list[str]]]] = []
        if not self.cfg.merge_short_sections:
            return [[s] for s in sections]
        for trail, lines in sections:
            total = self.tokenizer.count_tokens("\n".join(lines))
            if total < self.cfg.min_section_tokens and groups:
                groups[-1].append((trail, lines))
            else:
                groups.append([(trail, lines)])
        return groups

    # ------------------------------------------------------------------ #
    # Per-section chunking
    # ------------------------------------------------------------------ #
    def _chunk_group(self, group) -> list[str]:
        """Chunk one (possibly merged) group of sections into content strings."""
        contents: list[str] = []
        for trail, lines in group:
            section_contents = self._chunk_section(trail, lines)
            if not contents:
                contents.extend(section_contents)
            else:
                # Append a merged short section onto the previous chunk.
                contents[-1] = contents[-1].rstrip() + "\n\n" + section_contents[0]
                # A merged section may still sub-split into several contents;
                # keep the remainder so no content is lost.
                contents.extend(section_contents[1:])
        return contents

    def _chunk_section(self, trail: str, lines: list[str]) -> list[str]:
        """Turn one section's lines into one-or-more content strings."""
        if self.tokenizer.count_tokens("\n".join(lines)) <= self.cfg.max_tokens:
            return ["\n".join(lines)]

        header, body = self._split_header(lines)
        units = self._build_units(body)
        return self._pack_units(header, units)

    def _split_header(self, lines: list[str]) -> tuple[list[str], list[str]]:
        """Separate a leading heading line from the remaining body lines."""
        if lines and _HEADING_RE.match(lines[0]):
            return [lines[0]], lines[1:]
        return [], lines

    # ------------------------------------------------------------------ #
    # Units and packing
    # ------------------------------------------------------------------ #
    def _build_units(self, body: list[str]) -> list[tuple[str, list[str]]]:
        """Group ``body`` into atomic units: tables or paragraphs.

        Consecutive ``|``-prefixed lines form one table unit. A paragraph is a
        run of non-blank, non-table lines. A paragraph larger than ``max_tokens``
        is further split into sentence units.
        """
        units: list[tuple[str, list[str]]] = []
        i = 0
        n = len(body)
        while i < n:
            stripped = body[i].strip()
            if stripped == "":
                i += 1
                continue
            if stripped.startswith("|"):
                j = i
                while j < n and body[j].strip().startswith("|"):
                    j += 1
                units.extend(self._split_table(body[i:j]))
                i = j
            else:
                j = i
                while (
                    j < n
                    and body[j].strip() != ""
                    and not body[j].strip().startswith("|")
                ):
                    j += 1
                para = body[i:j]
                if self.tokenizer.count_tokens("\n".join(para)) > self.cfg.max_tokens:
                    units.extend(self._split_para(para))
                else:
                    units.append(("para", para))
                i = j
        return units

    def _split_para(self, para_lines: list[str]) -> list[tuple[str, list[str]]]:
        """Split an over-long paragraph into sentence units."""
        text = "\n".join(para_lines).strip()
        sentences = [s.strip() for s in _SENTENCE_RE.split(text) if s.strip()]
        if not sentences:
            return [("para", para_lines)]
        return [("para", [s]) for s in sentences]

    def _split_table(self, table: list[str]) -> list[tuple[str, list[str]]]:
        """Split an over-long table into row batches under ``max_tokens``.

        Each batch repeats the table header (rows up to and including the
        ``|---|`` separator) so column context survives the split. A table
        that already fits ``max_tokens`` stays a single atomic unit.
        """
        if self.tokenizer.count_tokens("\n".join(table)) <= self.cfg.max_tokens:
            return [("table", table)]
        header: list[str] = []
        body_start = 0
        for k, line in enumerate(table[:3]):
            header.append(line)
            if re.match(r"^\|[\s:\-|]+\|$", line.strip()):
                body_start = k + 1
                break
        header_tokens = self.tokenizer.count_tokens("\n".join(header))
        batches: list[tuple[str, list[str]]] = []
        cur: list[str] = list(header)
        cur_tokens = header_tokens
        for line in table[body_start:]:
            t = self.tokenizer.count_tokens(line)
            if cur and cur_tokens + t > self.cfg.max_tokens:
                batches.append(("table", cur))
                cur = list(header)
                cur_tokens = header_tokens
            cur.append(line)
            cur_tokens += t
        if cur != list(header):
            batches.append(("table", cur))
        return batches or [("table", table)]

    def _pack_units(
        self, header: list[str], units: list[tuple[str, list[str]]]
    ) -> list[str]:
        """Greedily pack units into chunks, repeating the header in each.

        Chunks stay under ``max_tokens``; the trailing units of a finished
        chunk are carried into the next as overlap up to ``overlap_tokens``.
        A single unit larger than ``max_tokens`` (e.g. a giant table) becomes
        its own overflow chunk rather than being split.
        """
        chunks: list[str] = []
        cur: list[tuple[str, list[str]]] = []
        cur_tokens = 0
        i = 0
        while i < len(units):
            ut = self.tokenizer.count_tokens("\n".join(units[i][1]))
            if not cur:
                cur.append(units[i])
                cur_tokens = ut
                i += 1
                continue
            if cur_tokens + ut > self.cfg.max_tokens:
                chunks.append(self._render(header, cur))
                overlap = self._take_overlap(cur)
                cur = overlap
                cur_tokens = sum(
                    self.tokenizer.count_tokens("\n".join(u[1])) for u in cur
                )
                cur.append(units[i])
                cur_tokens += ut
                i += 1
            else:
                cur.append(units[i])
                cur_tokens += ut
                i += 1
        if cur:
            chunks.append(self._render(header, cur))
        return chunks

    def _take_overlap(
        self, cur: list[tuple[str, list[str]]]
    ) -> list[tuple[str, list[str]]]:
        """Return the trailing units of ``cur`` summing to <= ``overlap_tokens``.

        Progress guard: if the overlap would consume the entire chunk (so the
        next chunk makes no progress), fall back to no overlap.
        """
        if not cur:
            return []
        overlap: list[tuple[str, list[str]]] = []
        ot = 0
        for unit in reversed(cur):
            t = self.tokenizer.count_tokens("\n".join(unit[1]))
            if ot + t > self.cfg.overlap_tokens:
                break
            overlap.insert(0, unit)
            ot += t
        if len(overlap) >= len(cur):
            return []
        return overlap

    def _render(self, header: list[str], units: list[tuple[str, list[str]]]) -> str:
        """Join a header and units into a chunk content string."""
        body = "\n\n".join("\n".join(u[1]) for u in units)
        if header:
            return "\n".join(header) + (("\n\n" + body) if body else "")
        return body
