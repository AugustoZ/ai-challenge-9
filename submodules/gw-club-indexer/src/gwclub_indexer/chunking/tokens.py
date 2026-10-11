"""Tokenizer contract and a whitespace-based implementation (Task 8).

Chunkers only care about a duck-typed ``tokenizer`` exposing
``count_tokens(text: str) -> int``. This module defines the structural
``Tokenizer`` protocol for type hints plus a deterministic, dependency-free
``WhitespaceTokenizer`` used by offline unit tests (1 whitespace-separated word
== 1 token). The real HF tokenizer wrapper lives elsewhere (indexer).

Public API::

    from gwclub_indexer.chunking.tokens import Tokenizer, WhitespaceTokenizer
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

__all__ = ["Tokenizer", "WhitespaceTokenizer"]


@runtime_checkable
class Tokenizer(Protocol):
    """Structural protocol every tokenizer must satisfy.

    ``isinstance``/``issubclass`` checks verify only that the runtime object has
    a callable ``count_tokens`` attribute (structural typing), matching the
    duck-typed contract used across the pipeline.
    """

    def count_tokens(self, text: str) -> int:
        """Return the number of tokens ``text`` would consume."""
        ...


class WhitespaceTokenizer:
    """Tokenize by splitting on whitespace: 1 whitespace-separated word = 1 token.

    Empty and whitespace-only strings return 0. Deterministic and free of any
    external dependency — ideal as the mock tokenizer for offline unit tests
    where 1 word == 1 token.
    """

    def count_tokens(self, text: str) -> int:
        return len(text.split())
