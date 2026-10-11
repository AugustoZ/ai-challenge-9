"""Unit tests for the vector search module (Task 14). Fully offline.

Tmp indexes are built DIRECTLY with FaissStore + SQLiteStore + MockEmbedder
(never through ``gwclub_indexer.indexer`` -- that module belongs to a parallel
task). Chunking uses FixedChunker with WhitespaceTokenizer on 2 tiny synthetic
Documents; vectors come from ``MockEmbedder.embed_passages`` so the sha256
determinism contract makes "query == chunk content" an exact top-1 match.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import sqlite3
from itertools import pairwise
from pathlib import Path
from types import SimpleNamespace

import pytest

from gwclub_indexer.chunking.fixed import FixedChunker
from gwclub_indexer.chunking.tokens import WhitespaceTokenizer
from gwclub_indexer.config import FixedChunkingConfig, load_config
from gwclub_indexer.embeddings.mock import MockEmbedder
from gwclub_indexer.loader import Document
from gwclub_indexer.search import (
    IndexMismatchError,
    SearchResult,
    build_search_subparser,
    run_search_cli,
    search,
)
from gwclub_indexer.storage.faiss_store import FaissStore
from gwclub_indexer.storage.sqlite_store import SQLiteStore

STRATEGY = "fixed"
DIM = 8


# --------------------------------------------------------------------------- #
# Fixtures: build a tiny real index with the stores directly
# --------------------------------------------------------------------------- #
def _doc1_content() -> str:
    """3 sentences of 12/11/9 words -> 2 chunks at max_tokens=30."""
    return (
        "как подключить беспроводной android auto в автомобиле через меню "
        "bluetooth настроек смартфона.\n"
        "затем откройте настройки мультимедиа выберите раздел подключения "
        "и подтвердите сопряжение телефона.\n"
        "если соединение не устанавливается перезапустите головное "
        "устройство и повторите."
    )


def _doc2_content() -> str:
    """3 sentences of exactly 15 words -> 2 chunks at max_tokens=30."""
    return (
        "обновление прошивки головного устройства выполняется через меню "
        "настроек автомобиля после загрузки официального архива с сайта.\n"
        "сайта с архива официального загрузки после автомобиля настроек "
        "меню через выполняется устройства головного прошивки обновление.\n"
        "устройства выполняется через меню настроек автомобиля после "
        "загрузки официального архива с сайта обновление прошивки головного."
    )


def _doc3_content() -> str:
    """One 70-word sentence -> word-window fallback -> chunks > 200 chars."""
    words = [f"слово{i}" for i in range(70)]
    return " ".join(words) + "."


@pytest.fixture
def built(tmp_path: Path) -> SimpleNamespace:
    """Index pair (faiss + sqlite) for strategy 'fixed' built with stores."""
    mock = MockEmbedder(dim=DIM)
    chunker = FixedChunker(
        FixedChunkingConfig(max_tokens=30, overlap_tokens=6), WhitespaceTokenizer()
    )
    docs = [
        Document(
            source="ru/android_auto.md",
            title="Android Auto",
            section_h1="Android Auto",
            locale="ru",
            content=_doc1_content(),
        ),
        Document(
            source="ru/firmware.md",
            title="Прошивка магнитолы",
            section_h1="Прошивка",
            locale="ru",
            content=_doc2_content(),
        ),
        Document(
            source="ru/long_page.md",
            title="Длинная страница",
            section_h1="Длинная",
            locale="ru",
            content=_doc3_content(),
        ),
    ]
    chunks = [c for d in docs for c in chunker.chunk(d)]
    assert len(chunks) >= 4  # sanity: k-clamp tests need n < k

    vectors = mock.embed_passages([c.content for c in chunks])
    faiss_path = tmp_path / f"index-{STRATEGY}.faiss"
    store = FaissStore(mock.dim)
    store.add_vectors(vectors)
    store.save(faiss_path)

    sqlite_path = tmp_path / f"index-{STRATEGY}.sqlite"
    SQLiteStore(sqlite_path).replace_all(
        chunks,
        embedding_model=mock.model_name,
        dim=mock.dim,
        prefix_mode="none",
        strategy=STRATEGY,
        params_json="{}",
    )

    base = load_config()
    cfg = dataclasses.replace(
        base,
        paths=dataclasses.replace(base.paths, index_dir=str(tmp_path)),
    )

    # A config.toml override file for CLI tests (paths.index_dir -> tmp_path).
    (tmp_path / "config.toml").write_text(
        f'[paths]\nindex_dir = "{tmp_path}"\n', encoding="utf-8"
    )

    return SimpleNamespace(
        cfg=cfg,
        mock=mock,
        chunks=chunks,
        n=len(chunks),
        tmp_path=tmp_path,
    )


def _corrupt_meta(path: Path, key: str, value: str) -> None:
    conn = sqlite3.connect(path)
    try:
        conn.execute("UPDATE index_meta SET value = ? WHERE key = ?", (value, key))
        conn.commit()
    finally:
        conn.close()


def _cli_ns(tmp_path: Path, **overrides) -> argparse.Namespace:
    values = {
        "config": str(tmp_path / "config.toml"),
        "strategy": STRATEGY,
        "k": 5,
        "json_flag": False,
        "query": "запрос по индексу",
    }
    values.update(overrides)
    return argparse.Namespace(**values)


@pytest.fixture
def offline_cli(built, monkeypatch):
    """Route run_search_cli's make_embedder to the offline mock.

    The CLI handler uses ``make_embedder(cfg)``; the config provider is
    ``local``, which would import torch (crashes in this sandbox and is why
    local-embedder tests are integration-marked). Patching the name inside
    ``gwclub_indexer.search`` keeps the production path intact.
    """
    import gwclub_indexer.search as search_mod

    monkeypatch.setattr(search_mod, "make_embedder", lambda cfg: built.mock)
    return built


# --------------------------------------------------------------------------- #
# search(): happy paths
# --------------------------------------------------------------------------- #
class TestSearchHappyPath:
    def test_returns_searchresult_dataclass_with_fields(self, built):
        # Only an exact content string guarantees top-1 with the hash mock.
        target = built.chunks[0]
        results = search(
            built.cfg,
            STRATEGY,
            target.content,
            3,
            embedder=built.mock,
        )

        assert 1 <= len(results) <= 3
        first = results[0]
        assert isinstance(first, SearchResult)
        assert first.chunk_id == target.chunk_id
        assert first.source == "ru/android_auto.md"
        assert first.title == "Android Auto"
        assert first.section == "(документ)"  # FixedChunker has no sections
        assert first.snippet  # non-empty
        assert isinstance(first.score, float)

    def test_scores_are_non_increasing(self, built):
        results = search(
            built.cfg, STRATEGY, "прошивка устройства", 4, embedder=built.mock
        )

        scores = [r.score for r in results]
        assert len(scores) == 4
        assert all(a >= b for a, b in pairwise(scores))

    def test_query_identical_to_chunk_content_ranks_first_score_1(self, built):
        target = built.chunks[0]
        results = search(built.cfg, STRATEGY, target.content, 3, embedder=built.mock)

        assert results[0].chunk_id == target.chunk_id
        assert results[0].score == pytest.approx(1.0, abs=1e-5)

    def test_snippet_truncated_at_200_chars_with_ellipsis(self, built):
        long_chunk = next(c for c in built.chunks if len(c.content) > 200)
        results = search(
            built.cfg, STRATEGY, long_chunk.content, 1, embedder=built.mock
        )

        assert results[0].chunk_id == long_chunk.chunk_id
        assert results[0].snippet == long_chunk.content[:200] + "…"

    def test_snippet_short_content_not_truncated(self, built):
        short = built.chunks[0]
        assert len(short.content) <= 200
        results = search(built.cfg, STRATEGY, short.content, 1, embedder=built.mock)

        assert results[0].snippet == short.content

    def test_k_larger_than_index_clamped_with_stderr_warning(self, built, capsys):
        k_big = built.n + 50
        results = search(built.cfg, STRATEGY, "запрос", k_big, embedder=built.mock)

        assert len(results) == built.n
        err = capsys.readouterr().err
        assert "warning" in err
        assert "clamp" in err
        assert str(built.n) in err


# --------------------------------------------------------------------------- #
# search(): error paths
# --------------------------------------------------------------------------- #
class TestSearchErrors:
    def test_empty_query_raises_valueerror(self, built):
        with pytest.raises(ValueError, match="non-empty"):
            search(built.cfg, STRATEGY, "", 3, embedder=built.mock)

    def test_whitespace_query_raises_valueerror(self, built):
        with pytest.raises(ValueError, match="non-empty"):
            search(built.cfg, STRATEGY, "   \t\n", 3, embedder=built.mock)

    def test_missing_faiss_file_raises_filenotfound(self, built, tmp_path):
        empty_cfg = dataclasses.replace(
            built.cfg,
            paths=dataclasses.replace(
                built.cfg.paths, index_dir=str(tmp_path / "nope")
            ),
        )

        with pytest.raises(FileNotFoundError, match="index not built"):
            search(empty_cfg, STRATEGY, "запрос", 3, embedder=built.mock)

    def test_missing_sqlite_file_raises_filenotfound(self, built):
        (built.tmp_path / f"index-{STRATEGY}.sqlite").unlink()

        with pytest.raises(FileNotFoundError, match="index not built"):
            search(built.cfg, STRATEGY, "запрос", 3, embedder=built.mock)

    def test_filenotfound_message_names_strategy_and_path(self, built, tmp_path):
        empty_cfg = dataclasses.replace(
            built.cfg,
            paths=dataclasses.replace(
                built.cfg.paths, index_dir=str(tmp_path / "nope")
            ),
        )

        with pytest.raises(
            FileNotFoundError,
            match=r"strategy 'fixed'.*index-fixed\.faiss",
        ):
            search(empty_cfg, STRATEGY, "запрос", 3, embedder=built.mock)

    def test_meta_model_mismatch_raises_index_mismatch_error(self, built):
        _corrupt_meta(
            built.tmp_path / f"index-{STRATEGY}.sqlite",
            "embedding_model",
            "other-model",
        )

        with pytest.raises(IndexMismatchError, match="embedding model mismatch"):
            search(built.cfg, STRATEGY, "запрос", 3, embedder=built.mock)

    def test_meta_dim_mismatch_raises_index_mismatch_error(self, built):
        _corrupt_meta(built.tmp_path / f"index-{STRATEGY}.sqlite", "dim", "3")

        with pytest.raises(IndexMismatchError, match="embedding model mismatch"):
            search(built.cfg, STRATEGY, "запрос", 3, embedder=built.mock)

    def test_mismatch_guards_before_embedding(self, built):
        """Guard must fire before any embed_query call (no vector math)."""

        class Boom:
            model_name = "mock-embedder"
            dim = 8

            def embed_query(self, text):
                raise AssertionError("embedding attempted despite mismatch")

        _corrupt_meta(
            built.tmp_path / f"index-{STRATEGY}.sqlite",
            "embedding_model",
            "other-model",
        )

        with pytest.raises(IndexMismatchError):
            search(built.cfg, STRATEGY, "запрос", 3, embedder=Boom())


# --------------------------------------------------------------------------- #
# run_search_cli(): exit codes and output
# --------------------------------------------------------------------------- #
class TestRunSearchCli:
    def test_human_output_exit_0(self, offline_cli, capsys):
        built = offline_cli
        rc = run_search_cli(_cli_ns(built.tmp_path, query=built.chunks[0].content, k=3))

        out = capsys.readouterr()
        assert rc == 0
        lines = out.out.splitlines()
        assert len(lines) >= 1
        # "1. 0.8123  source  —  section  —  snippet80"
        assert lines[0].startswith("1. ")
        assert "  —  " in lines[0]
        assert "ru/android_auto.md" in lines[0]

    def test_json_output_parses_exit_0(self, offline_cli, capsys):
        built = offline_cli
        rc = run_search_cli(
            _cli_ns(built.tmp_path, json_flag=True, query=built.chunks[0].content, k=3)
        )

        captured = capsys.readouterr()
        assert rc == 0
        payload = json.loads(captured.out)
        assert isinstance(payload, list) and len(payload) == 3
        assert set(payload[0]) == {
            "chunk_id",
            "score",
            "source",
            "title",
            "section",
            "snippet",
        }
        assert payload[0]["chunk_id"] == built.chunks[0].chunk_id
        assert payload[0]["score"] == pytest.approx(1.0, abs=1e-5)

    def test_empty_query_exit_1_no_traceback(self, built, capsys):
        rc = run_search_cli(_cli_ns(built.tmp_path, query="   "))

        captured = capsys.readouterr()
        assert rc == 1
        assert captured.err.startswith("error: ")
        assert "non-empty" in captured.err
        assert "Traceback" not in captured.err and "Traceback" not in captured.out

    def test_missing_index_exit_1(self, built, capsys):
        for suffix in (".faiss", ".sqlite"):
            (built.tmp_path / f"index-{STRATEGY}{suffix}").unlink()

        rc = run_search_cli(_cli_ns(built.tmp_path, query="запрос"))

        captured = capsys.readouterr()
        assert rc == 1
        assert captured.err.startswith("error: ")
        assert "index not built" in captured.err
        assert "Traceback" not in captured.err

    def test_mismatch_exit_2(self, offline_cli, capsys):
        built = offline_cli
        _corrupt_meta(
            built.tmp_path / f"index-{STRATEGY}.sqlite",
            "embedding_model",
            "other-model",
        )

        rc = run_search_cli(_cli_ns(built.tmp_path, query="запрос"))

        captured = capsys.readouterr()
        assert rc == 2
        assert captured.err.startswith("error: ")
        assert "embedding model mismatch" in captured.err
        assert "Traceback" not in captured.err


# --------------------------------------------------------------------------- #
# build_search_subparser(): wiring surface for Task 16
# --------------------------------------------------------------------------- #
class TestBuildSearchSubparser:
    def _parser(self) -> argparse.ArgumentParser:
        parser = argparse.ArgumentParser(prog="gwclub")
        subs = parser.add_subparsers(dest="command", metavar="COMMAND")
        build_search_subparser(subs)
        return parser

    def test_defaults(self):
        ns = self._parser().parse_args(["search", "привет мир"])

        assert ns.query == "привет мир"
        assert ns.config is None
        assert ns.strategy is None  # None -> cfg.default_search_strategy
        assert ns.k == 5
        assert ns.json_flag is False

    def test_all_options_parse(self):
        ns = self._parser().parse_args(
            [
                "search",
                "как auto",
                "-k",
                "3",
                "--json",
                "--strategy",
                "fixed",
                "--config",
                "config.toml",
            ]
        )

        assert ns.query == "как auto"
        assert ns.k == 3
        assert ns.json_flag is True
        assert ns.strategy == "fixed"
        assert ns.config == "config.toml"

    def test_strategy_rejects_unknown_choice(self):
        with pytest.raises(SystemExit):
            self._parser().parse_args(["search", "q", "--strategy", "bogus"])

    def test_wires_run_search_cli_as_func_default(self):
        ns = self._parser().parse_args(["search", "q"])

        assert ns.func is run_search_cli
