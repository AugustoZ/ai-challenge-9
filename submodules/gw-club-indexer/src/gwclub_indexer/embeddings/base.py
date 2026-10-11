"""Embedder protocol and error type for gw-club-docs indexing (Task 7).

This module is intentionally dependency-light: it only depends on ``numpy``
and ``typing``. The concrete encoders (local sentence-transformers model in
Task 10, OpenAI-compatible adapter in Task 11, and the deterministic mock in
``mock.py``) all satisfy the structural ``Embedder`` protocol, so any caller
can be written once against this interface and run offline with the mock.

Vector convention (shared by every embedder and the FAISS store):
    * ``embed_passages(texts)`` -> ``np.ndarray`` of shape ``(N, dim)``,
      ``float32``, each row L2-normalized.
    * ``embed_query(text)``     -> ``np.ndarray`` of shape ``(dim,)``,
      ``float32``, L2-normalized.
    * Cosine similarity is therefore computed as a plain inner product
      (``IndexFlatIP``) over these unit vectors.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

import numpy as np


class EmbeddingError(Exception):
    """Base class for any embedding/encoder failure.

    Concrete embedders raise this (or a subclass) for malformed inputs,
    model-mismatch conditions, backend errors, etc.
    """


@runtime_checkable
class Embedder(Protocol):
    """Structural interface every text embedder must satisfy.

    Structural (duck-typed) protocol: implementations do NOT subclass it.
    ``@runtime_checkable`` allows ``isinstance(obj, Embedder)`` to verify the
    required attributes/methods are present.
    """

    @property
    def model_name(self) -> str:
        """Stable identifier of the embedding model (e.g. ``"mock-embedder"``)."""
        ...

    @property
    def dim(self) -> int:
        """Dimensionality of produced vectors."""
        ...

    def embed_passages(self, texts: list[str]) -> np.ndarray:
        """Embed a batch of passage texts.

        Args:
            texts: List of ``N`` raw passage strings.

        Returns:
            ``np.ndarray`` of shape ``(N, dim)``, ``float32``, L2-normalized rows.
            An empty ``texts`` list yields shape ``(0, dim)``.
        """
        ...

    def embed_query(self, text: str) -> np.ndarray:
        """Embed a single query string.

        Args:
            text: The raw query string.

        Returns:
            ``np.ndarray`` of shape ``(dim,)``, ``float32``, L2-normalized.
        """
        ...
