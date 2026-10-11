"""Eval engine: per-strategy retrieval metrics + coherence + length dist (Task 15).

The engine compares the two chunking strategies ("fixed" and "structure") on a
deterministic ground-truth file. Everything here is offline and deterministic --
**no LLM/API is ever called**. Relevance is bound at the ``(source, section)``
level (not ``chunk_id``) and mapped to each strategy's own chunks, which is the
only way to compare chunking strategies without introducing bias.

Public API::

    from gwclub_indexer.eval.engine import run_eval

    results = run_eval(cfg, embedder=embedder)  # embedder optional (defaults to make_embedder)
"""

from __future__ import annotations

import json
import re
import sqlite3
from pathlib import Path

import numpy as np

from gwclub_indexer.chunking.base import NO_SECTION, normalize_heading
from gwclub_indexer.config import Config
from gwclub_indexer.embeddings import Embedder, make_embedder
from gwclub_indexer.eval.groundtruth import (
    GroundTruthItem,
    generate_ground_truth,
    load_ground_truth,
    save_ground_truth,
)
from gwclub_indexer.loader import load_documents
from gwclub_indexer.storage.faiss_store import FaissStore
from gwclub_indexer.storage.sqlite_store import SQLiteStore

__all__ = ["STRATEGIES", "TOP_K", "run_eval"]

#: Strategies compared by the engine (order is fixed: fixed first, structure second).
STRATEGIES: tuple[str, ...] = ("fixed", "structure")
#: Number of retrieved results per query (top-k used for MRR and recall@10).
TOP_K: int = 10

#: Markdown ATX heading line (``## foo``), used by ``_heading_in_content``.
_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+)$")
#: Sentence boundary used for the coherence metric.
_SENTENCE_RE = re.compile(r"(?<=[.!?])\s+")


# --------------------------------------------------------------------------- #
# Relevance mapping (canonical 4-rule decision)
# --------------------------------------------------------------------------- #
def _heading_in_content(section: str, content: str) -> bool:
    """Return True if the LAST trail component of ``section`` is a heading in ``content``.

    ``section`` is a trail like ``"A > B > C"``; only the last component
    (``"C"``) is matched, since that is the most specific heading a chunk can
    carry. The ``(документ)`` fallback label is never a real heading, so it
    short-circuits to False. The component is compared against markdown heading
    lines whose normalized text equals it; a substring fallback is accepted for
    robustness (e.g. a heading stored with trailing punctuation/whitespace).
    """
    component = section.split(" > ")[-1].strip()
    if component == NO_SECTION:
        return False
    for line in content.splitlines():
        m = _HEADING_RE.match(line.rstrip())
        if m is not None and normalize_heading(m.group(2)) == component:
            return True
    # Substring fallback: some heading lines carry inline markdown (bold/links)
    # that normalize_heading does not strip; accept a bare substring match.
    return component in content


def _relevant_chunk_ids(chunks: list, item: GroundTruthItem) -> set[str]:
    """Relevant chunk_ids for ``item`` under the canonical 4-rule mapping.

    A chunk is relevant to an ``(source, section)`` ground-truth item when it
    comes from the same source AND it is (a) in the exact section, (b) a
    sub-chunk of a section whose parent trail is ``item.section`` (section
    split into several chunks), (c) a parent section of ``item.section``, or
    (d) its content actually carries the item's heading (covers the ``fixed``
    strategy, whose chunks always have section ``(документ)``).
    """
    relevant: set[str] = set()
    for c in chunks:
        if c.source != item.source:
            continue
        if (
            c.section == item.section
            or c.section.startswith(item.section + " > ")
            or item.section.startswith(c.section + " > ")
            or _heading_in_content(item.section, c.content)
        ):
            relevant.add(c.chunk_id)
    return relevant


# --------------------------------------------------------------------------- #
# Low-level math
# --------------------------------------------------------------------------- #
def _cosine(a: np.ndarray, b: np.ndarray) -> float:
    """Cosine similarity between two vectors (normalizes both)."""
    a = np.asarray(a, dtype=np.float64).reshape(-1)
    b = np.asarray(b, dtype=np.float64).reshape(-1)
    na = float(np.linalg.norm(a))
    nb = float(np.linalg.norm(b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return float(np.dot(a, b) / (na * nb))


# --------------------------------------------------------------------------- #
# Per-strategy computation
# --------------------------------------------------------------------------- #
def _row_to_chunk_map(sqlite_path: Path, strategy: str) -> dict[int, str]:
    """Map FAISS row -> chunk_id by reading the store's ``faiss_row`` column.

    ``SQLiteStore.all_chunks`` intentionally does NOT expose ``faiss_row``, so a
    read-only connection to the store is used here. ``faiss_row`` is the index
    position the indexer wrote each chunk's vector to in FAISS (0..N-1).
    """
    uri = f"{Path(sqlite_path).resolve().as_uri()}?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    try:
        rows = conn.execute(
            "SELECT chunk_id, faiss_row FROM chunks WHERE strategy = ?",
            (strategy,),
        ).fetchall()
    finally:
        conn.close()
    return {int(row): cid for cid, row in rows}


def _load_strategy(
    cfg: Config, strategy: str, embedder: Embedder
) -> tuple[list, FaissStore, dict[int, str]]:
    """Load chunks, FAISS index and row->chunk map for one strategy.

    Raises:
        FileNotFoundError: any of the four index artifacts is missing, with a
            message naming the exact file and how to build it.
    """
    index_dir = Path(cfg.paths.index_dir)
    faiss_path = index_dir / f"index-{strategy}.faiss"
    sqlite_path = index_dir / f"index-{strategy}.sqlite"
    missing = [str(p) for p in (faiss_path, sqlite_path) if not p.is_file()]
    if missing:
        raise FileNotFoundError(
            "Missing index artifacts for strategy "
            f"{strategy!r}: {', '.join(missing)}. Run ``gwclub index "
            f"--strategy {strategy}`` (or ``--strategy both``) first."
        )
    store = SQLiteStore(sqlite_path)
    chunks = store.all_chunks(strategy=strategy)
    if not chunks:
        raise FileNotFoundError(
            f"No chunks stored for strategy {strategy!r} in {sqlite_path}."
        )
    row_map = _row_to_chunk_map(sqlite_path, strategy)
    faiss = FaissStore.load(faiss_path)
    if faiss.dim != embedder.dim:
        raise ValueError(
            f"dimension mismatch for strategy {strategy!r}: index dim "
            f"{faiss.dim} != embedder dim {embedder.dim} "
            f"({embedder.model_name})."
        )
    return chunks, faiss, row_map


def _item_metrics(
    chunks: list,
    faiss: FaissStore,
    row_map: dict[int, str],
    embedder: Embedder,
    items: list[GroundTruthItem],
) -> dict:
    """Compute per-item and aggregate retrieval metrics for one strategy."""
    per_item: list[dict] = []
    recall_5_sum = 0.0
    recall_10_sum = 0.0
    mrr_sum = 0.0
    n_hit = 0
    for item in items:
        relevant = _relevant_chunk_ids(chunks, item)
        rows = faiss.search(embedder.embed_query(item.query), TOP_K)
        ordered_ids = [row_map[r] for r, _ in rows]
        top5_ids = ordered_ids[:5]
        top10_ids = ordered_ids[:10]
        hit5 = len(set(top5_ids) & relevant)
        hit10 = len(set(top10_ids) & relevant)
        recall_5_sum += hit5 / max(1, len(relevant))
        recall_10_sum += hit10 / max(1, len(relevant))
        # MRR: 1 / rank (1-based) of the first hit in top10, 0 if no hit.
        mrr = 0.0
        for rank, cid in enumerate(top10_ids, start=1):
            if cid in relevant:
                mrr = 1.0 / rank
                break
        mrr_sum += mrr
        if relevant and hit10 > 0:
            n_hit += 1
        per_item.append(
            {
                "query": item.query,
                "source": item.source,
                "section": item.section,
                "origin": item.origin,
                "relevant_count": len(relevant),
                "top5_ids": top5_ids,
                "top10_ids": top10_ids,
                "recall_at_5": hit5 / max(1, len(relevant)),
            }
        )
    n_items = len(items)
    return {
        "n_items": n_items,
        "n_items_with_hit": n_hit,
        "recall_at_5": recall_5_sum / n_items if n_items else 0.0,
        "recall_at_10": recall_10_sum / n_items if n_items else 0.0,
        "mrr_at_10": mrr_sum / n_items if n_items else 0.0,
        "items": per_item,
    }


def _coherence(chunks: list, embedder: Embedder) -> float | None:
    """Intra-vs-inter silhouette coherence for one strategy.

    ``intra`` = cosine(chunk vector, mean of its sentence vectors); ``inter`` =
    cosine(chunk vector, vector of the NEXT chunk in the same document).
    Eligible chunks: >= 2 sentences AND a following chunk in the same source.
    Too few eligible chunks -> ``None`` (the report renders "n/a").
    """
    ordered = sorted(chunks, key=lambda c: (c.source, c.chunk_index, c.chunk_id))
    scores: list[float] = []
    for i, chunk in enumerate(ordered):
        sentences = [s.strip() for s in _SENTENCE_RE.split(chunk.content) if s.strip()]
        if len(sentences) < 2:
            continue
        # Next chunk in the same source (ordered contiguously by source).
        nxt = (
            ordered[i + 1]
            if i + 1 < len(ordered) and ordered[i + 1].source == chunk.source
            else None
        )
        if nxt is None:
            continue
        emb_chunk = embedder.embed_passages([chunk.content])[0]
        emb_sents = embedder.embed_passages(sentences)
        intra = _cosine(emb_chunk, emb_sents.mean(axis=0))
        emb_next = embedder.embed_passages([nxt.content])[0]
        inter = _cosine(emb_chunk, emb_next)
        scores.append(intra - inter)
    if not scores:
        return None
    return float(np.mean(scores))


def _length_stats(chunks: list, max_tokens: int) -> dict:
    """Length distribution over chunk ``token_count`` values."""
    counts = np.asarray([c.token_count for c in chunks], dtype=np.float64)
    n = len(counts) if len(counts) else 1
    return {
        "n_chunks": len(counts),
        "min": float(counts.min()) if len(counts) else 0.0,
        "p50": float(np.percentile(counts, 50)) if len(counts) else 0.0,
        "p90": float(np.percentile(counts, 90)) if len(counts) else 0.0,
        "max": float(counts.max()) if len(counts) else 0.0,
        # Overflow is legitimate only for atomic tables, so it is reported,
        # not penalized, here.
        "pct_below_30": float((counts < 30).sum() / n) if len(counts) else 0.0,
        "pct_over_max_tokens": float((counts > max_tokens).sum() / n)
        if len(counts)
        else 0.0,
    }


def _strategy_metrics(
    cfg: Config,
    strategy: str,
    embedder: Embedder,
    items: list[GroundTruthItem],
) -> dict:
    """Assemble the full metrics block for a single strategy."""
    chunks, faiss, row_map = _load_strategy(cfg, strategy, embedder)
    metrics = _item_metrics(chunks, faiss, row_map, embedder, items)
    max_tokens = (
        cfg.fixed_chunking.max_tokens
        if strategy == "fixed"
        else cfg.structure_chunking.max_tokens
    )
    overlap = (
        cfg.fixed_chunking.overlap_tokens
        if strategy == "fixed"
        else cfg.structure_chunking.overlap_tokens
    )
    return {
        "n_chunks": len(chunks),
        "params": {"max_tokens": max_tokens, "overlap_tokens": overlap},
        "coherence": _coherence(chunks, embedder),
        "length": _length_stats(chunks, max_tokens),
        **metrics,
    }


# --------------------------------------------------------------------------- #
# Public entry point
# --------------------------------------------------------------------------- #
def run_eval(cfg: Config, *, embedder: Embedder | None = None) -> dict:
    """Run the offline strategy comparison and return all raw metrics.

    Args:
        cfg: pipeline config (paths + chunking params).
        embedder: optional embedder; defaults to ``make_embedder(cfg)`` so a
            real run uses the configured model, while offline tests inject a
            ``MockEmbedder``.

    Returns:
        A dict of every raw metric, including per-item retrieval info. The raw
        dict is also persisted to ``cfg.paths.reports_dir/eval-results.json``.

    Raises:
        FileNotFoundError: the ground-truth file is absent and no corpus is
            available, or any strategy's index artifacts are missing.
    """
    embedder = embedder if embedder is not None else make_embedder(cfg)

    gt_path = Path(cfg.paths.groundtruth_file)
    if gt_path.is_file():
        items = load_ground_truth(gt_path)
    else:
        docs = load_documents(cfg)
        items = generate_ground_truth(cfg, docs)
        save_ground_truth(items, gt_path)

    docs = load_documents(cfg)  # for the report's corpus stats
    strategies: dict[str, dict] = {}
    for strategy in STRATEGIES:
        strategies[strategy] = _strategy_metrics(cfg, strategy, embedder, items)

    results = {
        "params": {
            "n_docs": len(docs),
            "model": embedder.model_name,
            "provider": cfg.embedding_provider,
            "embedding_dim": embedder.dim,
            "n_items": len(items),
            "origins": {
                "heading": sum(1 for i in items if i.origin == "heading"),
                "faq": sum(1 for i in items if i.origin == "faq"),
            },
        },
        "strategies": strategies,
    }

    out = Path(cfg.paths.reports_dir) / "eval-results.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    return results
