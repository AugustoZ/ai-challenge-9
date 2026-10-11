"""Unit tests for the local E5 embedder (Task 10) -- fully OFFLINE.

The real ``sentence_transformers`` model is never loaded here: every test
injects a ``FakeModel`` (constructor injection ``_model=``) or monkeypatches
``SentenceTransformer``. The FakeModel records the *exact* input strings and
call sizes it receives so we can assert:

* E5 prefixes (``"query: "`` / ``"passage: "``) are added INSIDE the embedder;
* batching: ``embed_passages`` calls the model in chunks of <= 32 texts;
* output contract: ``(N, dim)`` / ``(dim,)`` ``float32`` L2-normalized rows;
* lazy loading: constructing the embedder must not import/load the model.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pytest

from gwclub_indexer.embeddings.base import Embedder
from gwclub_indexer.embeddings.local import LocalEmbedder


# --------------------------------------------------------------------------- #
# Fakes
# --------------------------------------------------------------------------- #
@dataclass
class _Cfg:
    """Minimal LocalEmbeddingConfig-like stand-in (offline, no config file)."""

    model: str = "fake/e5-small"
    device: str = "cpu"


class FakeModel:
    """Stand-in for ``SentenceTransformer`` recording everything it receives."""

    def __init__(self, dim: int = 4, fail_on_half: bool = False) -> None:
        self.dim = dim
        self.encode_calls: list[list[str]] = []
        self.encode_kwargs: list[dict] = []
        self.half_called = False
        self._fail_on_half = fail_on_half
        # Optional per-text override: prefixed-text -> vector (float32/float64)
        self.vectors: dict[str, np.ndarray] = {}

    def get_sentence_embedding_dimension(self) -> int:
        return self.dim

    def half(self):
        self.half_called = True
        if self._fail_on_half:
            raise RuntimeError("fp16 not supported by this fake")
        return self

    def encode(self, texts, **kwargs):
        self.encode_calls.append(list(texts))
        self.encode_kwargs.append(kwargs)
        rows = [self.vectors.get(t, self._default_vec(t)) for t in texts]
        return np.asarray(rows, dtype=np.float32)

    def _default_vec(self, text: str) -> np.ndarray:
        """Deterministic NON-unit vector derived from the text hash."""
        seed = int.from_bytes(text.encode("utf-8")[-8:], "big") or 1
        rng = np.random.default_rng(seed)
        return rng.uniform(low=0.1, high=3.0, size=self.dim)  # not normalized


class _FakeFactory:
    """Callable replacement for ``SentenceTransformer.__init__``."""

    def __init__(self, model: FakeModel | None = None) -> None:
        self.model = model if model is not None else FakeModel()
        self.calls: list[dict] = []

    def __call__(self, model_name_or_path, **kwargs):
        self.calls.append({"name": model_name_or_path, **kwargs})
        return self.model


# --------------------------------------------------------------------------- #
# E5 prefixes (the #1 quality gotcha)
# --------------------------------------------------------------------------- #
class TestE5Prefixes:
    def test_embed_passages_adds_passage_prefix(self) -> None:
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(), _model=fake)
        emb.embed_passages(["a", "b"])
        assert fake.encode_calls == [["passage: a", "passage: b"]]

    def test_embed_query_adds_query_prefix(self) -> None:
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(), _model=fake)
        emb.embed_query("q")
        assert fake.encode_calls == [["query: q"]]

    def test_callers_pass_raw_text_no_double_prefix(self) -> None:
        """A caller that already added a prefix must not get a double one;
        the embedder adds exactly one prefix to the raw text it receives."""
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(), _model=fake)
        emb.embed_passages(["query: not-my-job"])
        assert fake.encode_calls == [["passage: query: not-my-job"]]

    def test_prefix_mode_constant(self) -> None:
        assert LocalEmbedder.PREFIX_MODE == "e5"


# --------------------------------------------------------------------------- #
# Output contract: float32, L2-normalized, shapes
# --------------------------------------------------------------------------- #
class TestOutputContract:
    def test_passages_normalized_from_non_unit_vectors(self) -> None:
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(), _model=fake)
        out = emb.embed_passages(["x", "y"])
        assert out.dtype == np.float32
        assert out.shape == (2, 4)
        np.testing.assert_allclose(np.linalg.norm(out, axis=1), 1.0, rtol=1e-5)

    def test_query_normalized_from_non_unit_vectors(self) -> None:
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(), _model=fake)
        out = emb.embed_query("hello")
        assert out.dtype == np.float32
        assert out.shape == (4,)
        assert np.isclose(np.linalg.norm(out), 1.0, rtol=1e-5)

    def test_already_normalized_input_passes_through(self) -> None:
        """If the model already returns unit vectors, the embedder's
        re-normalization must be a no-op (allclose pass-through)."""
        fake = FakeModel()
        rng = np.random.default_rng(42)
        unit = rng.standard_normal((3, 4))
        unit = (unit / np.linalg.norm(unit, axis=1, keepdims=True)).astype(np.float32)
        for i, t in enumerate(["passage: p0", "passage: p1", "passage: p2"]):
            fake.vectors[t] = unit[i]
        emb = LocalEmbedder(_Cfg(), _model=fake)
        out = emb.embed_passages(["p0", "p1", "p2"])
        np.testing.assert_allclose(out, unit, rtol=1e-5, atol=1e-6)
        assert out.dtype == np.float32

    def test_order_preserved_passages(self) -> None:
        fake = FakeModel()
        texts = ["alpha", "beta", "gamma", "delta"]
        emb = LocalEmbedder(_Cfg(), _model=fake)
        out = emb.embed_passages(texts)
        for i, t in enumerate(texts):
            expected = fake._default_vec(f"passage: {t}")
            expected = expected / np.linalg.norm(expected)
            np.testing.assert_allclose(out[i], expected, rtol=1e-5)

    def test_empty_passages_zero_rows(self) -> None:
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(), _model=fake)
        out = emb.embed_passages([])
        assert out.shape == (0, 4)
        assert out.dtype == np.float32

    def test_satisfies_embedder_protocol(self) -> None:
        emb = LocalEmbedder(_Cfg(), _model=FakeModel())
        assert isinstance(emb, Embedder)


# --------------------------------------------------------------------------- #
# Properties (model_name / dim) from the injected fake
# --------------------------------------------------------------------------- #
class TestProperties:
    def test_model_name_from_cfg(self) -> None:
        emb = LocalEmbedder(_Cfg(model="fake/e5-small"), _model=FakeModel())
        assert emb.model_name == "fake/e5-small"

    def test_model_name_override(self) -> None:
        emb = LocalEmbedder(
            _Cfg(model="fake/e5-small"), model_name="other/model", _model=FakeModel()
        )
        assert emb.model_name == "other/model"

    def test_dim_from_fake(self) -> None:
        emb = LocalEmbedder(_Cfg(), _model=FakeModel(dim=7))
        assert emb.dim == 7


# --------------------------------------------------------------------------- #
# Batching: <= 32 texts per model call, original order
# --------------------------------------------------------------------------- #
class TestBatching:
    def test_70_passages_batched_by_32_in_order(self) -> None:
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(), _model=fake)
        texts = [f"doc-{i}" for i in range(70)]
        out = emb.embed_passages(texts)

        sizes = [len(call) for call in fake.encode_calls]
        assert sizes == [32, 32, 6]  # all <= 32, every text encoded exactly once
        assert sum(sizes) == 70
        assert out.shape == (70, 4)
        for i, t in enumerate(texts):
            expected = fake._default_vec(f"passage: {t}")
            expected = expected / np.linalg.norm(expected)
            np.testing.assert_allclose(out[i], expected, rtol=1e-5)

    def test_encode_kwargs_forwarded(self) -> None:
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(), _model=fake)
        emb.embed_passages(["a"])
        kw = fake.encode_kwargs[0]
        assert kw.get("normalize_embeddings") is True
        assert kw.get("convert_to_numpy") is True
        assert kw.get("batch_size") == 32


# --------------------------------------------------------------------------- #
# Lazy loading: constructing must never load the real model
# --------------------------------------------------------------------------- #
class TestLazyLoading:
    def test_construction_and_model_name_do_not_load(self, monkeypatch) -> None:
        import sentence_transformers

        def boom(*a, **kw):  # real load would fail this test
            raise AssertionError("SentenceTransformer must not be constructed")

        monkeypatch.setattr(sentence_transformers, "SentenceTransformer", boom)
        emb = LocalEmbedder(_Cfg(model="fake/e5-small"))
        assert emb.model_name == "fake/e5-small"  # must not trigger a load
        assert emb._model is None

    def test_first_embed_loads_via_factory_once(self, monkeypatch) -> None:
        import sentence_transformers

        factory = _FakeFactory()
        monkeypatch.setattr(
            sentence_transformers,
            "SentenceTransformer",
            lambda name, **kw: factory(name, **kw),
        )
        emb = LocalEmbedder(_Cfg(model="fake/e5-small", device="cpu"))
        assert emb._model is None
        out = emb.embed_query("hello")
        assert len(factory.calls) == 1
        assert factory.calls[0]["name"] == "fake/e5-small"
        assert factory.calls[0]["device"] == "cpu"
        assert out.shape == (4,)
        # second call reuses the cached model
        emb.embed_query("again")
        assert len(factory.calls) == 1

    def test_load_failure_wrapped_in_embedding_error(self, monkeypatch) -> None:
        import sentence_transformers

        from gwclub_indexer.embeddings.base import EmbeddingError

        def boom(name, **kw):
            raise OSError("model not found")

        monkeypatch.setattr(sentence_transformers, "SentenceTransformer", boom)
        emb = LocalEmbedder(_Cfg(model="missing/model", device="cpu"))
        with pytest.raises(EmbeddingError):
            emb.embed_query("x")


# --------------------------------------------------------------------------- #
# Device selection
# --------------------------------------------------------------------------- #
class TestDeviceSelection:
    def test_auto_uses_mps_when_available(self, monkeypatch) -> None:
        import torch

        monkeypatch.setattr(torch.backends.mps, "is_available", lambda: True)
        emb = LocalEmbedder(_Cfg(device="auto"), _model=FakeModel())
        assert emb._pick_device() == "mps"

    def test_auto_falls_back_to_cpu(self, monkeypatch) -> None:
        import torch

        monkeypatch.setattr(torch.backends.mps, "is_available", lambda: False)
        emb = LocalEmbedder(_Cfg(device="auto"), _model=FakeModel())
        assert emb._pick_device() == "cpu"

    def test_explicit_device_respected(self, monkeypatch) -> None:
        import torch

        monkeypatch.setattr(torch.backends.mps, "is_available", lambda: True)
        emb = LocalEmbedder(_Cfg(device="cpu"), _model=FakeModel())
        assert emb._pick_device() == "cpu"


# --------------------------------------------------------------------------- #
# fp16 on mps/cuda, safe fallback to fp32
# --------------------------------------------------------------------------- #
class TestFp16:
    def test_half_called_on_mps(self) -> None:
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(device="mps"), _model=fake)
        emb.embed_query("x")
        assert fake.half_called is True

    def test_no_half_on_cpu(self) -> None:
        fake = FakeModel()
        emb = LocalEmbedder(_Cfg(device="cpu"), _model=fake)
        emb.embed_query("x")
        assert fake.half_called is False

    def test_half_failure_falls_back_to_fp32(self) -> None:
        fake = FakeModel(fail_on_half=True)
        emb = LocalEmbedder(_Cfg(device="mps"), _model=fake)
        out = emb.embed_query("x")  # must not raise
        assert out.shape == (4,)
        assert np.isclose(np.linalg.norm(out), 1.0, rtol=1e-5)


# --------------------------------------------------------------------------- #
# make_embedder factory -- local branch only (openai_compat is parallel work)
# --------------------------------------------------------------------------- #
class TestMakeEmbedder:
    def test_local_provider_returns_local_embedder(self) -> None:
        from gwclub_indexer.config import load_config
        from gwclub_indexer.embeddings import make_embedder

        cfg = load_config()
        assert cfg.embedding_provider == "local"
        emb = make_embedder(cfg)
        assert isinstance(emb, LocalEmbedder)
        assert emb.model_name == "intfloat/multilingual-e5-small"
        assert emb._model is None  # lazy: nothing loaded yet

    def test_unknown_provider_raises(self) -> None:
        from gwclub_indexer.config import Config, load_config
        from gwclub_indexer.embeddings import EmbeddingError, make_embedder

        cfg = load_config()
        broken = Config(
            paths=cfg.paths,
            filters=cfg.filters,
            fixed_chunking=cfg.fixed_chunking,
            structure_chunking=cfg.structure_chunking,
            embedding_provider="nope",
            local_embedding=cfg.local_embedding,
            openai_compat=cfg.openai_compat,
            default_search_strategy=cfg.default_search_strategy,
        )
        with pytest.raises(EmbeddingError):
            make_embedder(broken)
