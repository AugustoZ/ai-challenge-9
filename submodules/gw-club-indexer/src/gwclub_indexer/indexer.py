"""Indexer orchestrator (Task 13): documents -> chunks -> vectors -> artifacts.

``run_index`` wires the full offline-deterministic pipeline for one chunking
strategy: load documents, chunk them, embed all chunk texts in one batch,
validate everything, and only then write the two artifacts atomically:

* ``index-<strategy>.faiss``  -- flat IP FAISS index over L2-normalized vectors
  (written to ``<path>.tmp`` first, then ``os.replace``d into place);
* ``index-<strategy>.sqlite`` -- chunk metadata + index meta via
  :meth:`SQLiteStore.replace_all` (already atomic by construction).

Re-running a strategy is a full rebuild (no incremental indexing): chunk ids
derive from ``(source, strategy, chunk_index)`` only, so a re-run produces the
identical id set.

The real tokenizer used in production is :class:`HFTokenizer`, a lazy wrapper
around ``transformers.AutoTokenizer`` — ``transformers`` is imported inside
the first ``count_tokens`` call so importing this module (and all offline unit
tests) never loads a model.

Public API::

    from gwclub_indexer.indexer import HFTokenizer, IndexStats, run_index

    stats = run_index(cfg, "fixed")   # or "structure"
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from pathlib import Path

from gwclub_indexer.chunking.base import Chunk
from gwclub_indexer.chunking.fixed import FixedChunker
from gwclub_indexer.chunking.structure import StructureChunker
from gwclub_indexer.chunking.tokens import Tokenizer
from gwclub_indexer.config import Config
from gwclub_indexer.embeddings import Embedder, make_embedder
from gwclub_indexer.loader import load_documents
from gwclub_indexer.storage.faiss_store import FaissStore
from gwclub_indexer.storage.sqlite_store import SQLiteStore

__all__ = ["HFTokenizer", "IndexStats", "run_index"]

_STRATEGIES: tuple[str, ...] = ("fixed", "structure")


class HFTokenizer:
    """Duck-typed ``Tokenizer`` backed by a HF ``AutoTokenizer``.

    The ``transformers`` import and the model download/load happen lazily on
    the first :meth:`count_tokens` call, so merely constructing this object
    (e.g. during test collection or CLI startup) is cheap and offline-safe.
    """

    def __init__(self, model_name: str) -> None:
        self._model_name = model_name
        self._tok = None  # lazy: HF tokenizer instance

    def _ensure(self):
        if self._tok is None:
            from transformers import AutoTokenizer  # lazy: heavy import

            self._tok = AutoTokenizer.from_pretrained(self._model_name)
        return self._tok

    def count_tokens(self, text: str) -> int:
        return len(self._ensure().encode(text, add_special_tokens=False))


@dataclass(frozen=True)
class IndexStats:
    """Summary of one :func:`run_index` invocation."""

    strategy: str
    n_docs: int
    n_chunks: int
    dim: int
    elapsed_s: float
    faiss_path: Path
    sqlite_path: Path


def _chunk_params(cfg: Config, strategy: str) -> dict:
    """Chunking parameters recorded in ``index_meta.params_json``."""
    if strategy == "fixed":
        return {
            "max_tokens": cfg.fixed_chunking.max_tokens,
            "overlap_tokens": cfg.fixed_chunking.overlap_tokens,
        }
    sc = cfg.structure_chunking
    return {
        "max_tokens": sc.max_tokens,
        "overlap_tokens": sc.overlap_tokens,
        "merge_short_sections": sc.merge_short_sections,
        "min_section_tokens": sc.min_section_tokens,
    }


def _validate(chunks: list[Chunk], vectors, dim: int) -> None:
    """Raise ValueError before any artifact is written on any mismatch."""
    if vectors.shape[0] != len(chunks):
        raise ValueError(
            f"chunk/vector count mismatch: {len(chunks)} chunks but "
            f"{vectors.shape[0]} vectors"
        )
    chunk_ids = [c.chunk_id for c in chunks]
    duplicates = sorted({cid for cid in chunk_ids if chunk_ids.count(cid) > 1})
    if duplicates:
        raise ValueError(f"chunk_ids are not unique: {duplicates[:5]}")
    if vectors.ndim != 2 or vectors.shape[1] != dim:
        raise ValueError(
            f"dimension mismatch: embedder.dim={dim}, got vectors with shape "
            f"{tuple(vectors.shape)}"
        )


def run_index(
    cfg: Config,
    strategy: str,
    *,
    embedder: Embedder | None = None,
    tokenizer: Tokenizer | None = None,
) -> IndexStats:
    """Build and persist the index for one chunking strategy.

    Args:
        cfg: Pipeline configuration (paths, chunking params, embedding model).
        strategy: ``"fixed"`` or ``"structure"``.
        embedder: Optional injected embedder (tests use ``MockEmbedder``);
            defaults to :func:`make_embedder(cfg)`.
        tokenizer: Optional injected duck-typed tokenizer (tests use
            ``WhitespaceTokenizer``); defaults to :class:`HFTokenizer` over
            ``cfg.local_embedding.model``.

    Returns:
        :class:`IndexStats` describing the run and artifact paths.

    Raises:
        ValueError: unknown strategy, or validation failed (count/dim/id
            mismatches) -- raised *before* any file is written.
    """
    if strategy not in _STRATEGIES:
        raise ValueError(
            f"unknown strategy {strategy!r}; expected one of {list(_STRATEGIES)}"
        )

    start = time.perf_counter()

    # 1) Load + 2) chunk (doc order is deterministic: sorted by source).
    docs = load_documents(cfg)
    if tokenizer is None:
        tokenizer = HFTokenizer(cfg.local_embedding.model)

    if strategy == "fixed":
        chunker = FixedChunker(cfg.fixed_chunking, tokenizer)
    else:
        chunker = StructureChunker(cfg.structure_chunking, tokenizer)

    chunks: list[Chunk] = []
    for doc in docs:
        chunks.extend(chunker.chunk(doc))

    # 3) Embed all chunk texts in one batch.
    if embedder is None:
        embedder = make_embedder(cfg)
    vectors = embedder.embed_passages([c.content for c in chunks])

    # 4) Validate BEFORE touching the filesystem.
    dim = int(embedder.dim)
    _validate(chunks, vectors, dim)

    # 5) Write artifacts atomically.
    index_dir = Path(cfg.paths.index_dir)
    index_dir.mkdir(parents=True, exist_ok=True)
    faiss_path = index_dir / f"index-{strategy}.faiss"
    sqlite_path = index_dir / f"index-{strategy}.sqlite"

    store = FaissStore(dim)
    store.add_vectors(vectors)  # L2-normalizes rows
    faiss_tmp = Path(str(faiss_path) + ".tmp")
    store.save(faiss_tmp)
    os.replace(faiss_tmp, faiss_path)  # atomic: final file never half-written

    SQLiteStore(sqlite_path).replace_all(
        chunks,
        embedding_model=embedder.model_name,
        dim=dim,
        prefix_mode=getattr(embedder, "PREFIX_MODE", "none"),
        strategy=strategy,
        params_json=json.dumps(_chunk_params(cfg, strategy), sort_keys=True),
    )

    return IndexStats(
        strategy=strategy,
        n_docs=len(docs),
        n_chunks=len(chunks),
        dim=dim,
        elapsed_s=time.perf_counter() - start,
        faiss_path=faiss_path,
        sqlite_path=sqlite_path,
    )
