"""CrossEncoder re-ranker wrapper for stage-2 post-processing (Task 3).

Wraps ``sentence-transformers`` ``CrossEncoder`` (default:
``BAAI/bge-reranker-v2-m3``, ~2.2 GB one-time HF download) behind a small,
thread-safe re-scoring API. This is a *separate* stage from the :mod:`search`
module: pure cosine search returns candidate hits, and this wrapper optionally
re-scores them on a [0, 1] sigmoid scale.

Design decisions (mirrors the repo's ``embeddings/local.py`` idiom):

* **Lazy load under a lock** -- the weights are loaded on the first
  :meth:`Reranker.score` call (or ``__init__`` never loads), guarded by a
  ``threading.Lock`` so concurrent first calls load the model EXACTLY ONCE.
  Unit tests inject a fake via ``_model=`` / ``_factory=`` and never import torch.
* **Explicit sigmoid** -- ``CrossEncoder(..., activation_fn=nn.Sigmoid(), ...)``
  is passed explicitly; the sentence-transformers 6.x default for ``num_labels=1``
  happens to be Sigmoid, but we never rely on it.
* **Russian error** -- a load failure raises :class:`RerankError` with a message
  naming the model and hinting at the cause (never a raw traceback).

Public API::

    from gwclub_indexer.rerank import Reranker, RerankError

    r = Reranker("BAAI/bge-reranker-v2-m3")   # device="auto", max_length=512
    scores = r.score("как отформатировать флешку", [chunk1, chunk2])
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from typing import Any

import numpy as np

__all__ = [
    "RerankError",
    "Reranker",
]

logger = logging.getLogger(__name__)

#: Default token window forwarded to the CrossEncoder (documented in the plan).
_DEFAULT_MAX_LENGTH = 512


class RerankError(Exception):
    """Raised when the CrossEncoder model fails to load."""


def _default_factory(
    model_name: str, *, device: str | None, max_length: int
) -> Any:
    """Construct the real ``CrossEncoder`` with an EXPLICIT sigmoid activation.

    Imported lazily (never at module import) so unit tests that inject a fake
    never import torch / sentence_transformers.
    """
    from sentence_transformers import CrossEncoder
    from torch import nn

    kwargs: dict[str, Any] = {
        "activation_fn": nn.Sigmoid(),  # explicit -- never trust the ST 6.x default
        "max_length": max_length,
    }
    if device is not None:
        kwargs["device"] = device
    return CrossEncoder(model_name, **kwargs)


class Reranker:
    """Re-ranks candidate documents against a query with a CrossEncoder.

    Args:
        model_name: HF model id, e.g. ``"BAAI/bge-reranker-v2-m3"``.
        device: Optional device string (``"cpu"``/``"mps"``). ``None`` resolves
            to ``"mps"`` when available, else ``"cpu"`` (mirrors the embedder).
        max_length: Maximum token window forwarded to the model (default 512).
        _model: Test seam -- a pre-built model exposing ``predict(pairs, **kw)``.
            When given, the factory path is skipped (offline unit tests).
        _factory: Test seam -- callable ``(model_name, *, device, max_length)``
            constructing the model; defaults to :func:`_default_factory`.
    """

    def __init__(
        self,
        model_name: str,
        *,
        device: str | None = None,
        max_length: int = _DEFAULT_MAX_LENGTH,
        _model: Any | None = None,
        _factory: Callable[..., Any] | None = None,
    ) -> None:
        if not isinstance(model_name, str) or model_name.strip() == "":
            raise TypeError(
                f"model_name must be a non-empty str, got {model_name!r}"
            )
        if not isinstance(max_length, int):
            raise TypeError(f"max_length must be an int, got {type(max_length).__name__}")
        self._model_name: str = model_name
        self._device: str | None = device
        self._max_length: int = max_length
        self._model: Any | None = _model
        self._factory: Callable[..., Any] = (
            _factory if _factory is not None else _default_factory
        )
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ #
    # Public API
    # ------------------------------------------------------------------ #
    def score(self, query: str, documents: list[str]) -> list[float]:
        """Return sigmoid relevance scores ``in [0, 1]`` for each document.

        The query and documents are passed RAW (the reranker adds no E5
        ``query:``/``passage:`` prefixes -- only the embedder does). Scores stay
        parallel to ``documents``; sort them descending to get a re-ranked order.

        Args:
            query: The query text.
            documents: Candidate documents (their full chunk content).

        Returns:
            A list of floats, one per document, each in ``[0, 1]``.

        Raises:
            TypeError: ``query``/``documents``/a document element has a bad type.
            RerankError: the model failed to load (Russian message with a hint).
        """
        if not isinstance(query, str):
            raise TypeError(f"query must be a str, got {type(query).__name__}")
        if not isinstance(documents, list):
            raise TypeError(
                f"documents must be a list, got {type(documents).__name__}"
            )
        for doc in documents:
            if not isinstance(doc, str):
                raise TypeError(
                    f"every document must be a str, got {type(doc).__name__}"
                )

        if not documents:
            return []

        model = self._get_model()
        out = np.asarray(model.predict(
            [(query, doc) for doc in documents],
            convert_to_numpy=True,
        )).reshape(-1)
        return [float(x) for x in out]

    # ------------------------------------------------------------------ #
    # Internals
    # ------------------------------------------------------------------ #
    def _pick_device(self) -> str:
        """Resolve the configured device; ``None`` -> mps-if-available -> cpu."""
        if self._device is not None:
            return self._device
        import torch

        device = "mps" if torch.backends.mps.is_available() else "cpu"
        logger.info("Reranker auto device selection -> %s", device)
        return device

    def _get_model(self) -> Any:
        """Return the (possibly lazily loaded) model, loading it exactly once.

        Double-checked locking: the fast path returns the cached model without
        the lock; a missed cache then acquires the lock and re-checks, so
        concurrent first calls construct the model EXACTLY ONCE.
        """
        if self._model is not None:
            return self._model
        with self._lock:
            if self._model is None:
                self._model = self._load_model()
            return self._model

    def _load_model(self) -> Any:
        """Construct the CrossEncoder; wrap failures in a Russian RerankError."""
        device = self._pick_device()
        logger.info(
            "Loading CrossEncoder %r on device=%s (first use, ~2.2GB download "
            "on first run, cached in ~/.cache/huggingface)",
            self._model_name,
            device,
        )
        try:
            return self._factory(
                self._model_name,
                device=device,
                max_length=self._max_length,
            )
        except Exception as exc:
            raise RerankError(
                f"Не удалось загрузить модель реранкера {self._model_name!r}: "
                f"{exc}. Проверьте подключение к Hugging Face и наличие весов "
                f"модели (или скачайте её заранее)."
            ) from exc
