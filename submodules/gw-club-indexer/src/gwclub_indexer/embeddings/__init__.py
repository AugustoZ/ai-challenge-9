"""Embedding encoders for gw-club-docs.

Public API (Task 10)::

    from gwclub_indexer.embeddings import make_embedder

    embedder = make_embedder(cfg)   # cfg.embedding_provider picks the backend

Available backends:
    * :class:`~gwclub_indexer.embeddings.local.LocalEmbedder` -- local
      sentence-transformers model (E5 prefixes applied inside);
    * ``OpenAICompatEmbedder`` (Task 11, imported lazily inside
      :func:`make_embedder` to avoid an import-time race with parallel work);
    * :class:`~gwclub_indexer.embeddings.mock.MockEmbedder` -- deterministic
      offline stand-in for tests.
"""

from __future__ import annotations

from gwclub_indexer.config import Config
from gwclub_indexer.embeddings.base import Embedder, EmbeddingError
from gwclub_indexer.embeddings.local import LocalEmbedder
from gwclub_indexer.embeddings.mock import MockEmbedder

__all__ = [
    "Embedder",
    "EmbeddingError",
    "LocalEmbedder",
    "MockEmbedder",
    "make_embedder",
]


def make_embedder(cfg: Config) -> Embedder:
    """Build the embedder selected by ``cfg.embedding_provider``.

    ``"local"`` -> :class:`LocalEmbedder` built from ``cfg.local_embedding``
    (model loaded lazily on first embed call). ``"openai_compat"`` ->
    ``OpenAICompatEmbedder`` (Task 11); that module is imported *inside* the
    branch so a missing/partial parallel implementation never breaks imports
    of this package.

    Raises:
        EmbeddingError: unknown provider value.
    """
    provider = cfg.embedding_provider
    if provider == "local":
        return LocalEmbedder(cfg.local_embedding)
    if provider == "openai_compat":
        # Lazy import: openai_compat.py is authored by a parallel task; keep
        # this branch self-contained to avoid import-time races.
        from gwclub_indexer.embeddings.openai_compat import OpenAICompatEmbedder

        return OpenAICompatEmbedder(cfg.openai_compat)
    raise EmbeddingError(
        f"unknown embedding_provider {provider!r}; expected 'local' or 'openai_compat'"
    )
