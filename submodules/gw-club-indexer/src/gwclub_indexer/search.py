"""Vector search over the per-strategy FAISS + SQLite index pair (Task 14).

Pure vector (cosine) search: no re-ranking, no BM25, no hybrid scoring; the
stage-2 post-search pipeline (re-ranking + threshold filter) is owned by the
serve layer on top of this module. The
pair ``index-{strategy}.faiss`` + ``index-{strategy}.sqlite`` (built by Task
13's indexer) is loaded read-only; chunk metadata is joined back to FAISS row
ids via a small stdlib-sqlite3 query (``SQLiteStore.all_chunks`` deliberately
does not expose ``faiss_row``, and this module must not edit that store).

Public API::

    from gwclub_indexer.search import search, run_search_cli, build_search_subparser

    results = search(cfg, "structure", "как подключить android auto", 5)

CLI handler contract (wired by Task 16 into ``cli.py`` in one line)::

    search_sub = build_search_subparser(subparsers)   # argparse setup
    # run_search_cli(args) -> int                      # exit code handler

Exit codes: 0 ok; 1 bad query / missing index (ValueError, FileNotFoundError);
2 embedding-model mismatch (IndexMismatchError). Errors are printed as
``error: ...`` on stderr -- users never see a traceback.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

from gwclub_indexer.chunking.base import Chunk
from gwclub_indexer.config import Config, load_config
from gwclub_indexer.embeddings import Embedder, make_embedder
from gwclub_indexer.storage.faiss_store import FaissStore
from gwclub_indexer.storage.sqlite_store import SQLiteStore

__all__ = [
    "IndexMismatchError",
    "SearchResult",
    "build_search_subparser",
    "run_search_cli",
    "search",
]

_SNIPPET_CHARS = 200
_CLI_SNIPPET_CHARS = 80


class IndexMismatchError(Exception):
    """Index was built with a different embedding model/dim than requested."""


@dataclass(frozen=True)
class SearchResult:
    """One ranked hit: chunk identity, provenance, score and text preview."""

    chunk_id: str
    score: float
    source: str
    title: str
    section: str
    snippet: str


def _index_paths(cfg: Config, strategy: str) -> tuple[Path, Path]:
    index_dir = Path(cfg.paths.index_dir)
    return (
        index_dir / f"index-{strategy}.faiss",
        index_dir / f"index-{strategy}.sqlite",
    )


def _load_row_map(path: Path, strategy: str) -> dict[int, Chunk]:
    """Map ``faiss_row -> Chunk`` with a read-only stdlib sqlite3 query.

    ``SQLiteStore.all_chunks`` does not expose ``faiss_row``, and the search
    layer must not edit that module, so the join is done here directly. The
    connection is opened in read-only URI mode (``mode=ro``); ``as_uri()``
    percent-encodes spaces in the project path.
    """
    uri = f"{Path(path).resolve().as_uri()}?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    try:
        rows = conn.execute(
            "SELECT chunk_id, source, title, section, locale, chunk_index, "
            "strategy, token_count, char_count, content, faiss_row "
            "FROM chunks WHERE strategy = ?",
            (strategy,),
        ).fetchall()
    finally:
        conn.close()
    return {
        int(r[10]): Chunk(
            chunk_id=r[0],
            source=r[1],
            title=r[2],
            section=r[3],
            locale=r[4],
            chunk_index=int(r[5]),
            strategy=r[6],
            token_count=int(r[7]),
            char_count=int(r[8]),
            content=r[9],
        )
        for r in rows
    }


def _snippet(content: str) -> str:
    """First 200 chars of the chunk content, '…' appended when truncated."""
    if len(content) > _SNIPPET_CHARS:
        return content[:_SNIPPET_CHARS] + "…"
    return content


def search(
    cfg: Config,
    strategy: str,
    query: str,
    k: int,
    *,
    embedder: Embedder | None = None,
) -> list[SearchResult]:
    """Top-k pure vector (cosine) search against a per-strategy index pair.

    Args:
        cfg: Pipeline configuration (``cfg.paths.index_dir`` is searched).
        strategy: Index strategy name (``"fixed"`` or ``"structure"``).
        query: Non-empty query text.
        k: Number of results; clamped to the index size with a stderr warning.
        embedder: Optional pre-built embedder; default ``make_embedder(cfg)``.
            Tests inject ``MockEmbedder`` to stay offline.

    Returns:
        Results ordered by descending cosine score (FAISS inner product on
        L2-normalized vectors).

    Raises:
        ValueError: ``query`` is empty or whitespace-only.
        FileNotFoundError: either index artifact is missing.
        IndexMismatchError: index meta (embedding model / dim) disagrees with
            the embedder -- checked BEFORE any embedding is computed.
    """
    if not isinstance(query, str) or query.strip() == "":
        raise ValueError("query must be a non-empty string")

    faiss_path, sqlite_path = _index_paths(cfg, strategy)
    if not faiss_path.is_file():
        raise FileNotFoundError(
            f"index not built for strategy '{strategy}': expected {faiss_path}"
        )
    if not sqlite_path.is_file():
        raise FileNotFoundError(
            f"index not built for strategy '{strategy}': expected {sqlite_path}"
        )

    emb = embedder if embedder is not None else make_embedder(cfg)
    store = FaissStore.load(faiss_path)
    row_map = _load_row_map(sqlite_path, strategy)

    # Mismatch guard BEFORE embedding (cheap reads, no vector math).
    meta = SQLiteStore(sqlite_path)
    meta_model = meta.get_meta("embedding_model")
    meta_dim_raw = meta.get_meta("dim")
    meta_dim = int(meta_dim_raw) if meta_dim_raw is not None else -1
    if meta_model != emb.model_name or meta_dim != emb.dim:
        raise IndexMismatchError(
            f"embedding model mismatch: index built with {meta_model} "
            f"(dim={meta_dim}), requested {emb.model_name} (dim={emb.dim})"
        )

    n = len(store)
    if k > n:
        print(
            f"warning: requested top-k={k} but index '{strategy}' contains "
            f"only {n} vectors; clamping k to {n}",
            file=sys.stderr,
        )
        k = n

    rows = store.search(emb.embed_query(query), k)

    results: list[SearchResult] = []
    for row, score in rows:
        chunk = row_map.get(row)
        if chunk is None:
            # FAISS row with no sqlite metadata: the artifact pair is
            # inconsistent (never happens for a fully built index).
            raise RuntimeError(
                f"inconsistent index: faiss row {row} has no chunk metadata "
                f"in {sqlite_path.name} for strategy '{strategy}'"
            )
        results.append(
            SearchResult(
                chunk_id=chunk.chunk_id,
                score=float(score),
                source=chunk.source,
                title=chunk.title,
                section=chunk.section,
                snippet=_snippet(chunk.content),
            )
        )
    return results


# --------------------------------------------------------------------------- #
# CLI handler (wired by Task 16; cli.py itself is owned by Task 13)
# --------------------------------------------------------------------------- #
def run_search_cli(args: argparse.Namespace) -> int:
    """CLI handler for ``gwclub search``. Returns a process exit code.

    Reads ``args.config, strategy, k, json_flag, query`` (getattr with
    defaults for robustness). Errors print ``error: ...`` to stderr and never
    leak a traceback: exit 1 for ValueError/FileNotFoundError, exit 2 for
    IndexMismatchError.
    """
    try:
        config_path = getattr(args, "config", None)
        cfg = load_config(Path(config_path)) if config_path else load_config()
        strategy = getattr(args, "strategy", None) or cfg.default_search_strategy
        results = search(
            cfg,
            strategy,
            getattr(args, "query", ""),
            getattr(args, "k", 5),
        )
    except IndexMismatchError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except (ValueError, FileNotFoundError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if getattr(args, "json_flag", False):
        print(json.dumps([asdict(r) for r in results], ensure_ascii=False, indent=2))
        return 0

    for i, r in enumerate(results, 1):
        short = " ".join(r.snippet[:_CLI_SNIPPET_CHARS].split())
        print(f"{i}. {r.score:.4f}  {r.source}  —  {r.section}  —  {short}")
    return 0


def build_search_subparser(
    subparsers: argparse._SubParsersAction,
) -> argparse.ArgumentParser:
    """Register the ``search`` subcommand; Task 16 wires it in one line."""
    parser = subparsers.add_parser(
        "search",
        help="Search the vector index with a text query (pure cosine).",
    )
    parser.add_argument("query", help="query text (non-empty)")
    parser.add_argument(
        "--config", default=None, help="path to a config.toml override file"
    )
    parser.add_argument(
        "--strategy",
        choices=["fixed", "structure"],
        default=None,
        help="index strategy (default: cfg.default_search_strategy)",
    )
    parser.add_argument(
        "-k",
        "--top-k",
        dest="k",
        type=int,
        default=5,
        help="number of results to return (default: 5)",
    )
    parser.add_argument(
        "--json",
        dest="json_flag",
        action="store_true",
        help="emit results as JSON instead of human-readable lines",
    )
    parser.set_defaults(func=run_search_cli)
    return parser
