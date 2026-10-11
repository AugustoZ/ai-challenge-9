"""Integration tests for the local E5 embedder (Task 10) -- REAL model.

These load the real ``intfloat/multilingual-e5-small`` weights (~470MB one-
time HF download, cached afterwards) and therefore carry the ``integration``
marker; the default offline suite never selects them.

Run with::

    uv run pytest tests/integration/test_local_embedder.py -m integration -q
"""

from __future__ import annotations

import numpy as np
import pytest

from gwclub_indexer.config import load_config
from gwclub_indexer.embeddings.local import LocalEmbedder

pytestmark = pytest.mark.integration


@pytest.fixture(scope="module")
def embedder() -> LocalEmbedder:
    """One shared embedder per module: the 470MB model is loaded once."""
    cfg = load_config()
    return LocalEmbedder(cfg.local_embedding)


def test_dim_is_384(embedder: LocalEmbedder) -> None:
    assert embedder.model_name == "intfloat/multilingual-e5-small"
    assert embedder.dim == 384


def test_embed_query_shape_and_norm(embedder: LocalEmbedder) -> None:
    vec = embedder.embed_query("привет тест")
    assert vec.shape == (384,)
    assert vec.dtype == np.float32
    assert np.isclose(np.linalg.norm(vec), 1.0, rtol=1e-5)


def test_embed_passages_shape_and_norm(embedder: LocalEmbedder) -> None:
    out = embedder.embed_passages(["one", "two"])
    assert out.shape == (2, 384)
    assert out.dtype == np.float32
    np.testing.assert_allclose(np.linalg.norm(out, axis=1), 1.0, rtol=1e-5)


def test_same_text_yields_identical_vector(embedder: LocalEmbedder) -> None:
    v1 = embedder.embed_query("детерминизм векторного поиска")
    v2 = embedder.embed_query("детерминизм векторного поиска")
    np.testing.assert_array_equal(v1, v2)
