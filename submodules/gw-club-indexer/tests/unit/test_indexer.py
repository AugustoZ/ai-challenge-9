"""Offline unit tests for the indexer orchestrator (Task 13).

Everything runs against a tiny tmp_path corpus with ``MockEmbedder`` (dim=8)
and ``WhitespaceTokenizer`` injected, so no model ever loads and no network
access happens. Real-model behaviour is covered by the manual QA run.
"""

from __future__ import annotations

import dataclasses
import json
import sqlite3
import sys
import types
from pathlib import Path

import numpy as np
import pytest

from gwclub_indexer.chunking.tokens import WhitespaceTokenizer
from gwclub_indexer.config import load_config
from gwclub_indexer.indexer import HFTokenizer, IndexStats, run_index
from gwclub_indexer.storage.faiss_store import FaissStore
from gwclub_indexer.storage.sqlite_store import SQLiteStore

# --------------------------------------------------------------------------- #
# Helpers / fixtures
# --------------------------------------------------------------------------- #
_DOC_A = """---
title: Настройка клубов
locale: ru
---

## Регистрация

Клуб — это сообщество владельцев автомобилей. Регистрация клуба занимает
несколько минут и требует подтверждения электронной почты администратора.

## Взносы

Ежемесячный взнос списывается автоматически. При просрочке клуб переходит
в архив, данные сохраняются в течение тридцати календарных дней.
"""

_DOC_B = """---
title: Тарифы и оплата
locale: ru
---

Тарифный план выбирается при создании клуба. Сменить тариф можно в любой
момент в разделе настроек, изменения применяются с начала расчётного периода.

Оплата принимается банковскими картами и через сервис быстрых платежей.
Чеки приходят на электронную почту владельца клуба автоматически.
"""


def _write_corpus(docs_root: Path) -> None:
    (docs_root / "ru").mkdir(parents=True, exist_ok=True)
    (docs_root / "ru" / "clubs.md").write_text(_DOC_A, encoding="utf-8")
    (docs_root / "ru" / "tariffs.md").write_text(_DOC_B, encoding="utf-8")


def _make_cfg(docs_root: Path, index_dir: Path):
    base = load_config()
    paths = dataclasses.replace(
        base.paths,
        docs_root=str(docs_root),
        index_dir=str(index_dir),
        groundtruth_file=str(index_dir / "groundtruth.yaml"),
        reports_dir=str(index_dir / "reports"),
    )
    return dataclasses.replace(base, paths=paths)


@pytest.fixture
def offline_cfg(tmp_path):
    """Config pointing at a tiny tmp corpus with all artifacts under tmp."""
    docs_root = tmp_path / "docs"
    index_dir = tmp_path / "index"
    _write_corpus(docs_root)
    return _make_cfg(docs_root, index_dir)


class _WrongWidthEmbedder:
    """Embedder whose vectors have the wrong width — used to trip validation."""

    def __init__(self, width: int = 4) -> None:
        self._width = width

    @property
    def model_name(self) -> str:
        return "wrong-width"

    @property
    def dim(self) -> int:
        return 8  # lies: real output width is self._width

    def embed_passages(self, texts: list[str]) -> np.ndarray:
        return np.zeros((len(texts), self._width), dtype=np.float32)

    def embed_query(self, text: str) -> np.ndarray:
        return np.zeros(self._width, dtype=np.float32)


# --------------------------------------------------------------------------- #
# Full run: both strategies -> artifacts + metadata
# --------------------------------------------------------------------------- #
def test_run_index_both_strategies_creates_artifacts(offline_cfg, mock_embedder):
    stats_fixed = run_index(
        offline_cfg, "fixed", embedder=mock_embedder, tokenizer=WhitespaceTokenizer()
    )
    stats_struct = run_index(
        offline_cfg,
        "structure",
        embedder=mock_embedder,
        tokenizer=WhitespaceTokenizer(),
    )

    # IndexStats shape
    for stats, strategy in ((stats_fixed, "fixed"), (stats_struct, "structure")):
        assert isinstance(stats, IndexStats)
        assert stats.strategy == strategy
        assert stats.n_docs == 2
        assert stats.n_chunks > 0
        assert stats.dim == 8
        assert stats.elapsed_s >= 0.0
        assert (
            stats.faiss_path
            == Path(offline_cfg.paths.index_dir) / f"index-{strategy}.faiss"
        )
        assert (
            stats.sqlite_path
            == Path(offline_cfg.paths.index_dir) / f"index-{strategy}.sqlite"
        )

    # All 4 artifact files exist, no leftover .tmp files
    index_dir = Path(offline_cfg.paths.index_dir)
    for strategy in ("fixed", "structure"):
        assert (index_dir / f"index-{strategy}.faiss").is_file()
        assert (index_dir / f"index-{strategy}.sqlite").is_file()
    assert not list(index_dir.glob("*.tmp"))

    # SQLite: counts, distinct ids, meta
    for strategy in ("fixed", "structure"):
        store = SQLiteStore(index_dir / f"index-{strategy}.sqlite")
        chunks = store.all_chunks(strategy=strategy)
        assert store.count_chunks(strategy=strategy) == len(chunks) > 0
        assert len({c.chunk_id for c in chunks}) == len(chunks)
        assert all(c.strategy == strategy for c in chunks)
        # faiss_row == position in the input list (0..N-1), unique
        conn = sqlite3.connect(index_dir / f"index-{strategy}.sqlite")
        try:
            rows = conn.execute(
                "SELECT faiss_row FROM chunks ORDER BY faiss_row"
            ).fetchall()
        finally:
            conn.close()
        assert [r[0] for r in rows] == list(range(len(chunks)))
        assert store.get_meta("dim") == "8"
        assert store.get_meta("embedding_model") == "mock-embedder"
        assert store.get_meta("strategy") == strategy
        assert (
            store.get_meta("prefix_mode") == "none"
        )  # MockEmbedder has no PREFIX_MODE
        params = json.loads(store.get_meta("params_json"))
        assert params["max_tokens"] > 0 and params["overlap_tokens"] >= 0
        if strategy == "fixed":
            assert set(params) == {"max_tokens", "overlap_tokens"}
        else:
            assert set(params) == {
                "max_tokens",
                "overlap_tokens",
                "merge_short_sections",
                "min_section_tokens",
            }
            assert isinstance(params["merge_short_sections"], bool)


# --------------------------------------------------------------------------- #
# Idempotency: re-run produces identical chunk ids
# --------------------------------------------------------------------------- #
def test_run_index_idempotent_chunk_ids(offline_cfg, mock_embedder):
    first = run_index(
        offline_cfg, "fixed", embedder=mock_embedder, tokenizer=WhitespaceTokenizer()
    )
    store = SQLiteStore(first.sqlite_path)
    ids_before = sorted(c.chunk_id for c in store.all_chunks(strategy="fixed"))

    second = run_index(
        offline_cfg, "fixed", embedder=mock_embedder, tokenizer=WhitespaceTokenizer()
    )
    ids_after = sorted(
        c.chunk_id for c in SQLiteStore(second.sqlite_path).all_chunks(strategy="fixed")
    )

    assert ids_before == ids_after
    assert len(ids_after) == store.count_chunks(strategy="fixed")


# --------------------------------------------------------------------------- #
# FAISS artifact: loadable, row count and dim match
# --------------------------------------------------------------------------- #
def test_faiss_file_loads_with_expected_len(offline_cfg, mock_embedder):
    stats = run_index(
        offline_cfg,
        "structure",
        embedder=mock_embedder,
        tokenizer=WhitespaceTokenizer(),
    )
    loaded = FaissStore.load(stats.faiss_path)
    assert loaded.dim == 8
    assert len(loaded) == stats.n_chunks

    # Query vector round-trip: top-1 row is a valid faiss_row in the sqlite store
    sqlite_store = SQLiteStore(stats.sqlite_path)
    chunks = sqlite_store.all_chunks(strategy="structure")
    query = mock_embedder.embed_query(chunks[0].content)
    hits = loaded.search(query, k=1)
    assert len(hits) == 1
    row, score = hits[0]
    conn = sqlite3.connect(stats.sqlite_path)
    try:
        found = conn.execute(
            "SELECT COUNT(*) FROM chunks WHERE faiss_row = ?", (row,)
        ).fetchone()[0]
    finally:
        conn.close()
    assert found == 1
    assert score == pytest.approx(1.0, abs=1e-5)  # self-match on identical text


# --------------------------------------------------------------------------- #
# Strategy validation
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("strategy", ["both", "Fixed", "", "semantic"])
def test_run_index_rejects_unknown_strategy(offline_cfg, strategy):
    with pytest.raises(ValueError, match="strategy"):
        run_index(offline_cfg, strategy, embedder=mock_embedder_dummy())


def mock_embedder_dummy():  # never reached: validation fires before embedding
    from gwclub_indexer.embeddings.mock import MockEmbedder

    return MockEmbedder(dim=8)


# --------------------------------------------------------------------------- #
# Validation failure: wrong-width vectors -> ValueError, nothing written
# --------------------------------------------------------------------------- #
def test_validation_failure_writes_nothing(offline_cfg):
    index_dir = Path(offline_cfg.paths.index_dir)
    bad = _WrongWidthEmbedder(width=4)

    with pytest.raises(ValueError, match="dimension|width|shape"):
        run_index(offline_cfg, "fixed", embedder=bad, tokenizer=WhitespaceTokenizer())

    # No artifacts (not even the index directory) were created.
    assert not index_dir.exists() or not any(index_dir.iterdir())


def test_validation_failure_duplicate_chunk_ids(offline_cfg, mock_embedder):
    """Two identical chunks from the chunker must trip the uniqueness check."""
    docs_root = Path(offline_cfg.paths.docs_root)
    (docs_root / "ru" / "clubs.md").write_text(_DOC_A + _DOC_A, encoding="utf-8")

    class _DuplicatingEmbedder:
        """Pass-through embedder; duplication comes from a stubbed chunker."""

        model_name = "mock-embedder"
        dim = 8

        def embed_passages(self, texts):
            return np.zeros((len(texts), 8), dtype=np.float32)

        def embed_query(self, text):
            return np.zeros(8, dtype=np.float32)

    import gwclub_indexer.indexer as indexer_mod

    original_chunker = indexer_mod.FixedChunker

    class _DupChunker(original_chunker):
        def chunk(self, document):
            chunks = super().chunk(document)
            if chunks:
                dup = dataclasses.replace(chunks[0])
                return chunks + [dup]
            return chunks

    indexer_mod.FixedChunker = _DupChunker
    try:
        with pytest.raises(ValueError, match="unique"):
            run_index(
                offline_cfg,
                "fixed",
                embedder=_DuplicatingEmbedder(),
                tokenizer=WhitespaceTokenizer(),
            )
    finally:
        indexer_mod.FixedChunker = original_chunker

    index_dir = Path(offline_cfg.paths.index_dir)
    assert not index_dir.exists() or not any(index_dir.iterdir())


# --------------------------------------------------------------------------- #
# HFTokenizer: lazy transformers import, count via encode()
# --------------------------------------------------------------------------- #
def test_hf_tokenizer_lazy_import_and_count(monkeypatch):
    calls: list[str] = []

    class _FakeTokenizer:
        def encode(self, text: str, add_special_tokens: bool = False) -> list[int]:
            assert add_special_tokens is False
            return list(range(len(text.split())))

    fake_transformers = types.ModuleType("transformers")

    def _from_pretrained(model: str):
        calls.append(model)
        return _FakeTokenizer()

    fake_transformers.AutoTokenizer = types.SimpleNamespace(
        from_pretrained=_from_pretrained
    )
    monkeypatch.setitem(sys.modules, "transformers", fake_transformers)

    tok = HFTokenizer("intfloat/multilingual-e5-small")
    assert calls == []  # nothing loaded at construction time

    assert tok.count_tokens("один два три") == 3
    assert calls == ["intfloat/multilingual-e5-small"]  # lazy load happened once
    assert tok.count_tokens("ещё раз") == 2
    assert calls == ["intfloat/multilingual-e5-small"]  # cached, no second load


# --------------------------------------------------------------------------- #
# Empty-corpus guard: embedder with zero passages must not crash stores
# --------------------------------------------------------------------------- #
def test_run_index_empty_corpus(offline_cfg, mock_embedder):
    """A corpus where every doc is filtered out yields 0 chunks and valid artifacts."""
    docs_root = Path(offline_cfg.paths.docs_root)
    (docs_root / "ru" / "clubs.md").write_text("коротко", encoding="utf-8")
    (docs_root / "ru" / "tariffs.md").write_text("тоже коротко", encoding="utf-8")

    stats = run_index(
        offline_cfg, "fixed", embedder=mock_embedder, tokenizer=WhitespaceTokenizer()
    )
    assert stats.n_docs == 0
    assert stats.n_chunks == 0
    assert SQLiteStore(stats.sqlite_path).count_chunks(strategy="fixed") == 0
    assert len(FaissStore.load(stats.faiss_path)) == 0


# --------------------------------------------------------------------------- #
# CLI wiring: `gwclub index` subcommand (run_index stubbed -> stays offline)
# --------------------------------------------------------------------------- #
def test_cli_index_subcommand_runs_per_strategy(monkeypatch, capsys):
    from gwclub_indexer import cli

    calls: list[str] = []

    def _fake_run_index(cfg, strategy, **kwargs):
        calls.append(strategy)
        return IndexStats(
            strategy=strategy,
            n_docs=33,
            n_chunks=412 if strategy == "fixed" else 251,
            dim=384,
            elapsed_s=8.34,
            faiss_path=Path(cfg.paths.index_dir) / f"index-{strategy}.faiss",
            sqlite_path=Path(cfg.paths.index_dir) / f"index-{strategy}.sqlite",
        )

    monkeypatch.setattr(cli, "run_index", _fake_run_index)

    assert cli.main(["index"]) == 0
    assert calls == ["fixed", "structure"]
    lines = capsys.readouterr().out.strip().splitlines()
    assert len(lines) == 2
    assert lines[0].startswith("index[fixed]: 33 docs → 412 chunks, dim 384, 8.3s → ")
    assert lines[1].startswith(
        "index[structure]: 33 docs → 251 chunks, dim 384, 8.3s → "
    )


def test_cli_index_single_strategy(monkeypatch, capsys):
    from gwclub_indexer import cli

    calls: list[str] = []

    def _fake_run_index(cfg, strategy, **kwargs):
        calls.append(strategy)
        return IndexStats(
            strategy=strategy,
            n_docs=1,
            n_chunks=2,
            dim=8,
            elapsed_s=0.0,
            faiss_path=Path("x.faiss"),
            sqlite_path=Path("x.sqlite"),
        )

    monkeypatch.setattr(cli, "run_index", _fake_run_index)

    assert cli.main(["index", "--strategy", "structure"]) == 0
    assert calls == ["structure"]
    assert len(capsys.readouterr().out.strip().splitlines()) == 1


def test_cli_index_rejects_bad_strategy():
    from gwclub_indexer.cli import main

    assert main(["index", "--strategy", "semantic"]) == 2


def test_embedding_error_importable():
    from gwclub_indexer.embeddings import EmbeddingError

    assert issubclass(EmbeddingError, Exception)
