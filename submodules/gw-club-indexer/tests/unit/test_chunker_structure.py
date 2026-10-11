"""Unit tests for chunking.structure.StructureChunker (Task 9).

TDD: a duck-typed WordCounter (1 word = 1 token) stands in for the real model
tokenizer; ``gwclub_indexer.chunking.tokens`` is intentionally NOT imported
(that module is built in parallel by another task).
"""

from __future__ import annotations

from gwclub_indexer.chunking.base import NO_SECTION
from gwclub_indexer.chunking.structure import StructureChunker
from gwclub_indexer.config import StructureChunkingConfig
from gwclub_indexer.loader import Document


class WordCounter:
    """Whitespace word counter: 1 token per whitespace-separated word."""

    def count_tokens(self, text: str) -> int:
        return len(text.split())


def make_cfg(**overrides) -> StructureChunkingConfig:
    defaults = {
        "max_tokens": 400,
        "overlap_tokens": 60,
        "merge_short_sections": True,
        "min_section_tokens": 40,
    }
    defaults.update(overrides)
    return StructureChunkingConfig(**defaults)


def make_doc(content: str) -> Document:
    return Document(
        source="ru/test.md",
        title="Тестовый документ",
        section_h1="",
        locale="ru",
        content=content,
    )


def words(n: int) -> str:
    """A paragraph of ``n`` space-separated words (n tokens)."""
    return " ".join(f"w{i}" for i in range(n))


# --- H2 sections --------------------------------------------------------------


def test_h2_sections_become_separate_chunks():
    content = "# Заголовок\n## A\n" + words(20) + "\n## B\n" + words(20) + "\n"
    chunker = StructureChunker(make_cfg(merge_short_sections=False), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    assert [c.section for c in chunks] == ["A", "B"]
    assert [c.strategy for c in chunks] == ["structure", "structure"]
    # heading lines stay in content
    assert chunks[0].content.startswith("## A")
    assert chunks[1].content.startswith("## B")


def test_nested_h3_trail_inherits_h2_prefix():
    content = "## A\n" + words(20) + "\n### B\n" + words(20) + "\n"
    chunker = StructureChunker(make_cfg(merge_short_sections=False), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    sections = [c.section for c in chunks]
    assert "A" in sections
    assert "A > B" in sections


def test_long_h3_subchunks_share_h2_prefix():
    para = words(180)  # < max_tokens, > overlap_tokens
    huge = f"{para}\n\n{para}\n\n{para}"  # 540 tokens -> >= 2 sub-chunks
    content = "## A\n### A1\n" + huge + "\n### A2\n" + huge + "\n"
    chunker = StructureChunker(make_cfg(merge_short_sections=False), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    a1 = [c for c in chunks if c.section == "A > A1"]
    a2 = [c for c in chunks if c.section == "A > A2"]
    assert len(a1) >= 2, f"expected >=2 sub-chunks for A1, got {len(a1)}"
    assert len(a2) >= 2
    # every sub-chunk repeats the heading line as its first line
    assert all(c.content.startswith("### A1") for c in a1)
    assert all(c.content.startswith("### A2") for c in a2)


# --- Tables -------------------------------------------------------------------


def test_no_heading_table_doc_uses_no_section():
    content = "| h1 | h2 |\n| --- | --- |\n| r1 | v1 |\n| r2 | v2 |\n"
    chunker = StructureChunker(make_cfg(merge_short_sections=False), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    assert len(chunks) == 1
    assert chunks[0].section == NO_SECTION
    assert chunks[0].content.startswith("| h1 | h2 |")
    rows = ["| r1 | v1 |", "| r2 | v2 |"]
    for row in rows:
        assert row in chunks[0].content


def test_oversized_table_splits_into_header_batches():
    # 120 rows -> table unit >> max_tokens: split into row batches, each
    # batch repeats the table header rows so column context survives
    rows = "\n".join(f"| row {i} | value |" for i in range(120))
    content = "| h1 | h2 |\n| --- | --- |\n" + rows + "\n"
    chunker = StructureChunker(make_cfg(), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    assert len(chunks) > 1
    assert chunks[0].section == NO_SECTION
    max_tokens = make_cfg().max_tokens
    assert all(c.token_count <= max_tokens for c in chunks)
    # every table row survives intact
    all_content = "\n".join(c.content for c in chunks)
    for i in range(120):
        assert f"| row {i} | value |" in all_content
    # every batch carries the table header rows
    for c in chunks:
        assert "| h1 | h2 |" in c.content
        assert "| --- | --- |" in c.content


# --- Horizontal rule is not a boundary ---------------------------------------


def test_horizontal_rule_is_not_a_section_boundary():
    content = (
        "## A\n" + words(20) + "\n---\n" + words(20) + "\n## B\n" + words(20) + "\n"
    )
    chunker = StructureChunker(make_cfg(merge_short_sections=False), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    assert [c.section for c in chunks] == ["A", "B"]
    assert "---" in chunks[0].content


# --- merge_short_sections ------------------------------------------------------


def test_merge_short_sections_appends_to_previous_chunk():
    content = "## A\n" + words(10) + "\n## B\n" + words(10) + "\n"
    chunker = StructureChunker(make_cfg(merge_short_sections=True), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    assert len(chunks) == 1
    assert chunks[0].section == "A"
    assert "## A" in chunks[0].content
    assert "## B" in chunks[0].content


def test_merged_section_subsplit_keeps_all_contents():
    # Section B is 80 words: < min_section_tokens (100) so it is MERGED into
    # the first group, yet > max_tokens (50) so it sub-splits into >=2
    # contents. Regression: the second sub-content used to be silently
    # dropped; every sentence of B must survive in some chunk.
    sentences = [" ".join(f"w{i}-{k}" for k in range(10)) + "." for i in range(8)]
    content = (
        "## A\n" + words(10) + "\n"
        "## B\n" + " ".join(sentences) + "\n"
        "## C\n" + words(10) + "\n"
    )
    cfg = StructureChunkingConfig(
        max_tokens=50,
        overlap_tokens=10,
        merge_short_sections=True,
        min_section_tokens=100,
    )
    chunker = StructureChunker(cfg, WordCounter())
    chunks = chunker.chunk(make_doc(content))
    assert len(chunks) >= 2  # B sub-split, so its tail lands in a later chunk
    all_content = "\n".join(c.content for c in chunks)
    for sent in sentences:
        assert sent in all_content, f"lost sentence: {sent!r}"


# --- Heading escapes -----------------------------------------------------------


def test_escaped_heading_normalized_in_trail():
    content = "## \\- РФ -\n" + words(20) + "\n"
    chunker = StructureChunker(make_cfg(merge_short_sections=False), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    assert chunks[0].section == "- РФ -"
    assert "- РФ -" in chunks[0].content


# --- Ordering ------------------------------------------------------------------


def test_chunk_index_is_sequential():
    content = (
        "## A\n" + words(20) + "\n## B\n" + words(20) + "\n## C\n" + words(20) + "\n"
    )
    chunker = StructureChunker(make_cfg(merge_short_sections=False), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    assert [c.chunk_index for c in chunks] == list(range(len(chunks)))
    assert [c.section for c in chunks] == ["A", "B", "C"]


# --- Content preservation ------------------------------------------------------


def test_no_heading_or_table_row_lost():
    content = (
        "## Alpha\n" + words(20) + "\n"
        "## Beta\n" + words(10) + "\n"
        "| h1 | h2 |\n| --- | --- |\n| A | 1 |\n| B | 2 |\n"
        "## Gamma\n" + words(20) + "\n"
    )
    chunker = StructureChunker(make_cfg(merge_short_sections=False), WordCounter())
    chunks = chunker.chunk(make_doc(content))
    assert [c.section for c in chunks] == ["Alpha", "Beta", "Gamma"]
    beta = next(c for c in chunks if c.section == "Beta")
    assert "## Beta" in beta.content
    assert "| A | 1 |" in beta.content
    assert "| B | 2 |" in beta.content
    assert all(c.token_count > 0 for c in chunks)
    assert all(c.char_count == len(c.content) for c in chunks)
