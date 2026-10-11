"""Unit tests for SQLiteStore (Task 5).

Covers: atomic replace_all (tmp -> os.replace), idempotent full rebuild,
schema NOT NULL enforcement, strategy filtering, ordering, meta roundtrip,
faiss_row assignment by insertion order, and parent-dir creation.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from gwclub_indexer.chunking.base import Chunk, make_chunk_id
from gwclub_indexer.storage.sqlite_store import SQLiteStore


def _mk(
    source: str,
    strategy: str,
    idx: int,
    content: str,
    title: str = "Title",
    section: str = "(документ)",
) -> Chunk:
    return Chunk(
        chunk_id=make_chunk_id(source, strategy, idx),
        source=source,
        title=title,
        section=section,
        locale="ru",
        chunk_index=idx,
        strategy=strategy,
        token_count=len(content.split()),
        char_count=len(content),
        content=content,
    )


def _chunks() -> list[Chunk]:
    return [
        _mk("doc-a.md", "fixed", 0, "alpha text"),
        _mk("doc-a.md", "fixed", 1, "beta text"),
        _mk("doc-b.md", "structure", 0, "gamma text", title="B", section="Sec > Sub"),
    ]


META_KWARGS = {
    "embedding_model": "mock-8",
    "dim": 8,
    "prefix_mode": "section",
    "strategy": "both",
    "params_json": '{"max_tokens": 4096}',
}


@pytest.fixture
def db_path(tmp_index_dir: Path) -> Path:
    return tmp_index_dir / "index.sqlite"


@pytest.fixture
def store(db_path: Path) -> SQLiteStore:
    return SQLiteStore(db_path)


def test_replace_all_creates_valid_db_and_removes_tmp(store, db_path):
    n = store.replace_all(_chunks(), **META_KWARGS)
    assert n == 3
    assert db_path.exists()
    assert not db_path.with_name(db_path.name + ".tmp").exists()
    conn = sqlite3.connect(db_path)
    try:
        assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        tables = {
            r[0]
            for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
        }
        assert {"chunks", "index_meta"} <= tables
    finally:
        conn.close()


def test_replace_all_is_idempotent(store, db_path):
    chunks = _chunks()
    assert store.replace_all(chunks, **META_KWARGS) == 3
    first_count = store.count_chunks()
    first_rows = store.all_chunks()
    assert store.replace_all(chunks, **META_KWARGS) == 3
    assert store.count_chunks() == first_count == 3
    assert store.all_chunks() == first_rows
    conn = sqlite3.connect(db_path)
    try:
        dupes = conn.execute(
            "SELECT chunk_id FROM chunks GROUP BY chunk_id HAVING COUNT(*) > 1"
        ).fetchall()
        assert dupes == []
    finally:
        conn.close()


def test_schema_enforces_not_null(store, db_path):
    store.replace_all(_chunks(), **META_KWARGS)
    conn = sqlite3.connect(db_path)
    try:
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                "INSERT INTO chunks (chunk_id, source, title, section, locale, "
                "chunk_index, strategy, token_count, char_count, content, faiss_row) "
                "VALUES ('x', 's', NULL, 'sec', 'ru', 0, 'fixed', 1, 1, 'c', 0)"
            )
    finally:
        conn.close()


def test_replace_all_is_full_rebuild(store, db_path):
    store.replace_all(_chunks(), **META_KWARGS)
    smaller = _chunks()[:1]
    assert store.replace_all(smaller, **META_KWARGS) == 1
    assert store.count_chunks() == 1
    assert store.all_chunks()[0].chunk_id == _chunks()[0].chunk_id


def test_all_chunks_filters_by_strategy(store, db_path):
    store.replace_all(_chunks(), **META_KWARGS)
    fixed = store.all_chunks(strategy="fixed")
    assert [c.source for c in fixed] == ["doc-a.md", "doc-a.md"]
    assert all(c.strategy == "fixed" for c in fixed)
    structure = store.all_chunks(strategy="structure")
    assert [c.source for c in structure] == ["doc-b.md"]
    assert store.count_chunks(strategy="fixed") == 2
    assert store.count_chunks(strategy="structure") == 1
    assert store.count_chunks() == 3


def test_all_chunks_ordered_by_source_then_index(store, db_path):
    store.replace_all(list(reversed(_chunks())), **META_KWARGS)
    got = [(c.source, c.chunk_index, c.strategy) for c in store.all_chunks()]
    assert got == [
        ("doc-a.md", 0, "fixed"),
        ("doc-a.md", 1, "fixed"),
        ("doc-b.md", 0, "structure"),
    ]


def test_chunks_roundtrip_fields(store, db_path):
    store.replace_all(_chunks(), **META_KWARGS)
    rows = store.all_chunks()
    assert all(isinstance(r, Chunk) for r in rows)
    c = next(r for r in rows if r.source == "doc-b.md")
    assert (c.title, c.section, c.locale, c.content, c.token_count, c.char_count) == (
        "B",
        "Sec > Sub",
        "ru",
        "gamma text",
        2,
        10,
    )


def test_get_meta_roundtrip_and_unknown(store, db_path):
    store.replace_all(_chunks(), **META_KWARGS)
    assert store.get_meta("embedding_model") == "mock-8"
    assert store.get_meta("dim") == "8"
    assert store.get_meta("prefix_mode") == "section"
    assert store.get_meta("strategy") == "both"
    assert store.get_meta("params_json") == '{"max_tokens": 4096}'
    assert store.get_meta("nonexistent") is None
    # built_at must exist as ISO string, but its value is never asserted
    # (determinism is about chunk ids/counts, not timestamps).
    built_at = store.get_meta("built_at")
    assert isinstance(built_at, str) and built_at


def test_faiss_row_assigned_by_insertion_order(store, db_path):
    chunks = list(reversed(_chunks()))
    store.replace_all(chunks, **META_KWARGS)
    conn = sqlite3.connect(db_path)
    try:
        rows = conn.execute("SELECT chunk_id, faiss_row FROM chunks").fetchall()
    finally:
        conn.close()
    assert dict(rows) == {c.chunk_id: i for i, c in enumerate(chunks)}


def test_parent_dir_created_automatically(tmp_index_dir):
    path = tmp_index_dir / "nested" / "index.sqlite"
    store = SQLiteStore(path)
    assert store.replace_all(_chunks(), **META_KWARGS) == 3
    assert store.count_chunks() == 3
