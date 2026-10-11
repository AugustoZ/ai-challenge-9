"""OpenAI-compatible embeddings client (Task 11).

A dependency-light adapter that talks to ANY OpenAI-style embeddings endpoint
(``POST {base_url}/embeddings``) -- OpenAI itself, Ollama, LM Studio, local
gateways -- using ONLY the stdlib ``urllib.request``. No requests/httpx/openai
SDK, so the import graph and dependency set stay untouched.

Wire contract (OpenAI ``/v1/embeddings``):
    request  : ``{"model": str, "input": [str, ...]}`` with
               ``Authorization: Bearer <key>`` and
               ``Content-Type: application/json``.
    response : ``{"data": [{"object": "embedding", "index": i,
                            "embedding": [float, ...]}, ...], ...}``

Conventions:
    * ``PREFIX_MODE = "none"`` -- OpenAI-compatible models use their own text
      conventions; unlike E5-style local models we send RAW text with no
      ``"passage: "`` / ``"query: "`` prefixes.
    * Batching: requests are sent in chunks of 64 input texts.
    * ``dim`` is LAZY: the vector size is unknown until the first successful
      response (unless an explicit ``dim`` constructor hint was provided).
    * The API key is read from ``os.environ[cfg.api_key_env]`` at call time
      (default ``""`` -- many local servers accept an empty bearer) and is
      NEVER included in error messages or logs.
    * Result rows are L2-normalized float32 to honor the shared ``Embedder``
      vector convention (cosine == inner product).
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

import numpy as np

from gwclub_indexer.config import OpenAICompatConfig
from gwclub_indexer.embeddings.base import EmbeddingError

__all__ = ["PREFIX_MODE", "OpenAICompatEmbedder"]

#: OpenAI-compatible models get NO E5-style "passage: "/"query: " prefixes.
PREFIX_MODE = "none"

#: Number of texts sent per HTTP request.
BATCH_SIZE = 64

#: Socket timeout (seconds) for a single embeddings request.
TIMEOUT_S = 60.0


class OpenAICompatEmbedder:
    """Embedder backed by an OpenAI-compatible ``/embeddings`` HTTP endpoint.

    Satisfies the structural :class:`gwclub_indexer.embeddings.base.Embedder`
    protocol (``embed_passages`` -> ``(N, dim)`` float32 L2-normalized,
    ``embed_query`` -> ``(dim,)``, ``model_name``/``dim`` properties).
    """

    def __init__(self, cfg: OpenAICompatConfig, dim: int | None = None) -> None:
        """Create the client.

        Args:
            cfg: Endpoint/settings (base_url, api_key_env, model).
            dim: Optional known vector dimensionality. When omitted, ``dim``
                stays unknown (the property raises) until the first
                successful embed call learns it from the response.
        """
        self._cfg = cfg
        self._hint_dim = dim
        self._learned_dim: int | None = None

    # ------------------------------------------------------------------ #
    # Embedder protocol surface
    # ------------------------------------------------------------------ #
    @property
    def model_name(self) -> str:
        """Stable model identifier (``cfg.model``)."""
        return self._cfg.model

    @property
    def dim(self) -> int:
        """Vector dimensionality; lazy unless a constructor hint was given."""
        if self._hint_dim is not None:
            return self._hint_dim
        if self._learned_dim is None:
            raise EmbeddingError("dim unknown before first embed call")
        return self._learned_dim

    @property
    def prefix_mode(self) -> str:
        """``"none"`` -- raw text is sent, no E5-style prefixes."""
        return PREFIX_MODE

    def embed_passages(self, texts: list[str]) -> np.ndarray:
        """Embed a batch of passage texts (raw, no prefixes).

        Sends ``ceil(len(texts) / BATCH_SIZE)`` requests. Returns an
        ``(N, dim)`` float32 array with L2-normalized rows, order preserved
        (rows sorted by the server's ``data[i].index``).
        """
        if not texts:
            known = self._hint_dim if self._hint_dim is not None else self._learned_dim
            return np.zeros((0, known or 0), dtype=np.float32)

        rows: list[list[float]] = []
        for start in range(0, len(texts), BATCH_SIZE):
            chunk = texts[start : start + BATCH_SIZE]
            rows.extend(self._post_batch(chunk))

        try:
            arr = np.asarray(rows, dtype=np.float32)
        except (TypeError, ValueError) as exc:
            raise EmbeddingError(
                f"invalid embeddings response from {self._endpoint()}"
            ) from exc
        if arr.ndim != 2 or arr.shape[0] != len(texts):
            raise EmbeddingError(f"invalid embeddings response from {self._endpoint()}")

        norms = np.linalg.norm(arr, axis=1, keepdims=True)
        norms[norms == 0.0] = 1.0
        arr = arr / norms

        if self._learned_dim is None:
            self._learned_dim = int(arr.shape[1])
        return arr

    def embed_query(self, text: str) -> np.ndarray:
        """Embed a single query string (raw, no prefix) -> ``(dim,)``."""
        return self.embed_passages([text])[0]

    # ------------------------------------------------------------------ #
    # HTTP plumbing (stdlib only)
    # ------------------------------------------------------------------ #
    def _endpoint(self) -> str:
        return self._cfg.base_url.rstrip("/") + "/embeddings"

    def _api_key(self) -> str:
        """API key from the configured env var; empty string when unset.

        The value is used ONLY for the Authorization header -- never logged.
        """
        return os.environ.get(self._cfg.api_key_env, "")

    def _post_batch(self, texts: list[str]) -> list[list[float]]:
        """POST one batch, parse and index-sort the returned embeddings."""
        url = self._endpoint()
        payload = json.dumps({"model": self._cfg.model, "input": list(texts)}).encode(
            "utf-8"
        )
        request = urllib.request.Request(
            url,
            data=payload,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "Authorization": f"Bearer {self._api_key()}",
            },
        )

        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:  # must precede URLError (subclass)
            try:
                body = exc.read().decode("utf-8", "replace")
            except Exception:  # noqa: BLE001  # any read failure -> empty body, keep the HTTP status
                body = ""
            raise EmbeddingError(
                f"embedding endpoint returned {exc.code}: {body[:200]}"
            ) from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            reason = getattr(exc, "reason", exc)
            raise EmbeddingError(f"embedding request failed: {url}: {reason}") from exc

        try:
            doc = json.loads(raw.decode("utf-8"))
            items = doc["data"]
            rows = sorted(
                (
                    (int(item["index"]), [float(x) for x in item["embedding"]])
                    for item in items
                ),
                key=lambda pair: pair[0],
            )
            return [vec for _, vec in rows]
        except (AttributeError, KeyError, TypeError, ValueError) as exc:
            raise EmbeddingError(f"invalid embeddings response from {url}") from exc
