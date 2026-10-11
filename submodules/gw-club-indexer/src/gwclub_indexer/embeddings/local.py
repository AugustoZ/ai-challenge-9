"""Local sentence-transformers embedder with E5 input prefixes (Task 10).

Wraps ``intfloat/multilingual-e5-small`` (default) behind the structural
:class:`~gwclub_indexer.embeddings.base.Embedder` protocol.

E5 prefix convention (the #1 quality gotcha -- silent quality degradation
without it): the E5 checkpoint was trained with inputs prefixed ``"query: "``
or ``"passage: "``. Callers therefore pass RAW text; this embedder adds the
correct prefix itself:

* :meth:`embed_passages` -> ``"passage: " + t`` for every text;
* :meth:`embed_query`    -> ``"query: " + text``.

No other module may apply these prefixes.

Operational notes:
    * The model is loaded LAZILY on the first embed call (or ``dim`` access)
      and cached on the instance; tests inject a fake via ``_model=`` so unit
      tests never load real weights or import torch.
    * Device: config ``device="auto"`` resolves to ``"mps"`` when
      ``torch.backends.mps.is_available()`` (Apple Silicon) else ``"cpu"``;
      explicit ``"mps"``/``"cpu"`` is respected as-is.
    * fp16: on mps/cuda the model is cast with ``model.half()`` (halves memory
      and speeds up inference); any failure falls back to fp32 with a logged
      line -- never a crash.
    * The embedder re-normalizes outputs defensively so the float32
      L2-normalized contract holds even if a backend skips normalization.

sentence-transformers 6.1.0 note: its ``SentenceTransformer.__init__`` has no
``token_parallelism`` parameter (that is an env-var ``TOKENIZERS_PARALLELISM``
concern); we try passing ``token_parallelism=1`` as specified and transparently
retry without it on ``TypeError`` (see learnings).
"""

from __future__ import annotations

import logging
from typing import Any

import numpy as np

from gwclub_indexer.embeddings.base import EmbeddingError

logger = logging.getLogger(__name__)

#: Batch size forwarded to ``model.encode`` and used to chunk passage lists.
_BATCH_SIZE = 32

#: Devices that trigger the fp16 (``model.half()``) cast.
_HALF_DEVICES = frozenset({"mps", "cuda"})


class LocalEmbedder:
    """Sentence-transformers embedder adding E5 ``query:``/``passage:`` prefixes.

    Satisfies the ``Embedder`` protocol structurally (no subclassing):
    ``embed_passages(list[str]) -> (N, dim) float32``, ``embed_query(str) ->
    (dim,) float32``, both L2-normalized; ``model_name``/``dim`` properties.

    Args:
        cfg: ``LocalEmbeddingConfig``-like object exposing ``.model`` and
            ``.device``; a plain string is also accepted and treated as the
            model id with ``device="auto"``.
        model_name: Optional override of the model id (wins over ``cfg.model``).
        _model: Test seam -- an already-loaded model exposing ``encode`` and
            ``get_sentence_embedding_dimension()``. When given, nothing is
            imported or loaded (offline unit tests).
    """

    #: Stored by the indexer in index_meta so runs are reproducible/auditable.
    PREFIX_MODE = "e5"

    def __init__(
        self,
        cfg: Any,
        model_name: str | None = None,
        _model: Any | None = None,
    ) -> None:
        if isinstance(cfg, str):
            cfg_model, device = cfg, "auto"
        else:
            cfg_model = getattr(cfg, "model", None)
            device = getattr(cfg, "device", "auto")
        if model_name is not None:
            cfg_model = model_name
        if not cfg_model or not isinstance(cfg_model, str):
            raise EmbeddingError(f"invalid embedding model id: {cfg_model!r}")
        self._model_id: str = cfg_model
        self._device: str = str(device)
        self._model: Any | None = _model  # injected (tests) or lazily loaded
        self._fp16_applied: bool = False

    # ------------------------------------------------------------------ #
    # Protocol properties
    # ------------------------------------------------------------------ #
    @property
    def model_name(self) -> str:
        """The embedding model id (e.g. ``"intfloat/multilingual-e5-small"``)."""
        return self._model_id

    @property
    def dim(self) -> int:
        """Dimensionality of produced vectors (triggers the lazy load)."""
        return self._model_dim(self._get_model())

    # ------------------------------------------------------------------ #
    # Embedding API
    # ------------------------------------------------------------------ #
    def embed_passages(self, texts: list[str]) -> np.ndarray:
        """Embed raw passage texts with the ``"passage: "`` E5 prefix.

        Returns a ``(N, dim)`` ``float32`` array of L2-normalized rows; an
        empty input yields ``(0, dim)``. Texts are sent to the model in
        chunks of at most :data:`_BATCH_SIZE`, preserving order.
        """
        model = self._get_model()
        dim = self._model_dim(model)
        if not texts:
            return np.empty((0, dim), dtype=np.float32)
        if isinstance(texts, str):
            raise EmbeddingError(
                "embed_passages expects a list of strings, got a single str"
            )
        chunks: list[np.ndarray] = []
        for start in range(0, len(texts), _BATCH_SIZE):
            batch = texts[start : start + _BATCH_SIZE]
            prefixed = ["passage: " + t for t in batch]
            out = model.encode(
                prefixed,
                batch_size=_BATCH_SIZE,
                normalize_embeddings=True,
                convert_to_numpy=True,
            )
            chunks.append(np.asarray(out, dtype=np.float32))
        return self._finalize(np.vstack(chunks))

    def embed_query(self, text: str) -> np.ndarray:
        """Embed a single raw query string with the ``"query: "`` E5 prefix."""
        if not isinstance(text, str):
            raise EmbeddingError(
                f"embed_query expects a str, got {type(text).__name__}"
            )
        model = self._get_model()
        out = model.encode(
            ["query: " + text],
            batch_size=_BATCH_SIZE,
            normalize_embeddings=True,
            convert_to_numpy=True,
        )
        return self._finalize(np.asarray(out, dtype=np.float32))[0]

    # ------------------------------------------------------------------ #
    # Internals
    # ------------------------------------------------------------------ #
    def _pick_device(self) -> str:
        """Resolve the configured device; ``auto`` -> mps-if-available -> cpu."""
        if self._device != "auto":
            return self._device
        import torch

        device = "mps" if torch.backends.mps.is_available() else "cpu"
        logger.info("LocalEmbedder auto device selection -> %s", device)
        return device

    def _get_model(self) -> Any:
        """Return the cached model, loading it lazily on first use."""
        if self._model is None:
            self._model = self._load_model()
        self._apply_fp16_once(self._model)
        return self._model

    def _load_model(self) -> Any:
        """Construct ``SentenceTransformer`` (fp16 cast handled separately)."""
        device = self._pick_device()
        logger.info(
            "Loading SentenceTransformer %r on device=%s (first use, "
            "~470MB download on first run, cached in ~/.cache/huggingface)",
            self._model_id,
            device,
        )
        from sentence_transformers import SentenceTransformer

        try:
            model = SentenceTransformer(
                self._model_id, device=device, token_parallelism=1
            )
        except TypeError:
            # sentence-transformers 6.1.0 dropped/never had this kwarg.
            logger.info(
                "SentenceTransformer(%r) rejected token_parallelism; "
                "retrying without it",
                self._model_id,
            )
            try:
                model = SentenceTransformer(self._model_id, device=device)
            except Exception as exc:
                raise EmbeddingError(
                    f"failed to load model {self._model_id!r}: {exc}"
                ) from exc
        except Exception as exc:
            raise EmbeddingError(
                f"failed to load model {self._model_id!r}: {exc}"
            ) from exc
        return model

    def _apply_fp16_once(self, model: Any) -> None:
        """Cast the model to fp16 on mps/cuda (once); fall back to fp32."""
        if self._fp16_applied:
            return
        self._fp16_applied = True
        if self._pick_device() not in _HALF_DEVICES:
            return
        try:
            model.half()
            logger.info("fp16 enabled (model.half()) on device=%s", self._pick_device())
        except Exception as exc:  # noqa: BLE001 - fp16 is an optimization
            logger.info(
                "model.half() failed on device=%s (%s); continuing in fp32",
                self._pick_device(),
                exc,
            )

    @staticmethod
    def _model_dim(model: Any) -> int:
        """Model dimension via the ST 6.x name, falling back to the old one.

        sentence-transformers 6.1.0 renamed ``get_sentence_embedding_dimension``
        to ``get_embedding_dimension`` (the old name still works but warns).
        """
        getter = getattr(model, "get_embedding_dimension", None) or getattr(
            model, "get_sentence_embedding_dimension", None
        )
        if getter is None:
            raise EmbeddingError(
                "model exposes neither get_embedding_dimension() nor "
                "get_sentence_embedding_dimension()"
            )
        return int(getter())

    @staticmethod
    def _finalize(vecs: np.ndarray) -> np.ndarray:
        """Ensure the output contract: float32, rows L2-normalized."""
        vecs = np.asarray(vecs, dtype=np.float32)
        if vecs.ndim == 1:
            vecs = vecs[None, :]
        norms = np.linalg.norm(vecs, axis=1, keepdims=True)
        norms = np.where(norms == 0.0, 1.0, norms)  # guard zero vectors
        return (vecs / norms).astype(np.float32, copy=False)
