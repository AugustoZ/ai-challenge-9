"""FAISS vector store: flat inner-product index over L2-normalized vectors.

Cosine similarity is implemented as inner product on normalized vectors
(project standard — evaluation metrics depend on it). Stores vectors only;
metadata lives in SQLite (see later tasks).
"""

from __future__ import annotations

from pathlib import Path

import faiss
import numpy as np


def _l2_normalize(matrix: np.ndarray) -> np.ndarray:
    """L2-normalize rows; zero-norm rows are left as-is (division guarded)."""
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return matrix / norms


class FaissStore:
    """Flat IP index over L2-normalized float32 vectors (cosine semantics)."""

    def __init__(self, dim: int) -> None:
        self._index = faiss.IndexFlatIP(dim)

    @property
    def dim(self) -> int:
        return self._index.d

    def __len__(self) -> int:
        return self._index.ntotal

    def add_vectors(self, matrix: np.ndarray) -> None:
        """L2-normalize each row and append to the index."""
        arr = np.ascontiguousarray(matrix, dtype=np.float32)
        if arr.ndim != 2:
            raise ValueError(
                f"expected 2-D matrix (rows=vectors), got shape {arr.shape}"
            )
        if arr.shape[1] != self.dim:
            raise ValueError(
                f"dimension mismatch: expected {self.dim}, got {arr.shape[1]}"
            )
        self._index.add(_l2_normalize(arr))

    def search(self, vector: np.ndarray, k: int) -> list[tuple[int, float]]:
        """Return top-k (row_id, cosine_score) pairs, sorted descending.

        k is clamped to the number of stored vectors.
        """
        vec = np.ascontiguousarray(vector, dtype=np.float32)
        if vec.ndim != 1 or vec.shape[0] != self.dim:
            raise ValueError(
                f"dimension mismatch: expected {self.dim}-D vector, got shape {vec.shape}"
            )
        if k <= 0 or self._index.ntotal == 0:
            return []
        k = min(k, self._index.ntotal)
        scores, ids = self._index.search(_l2_normalize(vec[None, :]), k)
        return [(int(i), float(s)) for i, s in zip(ids[0], scores[0]) if i >= 0]

    def save(self, path: str | Path) -> None:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        faiss.write_index(self._index, str(path))

    @classmethod
    def load(cls, path: str | Path) -> FaissStore:
        index = faiss.read_index(str(path))
        store = cls(index.d)
        store._index = index
        return store
