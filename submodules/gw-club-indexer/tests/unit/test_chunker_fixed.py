"""Unit tests for the fixed (token-window) chunker (Task 8).

Mock tokenizer: :class:`WhitespaceTokenizer` (1 whitespace-separated word == 1
token). Configuration constructed directly because the config dataclasses are
frozen and this test must not depend on the shared ``tests/conftest.py``.
"""

from __future__ import annotations

from itertools import pairwise

from gwclub_indexer.chunking.fixed import FixedChunker
from gwclub_indexer.chunking.tokens import WhitespaceTokenizer
from gwclub_indexer.config import FixedChunkingConfig
from gwclub_indexer.loader import Document

CFG = FixedChunkingConfig(max_tokens=400, overlap_tokens=60)
TOKENIZER = WhitespaceTokenizer()


def make_doc(content: str) -> Document:
    return Document(
        source="ru/test.md",
        title="Test doc",
        section_h1="",
        locale="ru",
        content=content,
    )


def chunk(content: str, cfg: FixedChunkingConfig = CFG):
    return FixedChunker(cfg, TOKENIZER).chunk(make_doc(content))


def make_sentence(index: int) -> str:
    """Sentence of exactly 10 whitespace tokens (9 words + word with period)."""
    words = [f"w{i}" for i in range(index * 10, index * 10 + 9)]
    words.append(f"w{index * 10 + 9}.")
    return " ".join(words)


def make_950_word_content() -> str:
    return " ".join(make_sentence(i) for i in range(95))


def shared_overlap_tokens(previous_units: list[str], next_units: list[str]) -> int:
    """Sum of tokens in the longest whole-unit suffix/prefix shared by two chunks."""
    limit = min(len(previous_units), len(next_units))
    count = 0
    for k in range(limit, 0, -1):
        if previous_units[-k:] == next_units[:k]:
            count = sum(TOKENIZER.count_tokens(u) for u in previous_units[-k:])
            break
    return count


# --- short text -------------------------------------------------------------


def test_short_text_single_chunk():
    chunks = chunk("Short text here.")
    assert len(chunks) == 1
    assert chunks[0].chunk_index == 0
    assert chunks[0].token_count == 3
    assert chunks[0].strategy == "fixed"


# --- 950-word budget/overlap ------------------------------------------------


def test_950_words_three_chunks_budget():
    chunks = chunk(make_950_word_content())
    assert len(chunks) == 3
    assert all(c.token_count <= 400 for c in chunks)


def test_950_words_chunk_index_sequential():
    chunks = chunk(make_950_word_content())
    assert [c.chunk_index for c in chunks] == [0, 1, 2]


def test_950_words_adjacent_overlap_shared():
    chunks = chunk(make_950_word_content())
    for a, b in pairwise(chunks):
        overlap = shared_overlap_tokens(a.content.split("\n"), b.content.split("\n"))
        assert 0 < overlap <= 60


# --- table atomicity and overflow -------------------------------------------


def test_table_block_not_split_when_fits():
    table = "| A | B |\n| 1 | 2 |\n| 3 | 4 |"
    chunks = chunk("Intro sentence.\n" + table)
    assert len(chunks) == 1
    assert table in chunks[0].content


def test_oversized_table_single_chunk_overflow():
    rows = ["| a | b |"] + [f"| {i} | value_{i} |" for i in range(100)]
    table_block = "\n".join(rows)
    chunks = chunk(table_block)
    assert len(chunks) == 1
    assert table_block in chunks[0].content
    assert chunks[0].token_count > 400


# --- sentence boundaries ------------------------------------------------------


def test_no_mid_sentence_chunk_edges():
    sentences = [
        "Alpha beta gamma delta.",
        "Epsilon zeta eta theta.",
        "Iota kappa lambda mu.",
        "Nu xi omicron pi.",
    ]
    content = "\n".join(sentences)
    chunks = chunk(content, cfg=FixedChunkingConfig(max_tokens=8, overlap_tokens=3))
    assert len(chunks) >= 2
    for c in chunks:
        units = c.content.split("\n")
        assert units[0][0].isupper()
        assert units[-1][-1] in ".!?…"


# --- heading preservation -----------------------------------------------------


def test_heading_lines_preserved_verbatim():
    content = "# Главная\n## Установка\nУстановите программу за минуту."
    chunks = chunk(content)
    joined = "\n".join(c.content for c in chunks)
    assert "# Главная" in joined
    assert "## Установка" in joined


# --- no content lost ----------------------------------------------------------


def test_no_unit_content_lost():
    sentences = [make_sentence(i) for i in range(95)]
    chunks = chunk(" ".join(sentences))
    joined = "\n".join(c.content for c in chunks)
    for sentence in sentences:
        assert sentence in joined


# --- regression: overlap walk must always make progress -----------------------


def test_short_chunk_before_oversized_terminates():
    # First unit (3 tokens) fits entirely within overlap_tokens (60) so the
    # overlap walk would previously walk back to the chunk start -> infinite
    # loop. The giant table unit forces a mid-document close, then a fresh start.
    rows = ["| a | b |"] + [f"| {i} | value_{i} |" for i in range(100)]
    table_block = "\n".join(rows)
    content = "Short intro here.\n" + table_block + "\nTail follows here."
    chunks = chunk(content)
    assert len(chunks) == 3
    assert [c.chunk_index for c in chunks] == [0, 1, 2]
    assert chunks[1].token_count > 400
    joined = "\n".join(c.content for c in chunks)
    assert "Short intro here." in joined
    assert table_block in joined
    assert "Tail follows here." in joined
