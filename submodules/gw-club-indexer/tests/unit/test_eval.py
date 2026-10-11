"""Unit tests for gwclub_indexer.eval (Task 15): strategy-comparison metrics.

TDD, fully offline, deterministic. A tiny synthetic corpus (2 documents with known
headings) is indexed DIRECTLY with ``SQLiteStore``/``FaissStore`` (the
``gwclub_indexer.indexer`` orchestrator is intentionally NOT imported). The
``MockEmbedder(dim=8)`` gives deterministic vectors so a query identical to a
relevant chunk's content is retrieved perfectly -> Recall@5 == MRR == 1.0.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, replace
from pathlib import Path

import pytest

from gwclub_indexer.chunking.base import NO_SECTION, Chunk, make_chunk_id
from gwclub_indexer.config import PathsConfig, load_config
from gwclub_indexer.eval.engine import (
    _heading_in_content,
    _relevant_chunk_ids,
    run_eval,
)
from gwclub_indexer.eval.groundtruth import GroundTruthItem, save_ground_truth
from gwclub_indexer.eval.report import generate_report, run_eval_cli
from gwclub_indexer.storage.faiss_store import FaissStore
from gwclub_indexer.storage.sqlite_store import SQLiteStore

# --- Synthetic corpus ------------------------------------------------------- #
C_QUERY = (
    "## Установка прошивки\n\n"
    "Первый шаг установки прошивки. Второй шаг установки прошивки. "
    "Третий шаг установки прошивки."
)
C_A = (
    "## Настройка параметров\n\n"
    "Первый параметр настройки системы. Второй параметр настройки системы."
)
C_B = (
    "## Настройка параметров\n\n"
    "Третий параметр настройки системы. Четвертый параметр настройки системы."
)
SECT_QUERY = "Установка прошивки"
SECT_CFG = "Настройка параметров"


def _chunk(source, strategy, index, section, content) -> Chunk:
    return Chunk(
        chunk_id=make_chunk_id(source, strategy, index),
        source=source,
        title="Тестовый документ",
        section=section,
        locale="ru",
        chunk_index=index,
        strategy=strategy,
        token_count=len(content.split()),
        char_count=len(content),
        content=content,
    )


def _fixed_chunks() -> list[Chunk]:
    return [
        _chunk("ru/install.md", "fixed", 0, NO_SECTION, C_QUERY),
        _chunk("ru/config.md", "fixed", 0, NO_SECTION, C_A),
        _chunk("ru/config.md", "fixed", 1, NO_SECTION, C_B),
    ]


def _struct_chunks() -> list[Chunk]:
    return [
        _chunk("ru/install.md", "structure", 0, SECT_QUERY, C_QUERY),
        _chunk("ru/config.md", "structure", 0, SECT_CFG, C_A),
        _chunk("ru/config.md", "structure", 1, SECT_CFG, C_B),
    ]


def _build_index(index_dir, strategy, chunks, embedder) -> None:
    """Write FAISS + SQLite directly via the stores (indexer module NOT used)."""
    sqlite = SQLiteStore(index_dir / f"index-{strategy}.sqlite")
    sqlite.replace_all(
        chunks,
        embedding_model=embedder.model_name,
        dim=embedder.dim,
        prefix_mode="e5",
        strategy=strategy,
        params_json=json.dumps({"max_tokens": 400, "overlap_tokens": 60}),
    )
    faiss = FaissStore(dim=embedder.dim)
    faiss.add_vectors(embedder.embed_passages([c.content for c in chunks]))
    faiss.save(index_dir / f"index-{strategy}.faiss")


def _make_cfg(tmp_path: Path):
    cfg = load_config()
    paths = PathsConfig(
        docs_root=str(tmp_path / "docs"),
        index_dir=str(tmp_path / "index"),
        groundtruth_file=str(tmp_path / "eval" / "groundtruth.yaml"),
        reports_dir=str(tmp_path / "reports"),
    )
    return replace(cfg, paths=paths)


def _write_gt(cfg, items: list[GroundTruthItem]) -> Path:
    gt_path = Path(cfg.paths.groundtruth_file)
    gt_path.parent.mkdir(parents=True, exist_ok=True)
    save_ground_truth(items, gt_path)
    return gt_path


def _query_item() -> GroundTruthItem:
    return GroundTruthItem(
        query=C_QUERY, source="ru/install.md", section=SECT_QUERY, origin="heading"
    )


@dataclass
class World:
    tmp_path: Path
    index_dir: Path
    embedder: object
    cfg: object


@pytest.fixture
def world(tmp_path: Path, mock_embedder) -> World:
    cfg = _make_cfg(tmp_path)
    index_dir = tmp_path / "index"
    index_dir.mkdir()
    _build_index(index_dir, "fixed", _fixed_chunks(), mock_embedder)
    _build_index(index_dir, "structure", _struct_chunks(), mock_embedder)
    return World(tmp_path, index_dir, mock_embedder, cfg)


# --------------------------------------------------------------------------- #
# Relevance mapping (the canonical 4-rule decision)
# --------------------------------------------------------------------------- #
def test_relevance_mapping_rules(world: World) -> None:
    item = _query_item()
    # fixed: every section is (документ), relevance comes only from the heading
    # line appearing inside the chunk content (rule 4).
    assert _relevant_chunk_ids(_fixed_chunks(), item) == {
        make_chunk_id("ru/install.md", "fixed", 0)
    }
    # structure: exact section match (rule 1).
    assert _relevant_chunk_ids(_struct_chunks(), item) == {
        make_chunk_id("ru/install.md", "structure", 0)
    }
    # _heading_in_content: (документ) never matches; a real heading does.
    assert _heading_in_content(NO_SECTION, C_QUERY) is False
    assert _heading_in_content(SECT_QUERY, C_QUERY) is True


# --------------------------------------------------------------------------- #
# Known-answer: perfect retrieval for BOTH strategies
# --------------------------------------------------------------------------- #
def test_known_answer_perfect_retrieval(world: World) -> None:
    """Query == relevant chunk content => Recall@5 == 1.0 and MRR == 1.0."""
    _write_gt(world.cfg, [_query_item()])
    results = run_eval(world.cfg, embedder=world.embedder)
    for strat in ("fixed", "structure"):
        s = results["strategies"][strat]
        assert s["recall_at_5"] == 1.0, f"{strat}: recall@5 != 1.0"
        assert s["mrr_at_10"] == 1.0, f"{strat}: mrr@10 != 1.0"
        assert s["n_items"] == 1
        assert s["items"][0]["relevant_count"] == 1
        assert s["items"][0]["top5_ids"][0] == s["items"][0]["top10_ids"][0]


def test_empty_relevant_set_contributes_zero(world: World) -> None:
    """A ground-truth item bound to a source absent from the index contributes 0."""
    item = GroundTruthItem(
        query="Нерелевантный запрос",
        source="ru/missing.md",
        section="Секция X",
        origin="heading",
    )
    _write_gt(world.cfg, [item])
    results = run_eval(world.cfg, embedder=world.embedder)
    for strat in ("fixed", "structure"):
        s = results["strategies"][strat]
        assert s["recall_at_5"] == 0.0, f"{strat}: recall@5 != 0.0"
        assert s["mrr_at_10"] == 0.0, f"{strat}: mrr@10 != 0.0"
        assert s["n_items_with_hit"] == 0
        assert s["items"][0]["relevant_count"] == 0


# --------------------------------------------------------------------------- #
# Report artifacts
# --------------------------------------------------------------------------- #
def test_report_winner_rule_before_metrics_table(world: World) -> None:
    _write_gt(world.cfg, [_query_item()])
    results = run_eval(world.cfg, embedder=world.embedder)
    out = Path(world.cfg.paths.reports_dir) / "comparison.md"
    generate_report(results, world.cfg, out)
    text = out.read_text(encoding="utf-8")
    winner_idx = text.index("## Правило победителя")
    results_idx = text.index("## Результаты")
    assert winner_idx < results_idx
    # The first metrics-table row must come after the winner rule.
    assert winner_idx < text.index("| Recall@5")


def test_eval_results_json_written(world: World) -> None:
    _write_gt(world.cfg, [_query_item()])
    results = run_eval(world.cfg, embedder=world.embedder)
    p = Path(world.cfg.paths.reports_dir) / "eval-results.json"
    assert p.is_file()
    data = json.loads(p.read_text(encoding="utf-8"))
    assert data["params"]["n_items"] == 1
    assert "fixed" in data["strategies"] and "structure" in data["strategies"]
    # The returned raw dict matches what was persisted.
    assert (
        data["strategies"]["fixed"]["recall_at_5"]
        == results["strategies"]["fixed"]["recall_at_5"]
    )


# --------------------------------------------------------------------------- #
# Verdict consistency with the winner rule
# --------------------------------------------------------------------------- #
def _make_results(fixed_r5, struct_r5, fixed_mrr, struct_mrr) -> dict:
    def strat(r5, mrr, n_chunks):
        return {
            "n_chunks": n_chunks,
            "params": {"max_tokens": 400, "overlap_tokens": 60},
            "recall_at_5": r5,
            "recall_at_10": r5,
            "mrr_at_10": mrr,
            "n_items": 2,
            "n_items_with_hit": 1,
            "coherence": 0.12,
            "length": {
                "min": 12.0,
                "p50": 60.0,
                "p90": 300.0,
                "max": 520.0,
                "pct_below_30": 0.05,
                "pct_over_max_tokens": 0.02,
            },
            "items": [],
        }

    return {
        "params": {
            "n_docs": 2,
            "model": "mock-embedder",
            "provider": "local",
            "embedding_dim": 8,
            "n_items": 2,
            "origins": {"heading": 2, "faq": 0},
        },
        "strategies": {
            "fixed": strat(fixed_r5, fixed_mrr, 10),
            "structure": strat(struct_r5, struct_mrr, 12),
        },
    }


def _verdict_text(results, world: World) -> str:
    out = Path(world.cfg.paths.reports_dir) / "comparison.md"
    generate_report(results, world.cfg, out)
    text = out.read_text(encoding="utf-8")
    return text[text.index("## Вердикт") :]


def test_verdict_clear_structure_win(world: World) -> None:
    """fixed 0.30 vs structure 0.90: recall gap >= 5pp -> structure wins."""
    results = _make_results(0.30, 0.90, 0.50, 0.80)
    verdict = _verdict_text(results, world)
    assert "Победитель: structure" in verdict
    assert "для `search`: **structure**" in verdict


def test_verdict_parity(world: World) -> None:
    """|recall diff| < 5pp and equal MRR -> parity, default search = structure."""
    results = _make_results(0.50, 0.52, 0.50, 0.50)
    verdict = _verdict_text(results, world)
    assert "Победитель: паритет" in verdict
    assert "для `search`: **structure**" in verdict


# --------------------------------------------------------------------------- #
# CLI error path (missing index artifacts -> exit 1, no model load)
# --------------------------------------------------------------------------- #
def test_cli_eval_missing_index_returns_one(tmp_path: Path, capsys) -> None:
    cfg = _make_cfg(tmp_path)
    _write_gt(cfg, [_query_item()])
    config_path = tmp_path / "config.toml"
    config_path.write_text(
        f"[paths]\n"
        f'docs_root = "{cfg.paths.docs_root}"\n'
        f'index_dir = "{cfg.paths.index_dir}"\n'
        f'groundtruth_file = "{cfg.paths.groundtruth_file}"\n'
        f'reports_dir = "{cfg.paths.reports_dir}"\n'
        '[embedding]\nprovider = "local"\n',
        encoding="utf-8",
    )
    args = argparse.Namespace(config=str(config_path))
    code = run_eval_cli(args)
    captured = capsys.readouterr()
    assert code == 1
    assert captured.err.startswith("error:")
    assert "index-fixed" in captured.err
