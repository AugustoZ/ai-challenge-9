"""Unit tests for FaissStore (Task 6).

Cosine semantics = inner product on L2-normalized vectors (project standard —
evaluation metrics depend on it). Tiny dims (8), runs offline, no model.
"""

import numpy as np
import pytest

from gwclub_indexer.storage.faiss_store import FaissStore

DIM = 8


def _e(i: int) -> np.ndarray:
    """Unit vector along axis i (float32)."""
    v = np.zeros(DIM, dtype=np.float32)
    v[i] = 1.0
    return v


class TestCosineSemantics:
    def test_search_returns_cosine_scores_sorted_desc(self):
        store = FaissStore(DIM)
        vec3 = np.concatenate([[0.6, 0.8], np.zeros(DIM - 2)]).astype(np.float32)
        store.add_vectors(np.stack([_e(0), _e(1), vec3]))

        results = store.search(_e(0), k=2)

        assert len(results) == 2
        assert results[0][0] == 0  # top-1 = row 0, exact match
        assert results[0][1] == pytest.approx(1.0, abs=1e-5)
        assert results[1][0] == 2  # cos with axis 0 = 0.6
        assert results[1][1] == pytest.approx(0.6, abs=1e-5)
        assert results[0][1] >= results[1][1]  # descending

    def test_unnormalized_query_is_normalized(self):
        store = FaissStore(DIM)
        store.add_vectors(np.stack([_e(0), _e(1)]))

        results = store.search(3.0 * _e(0), k=1)

        assert results[0][0] == 0
        assert results[0][1] == pytest.approx(1.0, abs=1e-5)

    def test_zero_norm_row_left_as_is_no_crash(self):
        store = FaissStore(DIM)
        store.add_vectors(np.stack([np.zeros(DIM, dtype=np.float32), _e(0)]))

        results = store.search(_e(0), k=2)

        assert results[0][0] == 1  # real vector wins
        assert results[1][0] == 0  # zero row scores 0.0
        assert results[1][1] == pytest.approx(0.0, abs=1e-6)


class TestValidation:
    def test_add_wrong_width_raises_valueerror(self):
        store = FaissStore(DIM)
        with pytest.raises(ValueError, match="dimension"):
            store.add_vectors(np.zeros((2, 5), dtype=np.float32))

    def test_add_non_2d_raises_valueerror(self):
        store = FaissStore(DIM)
        with pytest.raises(ValueError):
            store.add_vectors(np.zeros(DIM, dtype=np.float32))  # 1-D

    def test_search_wrong_width_raises_valueerror(self):
        store = FaissStore(DIM)
        store.add_vectors(np.stack([_e(0)]))
        with pytest.raises(ValueError, match="dimension"):
            store.search(np.zeros(5, dtype=np.float32), k=1)


class TestKClampAndProps:
    def test_k_larger_than_ntotal_returns_all_available(self):
        store = FaissStore(DIM)
        store.add_vectors(np.stack([_e(0), _e(1)]))

        results = store.search(_e(0), k=100)

        assert len(results) == 2

    def test_dim_and_len_properties(self):
        store = FaissStore(DIM)
        assert store.dim == DIM
        assert len(store) == 0
        store.add_vectors(np.stack([_e(0), _e(1), _e(2)]))
        assert len(store) == 3


class TestSaveLoad:
    def test_roundtrip_preserves_search_results(self, tmp_path):
        store = FaissStore(DIM)
        store.add_vectors(np.stack([_e(0), _e(1), _e(2)]))
        before = store.search(_e(0), k=3)

        path = tmp_path / "nested" / "dir" / "index.faiss"  # parent dir missing
        store.save(path)

        loaded = FaissStore.load(path)
        assert loaded.dim == DIM
        assert len(loaded) == 3

        after = loaded.search(_e(0), k=3)
        assert [row for row, _ in after] == [row for row, _ in before]
        for (_, s_b), (_, s_a) in zip(before, after):
            assert s_a == pytest.approx(s_b, abs=1e-6)

    def test_save_accepts_str_path(self, tmp_path):
        store = FaissStore(DIM)
        store.add_vectors(np.stack([_e(0)]))
        path = str(tmp_path / "idx.faiss")
        store.save(path)
        assert FaissStore.load(path).dim == DIM
