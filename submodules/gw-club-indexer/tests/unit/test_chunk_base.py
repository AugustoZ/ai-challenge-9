"""Unit tests for chunking.base: Chunk, make_chunk_id, normalize_heading, section_to_trail (Task 4)."""

import hashlib
import subprocess
import sys

from gwclub_indexer.chunking.base import (
    Chunk,
    make_chunk_id,
    normalize_heading,
    section_to_trail,
)

# --- make_chunk_id -----------------------------------------------------------


def test_make_chunk_id_deterministic_and_matches_spec():
    a = make_chunk_id("ru/home.md", "fixed", 0)
    b = make_chunk_id("ru/home.md", "fixed", 0)
    assert a == b
    assert len(a) == 16
    # spec: sha256(f"{source}|{strategy}|{chunk_index}")[:16]
    assert a == hashlib.sha256(b"ru/home.md|fixed|0").hexdigest()[:16]


def test_make_chunk_id_distinguishes_components():
    base = make_chunk_id("ru/home.md", "fixed", 0)
    assert make_chunk_id("ru/home.md", "structure", 0) != base
    assert make_chunk_id("ru/home.md", "fixed", 1) != base
    assert make_chunk_id("en/home.md", "fixed", 0) != base


def test_make_chunk_id_stable_across_processes():
    code = (
        "from gwclub_indexer.chunking.base import make_chunk_id;"
        "print(make_chunk_id('ru/home.md', 'fixed', 0))"
    )
    out = subprocess.run(
        [sys.executable, "-c", code],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    assert out == make_chunk_id("ru/home.md", "fixed", 0)


# --- Chunk dataclass ----------------------------------------------------------


def test_chunk_dataclass_fields():
    c = Chunk(
        chunk_id="abc123def4567890",
        source="ru/home.md",
        title="ROX Premium",
        section="Установка",
        locale="ru",
        chunk_index=0,
        strategy="fixed",
        token_count=250,
        char_count=1200,
        content="...",
    )
    assert c.chunk_id == "abc123def4567890"
    assert c.source == "ru/home.md"
    assert c.title == "ROX Premium"
    assert c.section == "Установка"
    assert c.locale == "ru"
    assert c.chunk_index == 0
    assert c.strategy == "fixed"
    assert c.token_count == 250
    assert c.char_count == 1200
    assert c.content == "..."


# --- normalize_heading ---------------------------------------------------------


def test_normalize_heading_basic_cases():
    # strip markdown heading markers + unescape \- + collapse whitespace
    assert normalize_heading("## \\- РФ -") == "- РФ -"
    # collapse double spaces, no heading marker at all
    assert normalize_heading("###  Установка  прошивки") == "Установка прошивки"
    assert normalize_heading("") == ""
    assert normalize_heading("# Simple") == "Simple"
    # real corpus heading: numbered, must survive intact
    assert normalize_heading("## 2. Приложение Premium") == "2. Приложение Premium"


def test_normalize_heading_backslash_sequences_stay_sane():
    # \\ -> \ (escaped backslash)
    assert normalize_heading("## C:\\\\dir") == "C:\\dir"
    # \n (backslash + letter) is NOT a markdown escape -> unchanged
    assert normalize_heading("## C:\\dir\\n") == "C:\\dir\\n"
    # multiple escapes collapse cleanly
    assert normalize_heading("## \\* bold \\*") == "* bold *"


# --- section_to_trail -----------------------------------------------------------


def test_section_to_trail_joins_with_arrow():
    assert section_to_trail(["ROX Premium", "Engine Sound", "Установка"]) == (
        "ROX Premium > Engine Sound > Установка"
    )


def test_section_to_trail_empty_fallback():
    assert section_to_trail([]) == "(документ)"


def test_section_to_trail_normalizes_each_heading():
    assert section_to_trail(["## \\- РФ -", "###  Установка "]) == "- РФ - > Установка"
