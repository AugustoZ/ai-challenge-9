"""HTTP search sidecar (Task 2): ``gwclub serve``.

Exposes a stdlib-only ``http.server.ThreadingHTTPServer`` that answers
``POST /search`` with the top-k chunks for a query. The index pair
(``index-{strategy}.faiss`` + ``index-{strategy}.sqlite``) is preloaded ONCE at
startup -- the per-call reload anti-pattern of the search CLI is deliberately
avoided here.

``index_dir`` is relative (``"data/index"`` by default), so the server MUST be
started from the repo root. This is stated here because the CLI does not
resolve the path against the repo automatically.

Preload + warm-up (exactly once at startup, in :func:`build_server`):
    ``load_config()`` -> ``make_embedder(cfg)`` -> ``FaissStore.load(...)`` +
    ``gwclub_indexer.search._load_row_map(...)``; then one ``embed_query("warmup")``
    so the model is hot before the first request. A ``threading.Lock`` wraps
    embed + search per request: ``SentenceTransformer.encode`` is not
    thread-safe and ``ThreadingHTTPServer`` spawns a thread per request.

Request contract (``POST /search``):
    body    ``{"query": str, "k": int?, "k_before": int?, "threshold": float?, "rerank": bool?}``
            The three trailing fields are stage-2 (optional): ``k_before``
            defaults to ``k`` and is auto-raised to ``k`` when smaller;
            ``threshold`` filters on the FINAL score scale (cosine ``[-1, 1]``
            when ``rerank`` is off, sigmoid ``[0, 1]`` when on); ``rerank``
            defaults to false and needs ``--rerank-model``.
    200     ``[{chunk_id, score, source, title, section, content}]`` -- FULL
            ``content`` (not the 200-char snippet of the search CLI); ``score``
            rounded to 4 decimals; ``k`` defaults to ``--k`` (5) and is clamped
            to ``[1, 50]``.
    400     empty/whitespace/non-string query -> ``{"error": "Пустой запрос"}``;
            bad stage-2 params (non-bool ``rerank``, non-int ``k_before``,
            non-number/out-of-range ``threshold``), or ``rerank=true`` without
            ``--rerank-model``.
    404     unknown path or non-POST method -> ``{"error": "Not Found"}``
    500     index error (``IndexMismatchError`` / ``FileNotFoundError``) or
            reranker failure (``RerankError``) -> ``{"error": "<msg>"}``
"""

from __future__ import annotations

import argparse
import http.server
import json
import sys
import threading
from pathlib import Path

from gwclub_indexer.config import Config, load_config
from gwclub_indexer.embeddings import Embedder, make_embedder
from gwclub_indexer.rerank import Reranker, RerankError
from gwclub_indexer.search import IndexMismatchError, _index_paths, _load_row_map
from gwclub_indexer.storage.faiss_store import FaissStore
from gwclub_indexer.storage.sqlite_store import SQLiteStore

__all__ = [
    "build_serve_subparser",
    "build_server",
    "run_serve_cli",
]

_K_MIN = 1
_K_MAX = 50
_DEFAULT_K = 5
_DEFAULT_HOST = "127.0.0.1"
_DEFAULT_PORT = 18700

_EMPTY_QUERY = {"error": "Пустой запрос"}


class _ServeHTTPServer(http.server.ThreadingHTTPServer):
    """ThreadingHTTPServer carrying the preloaded, immutable search state."""

    def __init__(
        self,
        addr: tuple[str, int],
        handler: type[http.server.BaseHTTPRequestHandler],
        *,
        store: FaissStore,
        row_map: dict[int, object],
        embedder: Embedder,
        lock: threading.Lock,
        default_k: int,
        strategy: str,
        reranker: Reranker | None = None,
    ) -> None:
        super().__init__(addr, handler)
        self.store = store
        self.row_map = row_map
        self.embedder = embedder
        self.lock = lock
        self.default_k = default_k
        self.strategy = strategy
        self.reranker = reranker

    def search_pipeline(
        self,
        query: str,
        k: int,
        k_before: int,
        threshold: float | None,
        rerank: bool,
    ) -> list[dict[str, object]]:
        """Stage-2 post-search pipeline (single implementation).

        ``search(k_before)`` -> (when ``rerank``) ``Reranker.score`` under the
        lock + descending sort -> threshold filter ``score >= threshold`` on the
        FINAL scale -> ``top-k``. Final scale: cosine ``[-1, 1]`` when rerank is
        off, sigmoid ``[0, 1]`` when on; scores rounded to 4 decimals as in v1.

        Caller MUST hold ``self.lock`` (embed/store/rerank are not thread-safe).

        Args:
            query: Non-empty query text.
            k: Final top-k (already sanitized by the handler).
            k_before: Candidate pool size (already clamped to ``[1, 50]`` and
                auto-raised to ``k`` when smaller).
            threshold: Optional threshold on the FINAL scale; ``None`` = no cut.
            rerank: Whether to re-rank candidates with ``self.reranker``.

        Returns:
            Result dicts with the v1 serialization (``chunk_id``, ``score``,
            ``source``, ``title``, ``section``, ``content``).

        Raises:
            RuntimeError: FAISS row without sqlite metadata (inconsistent
                artifact pair), or rerank requested but not configured.
            RerankError: reranker failed to load/execute (handled as 500).
        """
        q_vec = self.embedder.embed_query(query)
        rows = self.store.search(q_vec, k_before)

        candidates: list[tuple[int, object, float]] = []
        for row, cos_score in rows:
            chunk = self.row_map.get(row)
            if chunk is None:
                # FAISS row with no sqlite metadata: inconsistent artifact pair.
                raise RuntimeError(
                    f"inconsistent index: faiss row {row} has no chunk metadata "
                    f"for strategy '{self.strategy}'"
                )
            candidates.append((row, chunk, float(cos_score)))

        if rerank:
            if self.reranker is None:
                # Handled by the handler (400), but kept defensive for callers.
                raise RuntimeError("reranker is not configured")
            contents = [chunk.content for _row, chunk, _cos in candidates]
            try:
                scores = self.reranker.score(query, contents)
            except RerankError:
                raise
            except Exception as exc:  # model execution failure -> 500 RU
                raise RerankError(f"Ошибка при выполнении реранкера: {exc}") from exc
            ranked = [
                (row, chunk, float(score))
                for (row, chunk, _cos), score in zip(candidates, scores)
            ]
            ranked.sort(key=lambda item: item[2], reverse=True)
            final = ranked
        else:
            final = candidates

        if threshold is not None:
            final = [item for item in final if item[2] >= threshold]

        final = final[:k]

        return [
            {
                "chunk_id": chunk.chunk_id,
                "score": round(float(score), 4),
                "source": chunk.source,
                "title": chunk.title,
                "section": chunk.section,
                "content": chunk.content,
            }
            for _row, chunk, score in final
        ]


class _SearchHandler(http.server.BaseHTTPRequestHandler):
    """``POST /search`` handler; reads search state off ``self.server``."""

    server_version = "gwclub-serve/0.1"

    # -- unsupported methods -> 404 (spec: unknown path/method -> 404) ---------- #
    def do_GET(self) -> None:
        self._not_found()

    def do_HEAD(self) -> None:
        self._not_found()

    def do_PUT(self) -> None:
        self._not_found()

    def do_DELETE(self) -> None:
        self._not_found()

    def do_OPTIONS(self) -> None:
        self._not_found()

    def do_PATCH(self) -> None:
        self._not_found()

    # -- POST ---------------------------------------------------------------- #
    def do_POST(self) -> None:
        if self.path != "/search":
            self._not_found()
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        raw = self.rfile.read(length) if length > 0 else b""

        if not raw:
            self._send_json(400, _EMPTY_QUERY)
            return
        try:
            body = json.loads(raw.decode("utf-8"))
            if not isinstance(body, dict):
                raise TypeError("expected a JSON object")
        except (TypeError, ValueError, UnicodeDecodeError):
            self._send_json(400, _EMPTY_QUERY)
            return

        query = body.get("query", "")
        if not isinstance(query, str) or query.strip() == "":
            self._send_json(400, _EMPTY_QUERY)
            return

        raw_k = body.get("k", self.server.default_k)
        if isinstance(raw_k, bool) or not isinstance(raw_k, int):
            raw_k = self.server.default_k
        k = max(_K_MIN, min(_K_MAX, raw_k))

        # -- stage-2 optional params (all default to v1 behavior) ------------ #
        rerank = body.get("rerank", False)
        if not isinstance(rerank, bool):
            self._send_json(
                400, {"error": "Параметр rerank должен быть логическим значением (true/false)"}
            )
            return
        if rerank and self.server.reranker is None:
            self._send_json(
                400, {"error": "Реранкер не настроен: запустите serve с --rerank-model"}
            )
            return

        raw_k_before = body.get("k_before", None)
        if raw_k_before is None:
            k_before = k
        else:
            if isinstance(raw_k_before, bool) or not isinstance(raw_k_before, int):
                self._send_json(
                    400, {"error": "Параметр k_before должен быть целым числом"}
                )
                return
            k_before = max(_K_MIN, min(_K_MAX, raw_k_before))
            k_before = max(k_before, k)  # documented: auto-raise to k, not a 400

        threshold: float | None = None
        raw_threshold = body.get("threshold", None)
        if raw_threshold is not None:
            if isinstance(raw_threshold, bool) or not isinstance(
                raw_threshold, (int, float)
            ):
                self._send_json(400, {"error": "Параметр threshold должен быть числом"})
                return
            threshold = float(raw_threshold)
            if rerank:
                if not (0.0 <= threshold <= 1.0):
                    self._send_json(
                        400,
                        {
                            "error": "Порог threshold должен быть в диапазоне "
                            f"[0, 1] (получено: {threshold})"
                        },
                    )
                    return
            elif not (-1.0 <= threshold <= 1.0):
                self._send_json(
                    400,
                    {
                        "error": "Порог threshold должен быть в диапазоне "
                        f"[-1, 1] (получено: {threshold})"
                    },
                )
                return

        try:
            with self.server.lock:
                results = self.server.search_pipeline(query, k, k_before, threshold, rerank)
            self._send_json(200, results)
        except (IndexMismatchError, FileNotFoundError) as exc:
            self._send_json(500, {"error": str(exc)})
        except RerankError as exc:
            self._send_json(500, {"error": str(exc)})
        except RuntimeError as exc:
            # Защитная сетка: RuntimeError из search_pipeline (несогласованный
            # индекс; defensive «reranker is not configured») → чистый 500 JSON
            # вместо сырого traceback-ответа. Сегодня недостижимо через HTTP
            # (400-валидация перекрывает defensive-ветку) — это hardening.
            self._send_json(500, {"error": str(exc)})

    # -- helpers ------------------------------------------------------------- #
    def _not_found(self) -> None:
        self._send_json(404, {"error": "Not Found"})

    def _send_json(self, status: int, payload: object) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def build_server(
    cfg: Config,
    strategy: str,
    host: str,
    port: int,
    *,
    embedder: Embedder | None = None,
    default_k: int = _DEFAULT_K,
    reranker: Reranker | None = None,
) -> _ServeHTTPServer:
    """Preload the index + embedder, warm up the model, and bind the server.

    Args:
        cfg: Pipeline config (``cfg.paths.index_dir`` is searched).
        strategy: Index strategy name (``"fixed"`` or ``"structure"``).
        host: Bind host (``"127.0.0.1"`` by default).
        port: Bind port; ``0`` selects an ephemeral port.
        embedder: Optional pre-built embedder; default ``make_embedder(cfg)``.
            Tests inject ``MockEmbedder`` to stay offline.
        default_k: Default top-k when a request omits ``k``.
        reranker: Optional configured :class:`Reranker`; ``None`` means the
            ``rerank=true`` request is rejected with 400 (no ``--rerank-model``).

    Returns:
        A :class:`_ServeHTTPServer` (subclass of ``ThreadingHTTPServer``) that has
        NOT yet been asked to serve. The handler and search state are already
        bound; call ``serve_forever()`` on it.

    Raises:
        FileNotFoundError: either index artifact is missing at startup.
        IndexMismatchError: index meta (embedding model / dim) disagrees with
            the embedder -- the server fails fast rather than erroring per-request.
    """
    emb = embedder if embedder is not None else make_embedder(cfg)

    # Fail fast at startup: a missing/mismatched index is a config error, not
    # something to answer with 500 per request.
    faiss_path, sqlite_path = _index_paths(cfg, strategy)
    if not faiss_path.is_file():
        raise FileNotFoundError(
            f"index not built for strategy '{strategy}': expected {faiss_path}"
        )
    if not sqlite_path.is_file():
        raise FileNotFoundError(
            f"index not built for strategy '{strategy}': expected {sqlite_path}"
        )

    store = FaissStore.load(faiss_path)
    row_map = _load_row_map(sqlite_path, strategy)

    meta = SQLiteStore(sqlite_path)
    meta_model = meta.get_meta("embedding_model")
    meta_dim_raw = meta.get_meta("dim")
    meta_dim = int(meta_dim_raw) if meta_dim_raw is not None else -1
    if meta_model != emb.model_name or meta_dim != emb.dim:
        raise IndexMismatchError(
            f"embedding model mismatch: index built with {meta_model} "
            f"(dim={meta_dim}), requested {emb.model_name} (dim={emb.dim})"
        )

    # Warm the model once before the first request (SentenceTransformer loads
    # lazily; we want the first /search to be fast already).
    emb.embed_query("warmup")

    server = _ServeHTTPServer(
        (host, port),
        _SearchHandler,
        store=store,
        row_map=row_map,
        embedder=emb,
        lock=threading.Lock(),
        default_k=default_k,
        strategy=strategy,
        reranker=reranker,
    )
    print(
        f"gwclub serve: стратегия {strategy}, {len(store)} чанков, "
        f"модель прогрета, порт {server.server_address[1]}",
        flush=True,
    )
    return server


def run_serve_cli(args: argparse.Namespace) -> int:
    """CLI handler for ``gwclub serve``. Blocks on ``serve_forever``.

    Reads ``args.config, strategy, host, port, k, rerank_model`` (getattr with
    defaults). Index errors print ``error: ...`` to stderr and return a non-zero
    exit code instead of leaking a traceback.
    """
    config_path = getattr(args, "config", None)
    cfg = load_config(Path(config_path)) if config_path else load_config()
    strategy = getattr(args, "strategy", None) or cfg.default_search_strategy
    host = getattr(args, "host", _DEFAULT_HOST)
    port = getattr(args, "port", _DEFAULT_PORT)
    k = getattr(args, "k", _DEFAULT_K)
    rerank_model = getattr(args, "rerank_model", None) or cfg.rerank_model
    reranker = Reranker(rerank_model) if rerank_model else None

    try:
        server = build_server(cfg, strategy, host, port, default_k=k, reranker=reranker)
    except IndexMismatchError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except (ValueError, FileNotFoundError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    # Warm once at startup (no separate flag): first rerank request stays fast,
    # and a load failure here is a config error rather than a per-request 500.
    if reranker is not None:
        try:
            reranker.score("warmup", ["warmup"])
        except (RerankError, TypeError) as exc:
            print(f"error: {exc}", file=sys.stderr)
            server.server_close()
            return 1

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("gwclub serve: остановлен", file=sys.stderr)
    finally:
        server.server_close()
    return 0


def build_serve_subparser(
    subparsers: argparse._SubParsersAction,
) -> argparse.ArgumentParser:
    """Register the ``serve`` subcommand (Task 2); cli.py wires it in one line."""
    parser = subparsers.add_parser(
        "serve",
        help="Start the HTTP search sidecar (POST /search).",
    )
    parser.add_argument(
        "--config", default=None, help="path to a config.toml override file"
    )
    parser.add_argument(
        "--strategy",
        choices=["fixed", "structure"],
        default=None,
        help="index strategy (default: cfg.default_search_strategy)",
    )
    parser.add_argument(
        "--host",
        default=_DEFAULT_HOST,
        help=f"bind host (default: {_DEFAULT_HOST})",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=_DEFAULT_PORT,
        help=f"bind port (default: {_DEFAULT_PORT})",
    )
    parser.add_argument(
        "-k",
        "--k",
        dest="k",
        type=int,
        default=_DEFAULT_K,
        help=f"default top-k for requests without a k field (default: {_DEFAULT_K})",
    )
    parser.add_argument(
        "--rerank-model",
        dest="rerank_model",
        default=None,
        help="CrossEncoder model id for the re-ranking stage-2 pipeline "
        "(default: cfg.search.rerank_model; unset -> re-ranking unavailable)",
    )
    parser.set_defaults(func=run_serve_cli)
    return parser
