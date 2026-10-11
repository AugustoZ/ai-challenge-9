"""TDD tests for the deterministic mock embedder (Task 7).

The ``MockEmbedder`` is the offline stand-in for the real
sentence-transformers model. It must be:
  * fully deterministic (same text -> same vector, on repeated calls);
  * distinct (different texts -> different vectors);
  * L2-normalized (unit-length rows / vectors);
  * correctly shaped (passages: (N, dim), query: (dim,));
  * structurally conformant with the ``Embedder`` protocol.

All assertions are property-based and require no network or model weights.
"""

from __future__ import annotations

import numpy as np
import pytest

from gwclub_indexer.embeddings.base import Embedder, EmbeddingError
from gwclub_indexer.embeddings.mock import MockEmbedder


def test_same_text_same_vector_across_calls(mock_embedder: MockEmbedder) -> None:
    first = mock_embedder.embed_passages(["alpha", "beta", "gamma"])
    second = mock_embedder.embed_passages(["alpha", "beta", "gamma"])
    np.testing.assert_array_equal(first, second)


def test_same_query_same_vector_across_calls(mock_embedder: MockEmbedder) -> None:
    q1 = mock_embedder.embed_query("как подключить Android Auto")
    q2 = mock_embedder.embed_query("как подключить Android Auto")
    np.testing.assert_array_equal(q1, q2)


def test_determinism_across_fresh_instances() -> None:
    # No hidden global RNG state: two independent instances agree.
    a = MockEmbedder(dim=8).embed_passages(["x", "y"])
    b = MockEmbedder(dim=8).embed_passages(["x", "y"])
    np.testing.assert_array_equal(a, b)


def test_distinct_texts_produce_distinct_vectors(mock_embedder: MockEmbedder) -> None:
    texts = [f"uniquish document {i} with distinct payload" for i in range(10)]
    vecs = mock_embedder.embed_passages(texts)
    keys = {tuple(np.round(v, 6)) for v in vecs}
    assert len(keys) == 10, "collisions detected among 10 distinct texts"


def test_passage_rows_are_unit_length(mock_embedder: MockEmbedder) -> None:
    vecs = mock_embedder.embed_passages(["one", "two", "three"])
    norms = np.linalg.norm(vecs, axis=1)
    assert np.allclose(norms, 1.0, atol=1e-5), norms


def test_query_vector_is_unit_length(mock_embedder: MockEmbedder) -> None:
    vec = mock_embedder.embed_query("some query text")
    assert np.isclose(float(np.linalg.norm(vec)), 1.0, atol=1e-5)


def test_shapes_and_dtype(mock_embedder: MockEmbedder) -> None:
    dim = mock_embedder.dim
    assert dim == 8

    passages = mock_embedder.embed_passages(["a", "b", "c"])
    assert passages.shape == (3, dim)
    assert passages.dtype == np.float32

    query = mock_embedder.embed_query("q")
    assert query.shape == (dim,)
    assert query.dtype == np.float32

    empty = mock_embedder.embed_passages([])
    assert empty.shape == (0, dim)
    assert empty.dtype == np.float32


def test_custom_dim_respected() -> None:
    emb = MockEmbedder(dim=16)
    assert emb.dim == 16
    assert emb.embed_query("hello").shape == (16,)
    assert emb.embed_passages(["x"]).shape == (1, 16)


def test_model_name_and_dim_properties(mock_embedder: MockEmbedder) -> None:
    assert mock_embedder.model_name == "mock-embedder"
    assert mock_embedder.dim == 8


def test_mock_satisfies_embedder_protocol(mock_embedder: MockEmbedder) -> None:
    assert isinstance(mock_embedder, Embedder)


def test_invalid_dim_raises_embedding_error() -> None:
    with pytest.raises(EmbeddingError):
        MockEmbedder(dim=0)
