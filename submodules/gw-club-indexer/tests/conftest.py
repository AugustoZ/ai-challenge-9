"""Shared pytest fixtures for gw-club-indexer (Task 7)."""

from __future__ import annotations

from pathlib import Path

import pytest

from gwclub_indexer.embeddings.mock import MockEmbedder


@pytest.fixture
def mock_embedder() -> MockEmbedder:
    """Deterministic offline embedder (dim=8) for offline unit tests."""
    return MockEmbedder(dim=8)


@pytest.fixture
def tmp_index_dir(tmp_path: Path) -> Path:
    """Scratch directory for index artifacts (FAISS/SQLite) written by tests."""
    return tmp_path
