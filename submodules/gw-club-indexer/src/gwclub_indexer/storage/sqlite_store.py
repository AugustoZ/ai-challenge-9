"""SQLite storage for index chunks and index-level metadata (Task 5).

Vectors live in FAISS (see ``faiss_store.py``); this store persists chunk
text/metadata plus ``index_meta`` (embedding model, dim, ...) that the
search/eval layers use for mismatch guards. The write model is a full
atomic rebuild (``replace_all``) — there is deliberately no incremental
single-chunk update API.
"""

from __future__ import annotations

import os
import sqlite3
from datetime import UTC, datetime
from pathlib import Path

from gwclub_indexer.chunking.base import Chunk

__all__ = ["SQLiteStore"]

_SCHEMA = """
CREATE TABLE IF NOT EXISTS chunks (
    chunk_id    TEXT PRIMARY KEY,
    source      TEXT NOT NULL,
    title       TEXT NOT NULL,
    section     TEXT NOT NULL,
    locale      TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    strategy    TEXT NOT NULL,
    token_count INTEGER NOT NULL,
    char_count  INTEGER NOT NULL,
    content     TEXT NOT NULL,
    faiss_row   INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS index_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

_CHUNK_COLUMNS = (
    "chunk_id",
    "source",
    "title",
    "section",
    "locale",
    "chunk_index",
    "strategy",
    "token_count",
    "char_count",
    "content",
    "faiss_row",
)
_CHUNK_VALUES = ",".join("?" * len(_CHUNK_COLUMNS))


class SQLiteStore:
    """Chunk + metadata store over a single SQLite file.

    ``path`` is the final database file. The schema is created on first
    use. ``replace_all`` builds the complete new state in ``<path>.tmp``
    and atomically swaps it into place, so the final file is never left
    half-written.
    """

    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)

    @property
    def path(self) -> Path:
        return self._path

    def _connect(self, path: Path) -> sqlite3.Connection:
        path.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(path)
        conn.row_factory = sqlite3.Row
        conn.executescript(_SCHEMA)
        return conn

    def replace_all(
        self,
        chunks: list[Chunk],
        *,
        embedding_model: str,
        dim: int,
        prefix_mode: str,
        strategy: str,
        params_json: str,
    ) -> int:
        """Atomically rebuild the store: full DELETE+INSERT in one transaction
        on a temporary database, then ``os.replace`` onto the final path.

        ``faiss_row`` for each chunk is its position in the input list
        (0..N-1) — the FAISS row the indexer will write it to.
        Returns the number of chunks stored.
        """
        tmp = Path(str(self._path) + ".tmp")
        if tmp.exists():
            tmp.unlink()
        meta = {
            "embedding_model": str(embedding_model),
            "dim": str(int(dim)),
            "prefix_mode": str(prefix_mode),
            "strategy": str(strategy),
            "params_json": str(params_json),
            # ISO 8601 UTC; informational only — determinism is about
            # chunk ids/counts, never this timestamp.
            "built_at": datetime.now(UTC).isoformat(),
        }
        conn = self._connect(tmp)
        try:
            with conn:  # single transaction: clear, insert chunks, insert meta
                conn.execute("DELETE FROM chunks")
                conn.execute("DELETE FROM index_meta")
                conn.executemany(
                    f"INSERT INTO chunks ({','.join(_CHUNK_COLUMNS)}) VALUES ({_CHUNK_VALUES})",
                    [
                        (
                            c.chunk_id,
                            c.source,
                            c.title,
                            c.section,
                            c.locale,
                            c.chunk_index,
                            c.strategy,
                            c.token_count,
                            c.char_count,
                            c.content,
                            faiss_row,
                        )
                        for faiss_row, c in enumerate(chunks)
                    ],
                )
                conn.executemany(
                    "INSERT INTO index_meta (key, value) VALUES (?, ?)",
                    list(meta.items()),
                )
        finally:
            conn.close()
        os.replace(tmp, self._path)  # atomic: final file never half-written
        return len(chunks)

    def get_meta(self, key: str) -> str | None:
        """Return the value for a meta key, or None if absent."""
        conn = self._connect(self._path)
        try:
            row = conn.execute(
                "SELECT value FROM index_meta WHERE key = ?", (key,)
            ).fetchone()
            return row["value"] if row is not None else None
        finally:
            conn.close()

    def count_chunks(self, strategy: str | None = None) -> int:
        """Number of stored chunks, optionally filtered by strategy."""
        conn = self._connect(self._path)
        try:
            if strategy is None:
                row = conn.execute("SELECT COUNT(*) AS n FROM chunks").fetchone()
            else:
                row = conn.execute(
                    "SELECT COUNT(*) AS n FROM chunks WHERE strategy = ?",
                    (strategy,),
                ).fetchone()
            return int(row["n"])
        finally:
            conn.close()

    def all_chunks(self, strategy: str | None = None) -> list[Chunk]:
        """All stored chunks ordered by source, then chunk_index
        (strategy as deterministic tiebreaker), optionally filtered."""
        sql = (
            "SELECT chunk_id, source, title, section, locale, chunk_index, "
            "strategy, token_count, char_count, content FROM chunks"
            + (" WHERE strategy = ?" if strategy is not None else "")
            + " ORDER BY source, chunk_index, strategy"
        )
        params = (strategy,) if strategy is not None else ()
        conn = self._connect(self._path)
        try:
            rows = conn.execute(sql, params).fetchall()
        finally:
            conn.close()
        return [
            Chunk(
                chunk_id=r["chunk_id"],
                source=r["source"],
                title=r["title"],
                section=r["section"],
                locale=r["locale"],
                chunk_index=r["chunk_index"],
                strategy=r["strategy"],
                token_count=r["token_count"],
                char_count=r["char_count"],
                content=r["content"],
            )
            for r in rows
        ]
