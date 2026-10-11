"""Deterministic offline mock embedder (Task 7).

``MockEmbedder`` is a dependency-light stand-in for the real sentence-
transformers model. It lets every other module (chunkers, stores, indexer,
eval) be unit-tested offline and fast, without loading weights or hitting the
network.

Determinism contract:
    For a given input string ``t``, the vector is derived *only* from
    ``sha256(t)``:

        seed  = int.from_bytes(sha256(t), "big")
        rng   = np.random.default_rng(seed)
        vec   = rng.standard_normal(dim)
        out   = (vec / ||vec||).astype(float32)

    There is NO dependence on wall-clock time, ``id()``, or any unseeded
    global RNG state. Consequently:
        * the same text always yields the same vector (across calls and
          across fresh ``MockEmbedder`` instances / processes);
        * two different texts yield different vectors (a 256-bit sha256
          seed makes collisions practically impossible).
"""

from __future__ import annotations

import hashlib

import numpy as np

from gwclub_indexer.embeddings.base import EmbeddingError


class MockEmbedder:
    """Deterministic, offline ``Embedder`` used for offline unit tests.

    Not a subclass of the ``Embedder`` protocol (it is satisfied
    structurally). ``model_name`` and ``dim`` are properties per the contract.
    """

    #: Identifier stored in index metadata so a real run can detect a mock.
    MODEL_NAME = "mock-embedder"

    def __init__(self, dim: int = 8) -> None:
        if not isinstance(dim, (int, np.integer)) or isinstance(dim, bool):
            raise EmbeddingError(f"dim must be an int, got {type(dim).__name__}")
        if dim <= 0:
            raise EmbeddingError(f"dim must be positive, got {dim}")
        self._dim = int(dim)

    @property
    def model_name(self) -> str:
        return self.MODEL_NAME

    @property
    def dim(self) -> int:
        return self._dim

    def _embed_one(self, text: str) -> np.ndarray:
        """Return the L2-normalized ``float32`` vector for a single string."""
        if not isinstance(text, str):
            raise EmbeddingError(f"expected str text, got {type(text).__name__}")
        seed = int(hashlib.sha256(text.encode("utf-8")).hexdigest(), 16)
        rng = np.random.default_rng(seed)
        vec = rng.standard_normal(self._dim)  # float64, full precision
        vec = vec / np.linalg.norm(vec)  # normalize in float64
        return vec.astype(np.float32)  # contract: float32 out

    def embed_passages(self, texts: list[str]) -> np.ndarray:
        if not texts:
            return np.empty((0, self._dim), dtype=np.float32)
        return np.asarray([self._embed_one(t) for t in texts], dtype=np.float32)

    def embed_query(self, text: str) -> np.ndarray:
        return self._embed_one(text)
