"""Unit tests for the stage-2 post-search pipeline (Task 8). Fully offline.

The extra ``/search`` params (``k_before``, ``threshold``, ``rerank``) are
exercised over the port-0 HTTP stack with ``MockEmbedder`` + ``FixedChunker``
exactly like ``test_serve.py``. The reranker is ALWAYS a fake injected through
``Reranker(_factory=`` / ``_model=``)`` -- no real CrossEncoder, no torch in this
process (a real model next to ``test_local_embedder.py`` would re-trigger the
environment segfault; see issues.md). The built index is reused from
``test_serve._build_index`` so the two files stay consistent.
"""

from __future__ import annotations

import threading
from itertools import pairwise
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

from gwclub_indexer.rerank import Reranker
from gwclub_indexer.serve import build_server
from tests.unit.test_serve import STRATEGY, _build_index, _post

DEFAULT_K = 5


# --------------------------------------------------------------------------- #
# Fakes (offline, no torch)
# --------------------------------------------------------------------------- #
class _FakeModel:
    """Stand-in for a CrossEncoder: deterministic scores, no weights."""

    def __init__(self, score_fn=None) -> None:
        self._score_fn = score_fn or self._default_score

    def predict(self, inputs, **kwargs):
        return np.asarray(
            [self._score_fn(q, d) for q, d in inputs], dtype=np.float32
        )

    @staticmethod
    def _default_score(query: str, document: str) -> float:
        # check "нерелевант" before "релевант" (the RU word contains it as a substring)
        if "нерелевант" in document:
            return 0.05
        if "релевант" in document:
            return 0.95
        return 0.5


class _CountingFactory:
    """Factory that counts model constructions (the lazy-load seam)."""

    def __init__(self, model=None) -> None:
        self.count = 0
        self.model = model if model is not None else _FakeModel()

    def __call__(self, model_name: str, *, device: str | None, max_length: int):
        self.count += 1
        return self.model


def _make_reranker(factory: _CountingFactory) -> Reranker:
    return Reranker("fake/model", device="cpu", _factory=factory)


# --------------------------------------------------------------------------- #
# Server fixtures (port 0, reuse the mock index from test_serve)
# --------------------------------------------------------------------------- #
def _start_server(ctx: SimpleNamespace, *, reranker: Reranker | None = None):
    srv = build_server(
        ctx.cfg,
        STRATEGY,
        "127.0.0.1",
        0,
        embedder=ctx.mock,
        default_k=DEFAULT_K,
        reranker=reranker,
    )
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    base_url = f"http://127.0.0.1:{srv.server_address[1]}"
    return srv, base_url, thread


@pytest.fixture
def serve_index(tmp_path: Path) -> SimpleNamespace:
    """A built mock index pair (no server started)."""
    return _build_index(tmp_path)


@pytest.fixture
def running(serve_index: SimpleNamespace) -> SimpleNamespace:
    """A running server with NO reranker (rerank=true -> 400)."""
    ctx = serve_index
    srv, base_url, thread = _start_server(ctx)
    try:
        yield SimpleNamespace(base_url=base_url, server=srv)
    finally:
        srv.shutdown()
        srv.server_close()
        thread.join(timeout=5)


@pytest.fixture
def running_rerank(serve_index: SimpleNamespace) -> SimpleNamespace:
    """A running server WITH a fake reranker (real pipeline, offline)."""
    ctx = serve_index
    factory = _CountingFactory()
    reranker = _make_reranker(factory)
    srv, base_url, thread = _start_server(ctx, reranker=reranker)
    try:
        yield SimpleNamespace(base_url=base_url, server=srv, factory=factory)
    finally:
        srv.shutdown()
        srv.server_close()
        thread.join(timeout=5)


# --------------------------------------------------------------------------- #
# E3: rerank=true without a configured model -> 400 (Russian)
# --------------------------------------------------------------------------- #
class TestRerankUnconfigured:
    def test_rerank_true_without_model_returns_400(self, serve_index):
        srv, base_url, thread = _start_server(serve_index)
        try:
            status, body = _post(base_url, "/search", {"query": "запрос", "rerank": True})
        finally:
            srv.shutdown()
            srv.server_close()
            thread.join(timeout=5)

        assert status == 400
        assert body == {"error": "Реранкер не настроен: запустите serve с --rerank-model"}


# --------------------------------------------------------------------------- #
# Threshold against the cosine scale (rerank=false)
# --------------------------------------------------------------------------- #
class TestThresholdCosine:
    def test_hard_threshold_returns_empty_200(self, running):
        """E1: a threshold above every cosine -> 200 with an empty list."""
        status, results = _post(
            running.base_url, "/search", {"query": "запрос", "threshold": 0.999999}
        )
        assert status == 200
        assert results == []

    def test_threshold_out_of_range_cosine_returns_400(self, running):
        status, body = _post(
            running.base_url, "/search", {"query": "запрос", "threshold": 1.5}
        )
        assert status == 400
        assert "[-1, 1]" in body["error"]


# --------------------------------------------------------------------------- #
# Threshold against the sigmoid scale (rerank=true)
# --------------------------------------------------------------------------- #
class TestThresholdSigmoid:
    def test_threshold_zero_rerank_keeps_all(self, running_rerank):
        query = next(iter(running_rerank.server.row_map.values())).content
        status, results = _post(
            running_rerank.base_url,
            "/search",
            {"query": query, "k": 3, "rerank": True, "threshold": 0.0},
        )
        assert status == 200
        assert len(results) == 3  # sigmoid scores >= 0 -> all kept

    def test_threshold_one_rerank_filters_all(self, running_rerank):
        query = next(iter(running_rerank.server.row_map.values())).content
        status, results = _post(
            running_rerank.base_url,
            "/search",
            {"query": query, "k": 3, "rerank": True, "threshold": 1.0},
        )
        assert status == 200
        assert results == []  # all sigmoid scores < 1.0 -> filtered out

    def test_threshold_out_of_range_sigmoid_returns_400(self, running_rerank):
        status, body = _post(
            running_rerank.base_url,
            "/search",
            {"query": "запрос", "rerank": True, "threshold": 1.5},
        )
        assert status == 400
        assert "[0, 1]" in body["error"]


# --------------------------------------------------------------------------- #
# Param validation (bad types -> 400 Russian)
# --------------------------------------------------------------------------- #
class TestParamValidation:
    def test_threshold_bool_returns_400(self, running):
        status, body = _post(
            running.base_url, "/search", {"query": "запрос", "threshold": True}
        )
        assert status == 400
        assert body == {"error": "Параметр threshold должен быть числом"}

    def test_threshold_str_returns_400(self, running):
        status, body = _post(
            running.base_url, "/search", {"query": "запрос", "threshold": "0.5"}
        )
        assert status == 400
        assert body == {"error": "Параметр threshold должен быть числом"}

    def test_rerank_non_bool_returns_400(self, running):
        status, body = _post(
            running.base_url, "/search", {"query": "запрос", "rerank": "yes"}
        )
        assert status == 400
        assert body == {
            "error": "Параметр rerank должен быть логическим значением (true/false)"
        }

    def test_k_before_non_int_returns_400(self, running):
        status, body = _post(
            running.base_url, "/search", {"query": "запрос", "k_before": "20"}
        )
        assert status == 400
        assert body == {"error": "Параметр k_before должен быть целым числом"}


# --------------------------------------------------------------------------- #
# k_before semantics
# --------------------------------------------------------------------------- #
class TestKBefore:
    def test_k_before_larger_than_index_clamped(self, running):
        """E2: k_before beyond the index size is clamped, not an error."""
        status, results = _post(
            running.base_url, "/search", {"query": "запрос", "k": 2, "k_before": 1000}
        )
        assert status == 200
        assert len(results) == 2  # top-k still applied

    def test_k_before_less_than_k_auto_raised(self, running):
        """k_before < k is auto-raised to k (documented; not a 400)."""
        status, results = _post(
            running.base_url, "/search", {"query": "запрос", "k": 3, "k_before": 1}
        )
        assert status == 200
        assert len(results) == 3  # search pool widened to k, so top-k fills

    def test_k_before_null_defaults_to_k(self, running):
        status_none, res_none = _post(
            running.base_url, "/search", {"query": "запрос", "k": 3}
        )
        status_null, res_null = _post(
            running.base_url, "/search", {"query": "запрос", "k": 3, "k_before": None}
        )
        assert status_none == status_null == 200
        assert res_none == res_null


# --------------------------------------------------------------------------- #
# Rerank pipeline mechanics
# --------------------------------------------------------------------------- #
class TestRerankPipeline:
    def test_rerank_returns_top_k_in_0_1_desc(self, running_rerank):
        query = next(iter(running_rerank.server.row_map.values())).content
        status, results = _post(
            running_rerank.base_url,
            "/search",
            {"query": query, "k": 2, "k_before": 5, "rerank": True},
        )
        assert status == 200
        assert len(results) == 2
        scores = [r["score"] for r in results]
        assert all(0.0 <= s <= 1.0 for s in scores)
        assert all(a >= b for a, b in pairwise(scores))

    def test_rerank_reorders_candidates(self, serve_index):
        """A reranker that favors the android chunk must override the cosine
        order -- so re-ranking actually re-scores, not just re-labels.

        The query is an exact chunk content, which the deterministic mock
        embedder scores 1.0 (guaranteed cosine top-1). The reranker scores the
        android chunks 0.99 and everything else 0.01, so the rerank flips the
        first hit from the exact-matched chunk to the android chunk.
        """
        long_chunk = next(c for c in serve_index.chunks if "слово" in c.content)

        def score_fn(query: str, document: str) -> float:
            return 0.99 if "android" in document else 0.01

        factory = _CountingFactory(model=_FakeModel(score_fn=score_fn))
        reranker = _make_reranker(factory)
        srv, base_url, thread = _start_server(serve_index, reranker=reranker)
        try:
            _, cosine = _post(
                base_url, "/search", {"query": long_chunk.content, "k": 3}
            )
            status, reranked = _post(
                base_url,
                "/search",
                {
                    "query": long_chunk.content,
                    "k": 3,
                    "k_before": 7,
                    "rerank": True,
                },
            )
        finally:
            srv.shutdown()
            srv.server_close()
            thread.join(timeout=5)

        assert status == 200
        assert len(reranked) == 3
        # cosine ranked the exact-matched chunk first; rerank flips it to android.
        assert cosine[0]["chunk_id"] == long_chunk.chunk_id
        assert reranked[0]["source"] == "ru/android_auto.md"
        assert cosine[0]["chunk_id"] != reranked[0]["chunk_id"]
        assert reranked[0]["score"] == pytest.approx(0.99)


# --------------------------------------------------------------------------- #
# E4: concurrent first rerank requests load the model exactly once
# --------------------------------------------------------------------------- #
class TestRerankConcurrency:
    def test_two_concurrent_first_rerank_load_once(self, serve_index):
        factory = _CountingFactory()
        reranker = _make_reranker(factory)
        srv, base_url, thread = _start_server(serve_index, reranker=reranker)
        target = serve_index.chunks[0]
        outcomes: list[tuple[int, dict]] = []

        def worker() -> None:
            status, body = _post(
                base_url, "/search", {"query": target.content, "k": 2, "rerank": True}
            )
            outcomes.append((status, body))

        threads = [threading.Thread(target=worker) for _ in range(2)]
        try:
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=15)
        finally:
            srv.shutdown()
            srv.server_close()
            thread.join(timeout=5)

        assert len(outcomes) == 2
        for status, _body in outcomes:
            assert status == 200
        assert factory.count == 1  # the model was loaded exactly once
