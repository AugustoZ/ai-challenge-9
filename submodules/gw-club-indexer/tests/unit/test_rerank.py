"""Unit tests for the CrossEncoder reranker wrapper (Task 3) -- OFFLINE.

The real ``sentence_transformers.CrossEncoder`` weights (``BAAI/bge-reranker-v2-m3``,
~2.2 GB) are NEVER downloaded in this file. Every offline test injects a fake
CrossEncoder through one of two seams on :class:`Reranker`:

* ``_model=``  -- a pre-built fake object exposing ``predict(pairs, **kw)``; the
  factory path is skipped entirely.
* ``_factory=`` -- a callable ``(model_name, *, device, max_length) -> model``
  that counts constructions; the lazy-load and lock-concurrency tests drive the
  *real* lazy path (``_get_model`` once) without loading real weights.

One integration test does exercise the REAL model but is gated by
``@pytest.mark.skipif(not _model_cached(), ...)`` so CI stays green without the
2.2 GB download and without touching a half-populated HF cache.
"""

from __future__ import annotations

import os
import threading
import time
from pathlib import Path

import numpy as np
import pytest

from gwclub_indexer.config import load_config
from gwclub_indexer.rerank import Reranker, RerankError


# --------------------------------------------------------------------------- #
# Fakes (fully offline)
# --------------------------------------------------------------------------- #
class FakeCrossEncoder:
    """Stand-in for ``CrossEncoder``: counts constructions, records predict calls.

    Deterministic scores in [0, 1] are derived from the document text so the
    sorting test can assert a meaningful descending order.
    """

    instances = 0  # class-level construction counter

    def __init__(self, *args: object, **kwargs: object) -> None:
        FakeCrossEncoder.instances += 1
        self.args = args
        self.kwargs = kwargs
        self.predict_calls: list[tuple[list[tuple[str, str]], dict]] = []

    def predict(self, inputs, **kwargs):
        self.predict_calls.append((list(inputs), kwargs))
        return np.asarray([self._score_for(q, d) for q, d in inputs], dtype=np.float32)

    # Public for the fake, so tests can drive it deterministically.
    @staticmethod
    def _score_for(query: str, document: str) -> float:
        # NOTE: check "нерелевант" BEFORE "релевант" -- the Russian word for
        # "irrelevant" ("нерелевантный") *contains* "релевант" as a substring.
        if "нерелевант" in document:
            return 0.05
        if "релевант" in document:
            return 0.95
        return 0.5


class CountingFactory:
    """Callable factory that counts constructions (the lazy-load seam)."""

    def __init__(self, delay: float = 0.0) -> None:
        self.count = 0
        self.model = FakeCrossEncoder()
        self.delay = delay

    def __call__(self, model_name: str, *, device: str | None, max_length: int):
        self.count += 1
        if self.delay:
            time.sleep(self.delay)  # widen the race so the lock is really contended
        return self.model


# --------------------------------------------------------------------------- #
# Config: [search].rerank_model default
# --------------------------------------------------------------------------- #
class TestConfigRerankModel:
    def test_rerank_model_defaults_to_none(self) -> None:
        """`[search].rerank_model` must read as None when unset (no defaults changed)."""
        cfg = load_config(path=None)
        assert cfg.default_search_strategy == "structure"  # existing default intact
        assert cfg.rerank_model is None


# --------------------------------------------------------------------------- #
# Happy path
# --------------------------------------------------------------------------- #
class TestHappyPath:
    def test_score_returns_scores_in_0_1_in_order(self) -> None:
        r = Reranker("fake/model", _model=FakeCrossEncoder())
        docs = ["релевантный документ про флешку", "нерелевантный документ"]
        scores = r.score("как отформатировать флешку", docs)

        assert isinstance(scores, list)
        assert len(scores) == len(docs)
        for s in scores:
            assert 0.0 <= s <= 1.0
        assert scores[0] > scores[1]  # relevant > irrelevant

    def test_empty_documents_returns_empty(self) -> None:
        r = Reranker("fake/model", _model=FakeCrossEncoder())
        assert r.score("query", []) == []


# --------------------------------------------------------------------------- #
# Lazy loading (exactly once)
# --------------------------------------------------------------------------- #
class TestLazyLoad:
    def test_two_score_calls_load_once(self) -> None:
        factory = CountingFactory()
        r = Reranker("fake/model", device="cpu", _factory=factory)

        assert factory.count == 0  # construction must NOT load
        r.score("q", ["a", "b"])
        r.score("q", ["c"])
        assert factory.count == 1  # exactly one model load

    def test_predict_gets_flat_pairs_in_order(self) -> None:
        model = FakeCrossEncoder()
        r = Reranker("fake/model", _model=model)
        docs = ["a", "b"]
        r.score("query", docs)
        pairs, kwargs = model.predict_calls[0]
        assert pairs == [("query", "a"), ("query", "b")]
        assert kwargs.get("convert_to_numpy") is True


# --------------------------------------------------------------------------- #
# Concurrency: one load under 8 threads
# --------------------------------------------------------------------------- #
class TestLockConcurrency:
    def test_eight_threads_collide_but_load_once(self) -> None:
        factory = CountingFactory(delay=0.05)  # ensure the 8 threads overlap on the lock
        r = Reranker("fake/model", device="cpu", _factory=factory)
        errors: list[BaseException] = []
        lengths: list[int] = []

        def worker() -> None:
            try:
                lengths.append(len(r.score("q", ["x", "y", "z"])))
            except BaseException as exc:  # noqa: BLE001 - collect for the assert
                errors.append(exc)

        threads = [threading.Thread(target=worker) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=15)

        assert errors == []
        assert factory.count == 1  # EXACTLY one load
        assert lengths == [3] * 8  # every thread got a full result


# --------------------------------------------------------------------------- #
# Load failure -> readable Russian message
# --------------------------------------------------------------------------- #
class TestLoadError:
    def test_error_message_contains_model_name_and_hint(self) -> None:
        def boom(model_name: str, *, device: str | None, max_length: int):
            raise OSError("network is down")

        r = Reranker("BAAI/bge-reranker-v2-m3", _factory=boom)

        with pytest.raises(RerankError) as exc_info:
            r.score("q", ["a"])

        msg = str(exc_info.value)
        assert "BAAI/bge-reranker-v2-m3" in msg  # model name present
        # Russian hint about the cause / remedy -- not a raw traceback.
        assert "Не удалось загрузить" in msg
        assert any(
            kw in msg
            for kw in ("Hugging Face", "huggingface", "веса модели", "скачайте")
        )


# --------------------------------------------------------------------------- #
# Type errors -> TypeError (TRY004), never ValueError
# --------------------------------------------------------------------------- #
class TestTypeErrors:
    def test_non_str_query_raises_typeerror(self) -> None:
        r = Reranker("fake/model", _model=FakeCrossEncoder())
        with pytest.raises(TypeError):
            r.score(123, ["a"])  # type: ignore[arg-type]

    def test_non_list_documents_raises_typeerror(self) -> None:
        r = Reranker("fake/model", _model=FakeCrossEncoder())
        with pytest.raises(TypeError):
            r.score("q", "not-a-list")  # type: ignore[arg-type]

    def test_non_str_document_raises_typeerror(self) -> None:
        r = Reranker("fake/model", _model=FakeCrossEncoder())
        with pytest.raises(TypeError):
            r.score("q", ["ok", 42])  # type: ignore[list-item]


# --------------------------------------------------------------------------- #
# Descending-sort support: scores stay parallel to documents
# --------------------------------------------------------------------------- #
class TestDescendingSort:
    def test_scores_sort_documents_descending(self) -> None:
        r = Reranker("fake/model", _model=FakeCrossEncoder())
        docs = [
            "нерелевантный текст",
            "релевантный документ про установку",
            "нейтральный текст",
        ]
        scores = r.score("как установить прошивку", docs)

        # zip keeps document<->score association; sort descending by score.
        ranked = [d for _, d in sorted(zip(scores, docs), key=lambda p: p[0], reverse=True)]
        assert ranked == [
            "релевантный документ про установку",
            "нейтральный текст",
            "нерелевантный текст",
        ]
        # float32 rounding: compare with tolerance, not exact equality.
        assert sorted(scores, reverse=True) == pytest.approx([0.95, 0.5, 0.05])


# --------------------------------------------------------------------------- #
# Integration test with the REAL model (gated: no 2.2 GB download in CI-like runs)
# --------------------------------------------------------------------------- #
def _model_cached() -> bool:
    """True iff BAAI/bge-reranker-v2-m3 is fully downloaded in the HF cache.

    Checks for a snapshot ``config.json`` so a half-populated cache (parallel
    download in progress) is treated as not ready -> the test skips, never fails.
    This check DOES NOT trigger a download.
    """
    hub = os.environ.get("HUGGINGFACE_HUB_CACHE") or str(
        Path.home() / ".cache" / "huggingface" / "hub"
    )
    model_dir = Path(hub) / "models--BAAI--bge-reranker-v2-m3"
    if not model_dir.is_dir():
        return False
    try:
        for snap in (model_dir / "snapshots").glob("*/"):
            if (snap / "config.json").is_file():
                return True
    except OSError:
        return False
    return False


REAL_MODEL = "BAAI/bge-reranker-v2-m3"


@pytest.mark.integration
@pytest.mark.skipif(
    not _model_cached(),
    reason=f"{REAL_MODEL} не закэширован (2.2GB не скачиваем в этом прогоне)",
)
def test_integration_real_model_range_and_separation() -> None:
    """Real CrossEncoder: [0,1] scores AND a clear relevant/irrelevant gap."""
    r = Reranker(REAL_MODEL, device="cpu", max_length=512)
    query = "как правильно отформатировать флешку"
    relevant = (
        "Для форматирования флешки в NTFS откройте Rufus, выберите схему MBR, "
        "укажите файловую систему NTFS и нажмите Запуск."
    )
    irrelevant = (
        "Завтра на стадионе пройдёт воскресный матч, погода будет солнечная и "
        "теплая, а зрители придут с утра."
    )

    scores = r.score(query, [relevant, irrelevant])

    assert all(0.0 <= s <= 1.0 for s in scores)
    assert scores[0] - scores[1] >= 0.3  # relevant clearly above irrelevant
