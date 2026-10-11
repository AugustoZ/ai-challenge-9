"""Scaffold smoke test: all key dependencies import (Task 1)."""

import faiss
import numpy
import sentence_transformers
import yaml

import gwclub_indexer


def test_imports():
    assert gwclub_indexer.__version__ == "0.1.0"
    assert hasattr(faiss, "IndexFlatIP")
    assert hasattr(sentence_transformers, "SentenceTransformer")
    assert numpy.__version__
    assert yaml.__version__
