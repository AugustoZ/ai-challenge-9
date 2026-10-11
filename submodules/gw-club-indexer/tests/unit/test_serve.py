"""Unit tests for the HTTP search sidecar (Task 2). Fully offline.

The real e5 model is NEVER loaded here. Every test builds a tiny scratch
index (FAISS + SQLite) directly with ``MockEmbedder`` (dim=8) and ``FixedChunker``
on synthetic Documents, then starts ``build_server`` onto port 0 and talks to
it over the real HTTP stack via ``urllib``. The index is also loaded through
``build_server`` so the preload/warm-up/lock path is exercised for real.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from types import SimpleNamespace

import pytest

from gwclub_indexer.chunking.fixed import FixedChunker
from gwclub_indexer.chunking.tokens import WhitespaceTokenizer
from gwclub_indexer.config import FixedChunkingConfig, load_config
from gwclub_indexer.embeddings.mock import MockEmbedder
from gwclub_indexer.loader import Document
from gwclub_indexer.serve import build_server
from gwclub_indexer.storage.faiss_store import FaissStore
from gwclub_indexer.storage.sqlite_store import SQLiteStore

STRATEGY = "fixed"
DIM = 8
DEFAULT_K = 5


# --------------------------------------------------------------------------- #
# Fixtures: build a tiny real index pair (reusing the test_search pattern)
# --------------------------------------------------------------------------- #
def _doc1_content() -> str:
    return (
        "как подключить беспроводной android auto в автомобиле через меню "
        "bluetooth настроек смартфона.\n"
        "затем откройте настройки мультимедиа выберите раздел подключения "
        "и подтвердите сопряжение телефона.\n"
        "если соединение не устанавливается перезапустите головное "
        "устройство и повторите."
    )


def _doc2_content() -> str:
    return (
        "обновление прошивки головного устройства выполняется через меню "
        "настроек автомобиля после загрузки официального архива с сайта.\n"
        "сайта с архива официального загрузки после автомобиля настроек "
        "меню через выполняется устройства головного прошивки обновление.\n"
        "устройства выполняется через меню настроек автомобиля после "
        "загрузки официального архива с сайта обновление прошивки головного."
    )


def _doc3_content() -> str:
    """A 70-word sentence -> word-window fallback -> chunks longer than 200 chars."""
    words = [f"слово{i}" for i in range(70)]
    return " ".join(words) + "."


def _build_index(tmp_path: Path) -> SimpleNamespace:
    """Write a `{strategy}` index pair into tmp_path; return context."""
    mock = MockEmbedder(dim=DIM)
    chunker = FixedChunker(
        FixedChunkingConfig(max_tokens=30, overlap_tokens=6), WhitespaceTokenizer()
    )
    docs = [
        Document(
            source="ru/android_auto.md",
            title="Android Auto",
            section_h1="Android Auto",
            locale="ru",
            content=_doc1_content(),
        ),
        Document(
            source="ru/firmware.md",
            title="Прошивка магнитолы",
            section_h1="Прошивка",
            locale="ru",
            content=_doc2_content(),
        ),
        Document(
            source="ru/long_page.md",
            title="Длинная страница",
            section_h1="Длинная",
            locale="ru",
            content=_doc3_content(),
        ),
    ]
    chunks = [c for d in docs for c in chunker.chunk(d)]
    assert len(chunks) >= 4
    assert any(len(c.content) > 200 for c in chunks)  # full-content test needs a long chunk

    vectors = mock.embed_passages([c.content for c in chunks])
    store = FaissStore(mock.dim)
    store.add_vectors(vectors)
    store.save(tmp_path / f"index-{STRATEGY}.faiss")

    SQLiteStore(tmp_path / f"index-{STRATEGY}.sqlite").replace_all(
        chunks,
        embedding_model=mock.model_name,
        dim=mock.dim,
        prefix_mode="none",
        strategy=STRATEGY,
        params_json="{}",
    )

    base = load_config()
    cfg = dataclasses.replace(
        base,
        paths=dataclasses.replace(base.paths, index_dir=str(tmp_path)),
    )
    return SimpleNamespace(cfg=cfg, mock=mock, chunks=chunks, n=len(chunks), tmp_path=tmp_path)


@pytest.fixture
def serve_index(tmp_path: Path) -> SimpleNamespace:
    """A built index pair (no server started)."""
    return _build_index(tmp_path)


def _start_server(ctx: SimpleNamespace, *, embedder: MockEmbedder | None = None):
    """build_server onto port 0, serve in a daemon thread; return (server, base_url)."""
    emb = embedder if embedder is not None else ctx.mock
    srv = build_server(ctx.cfg, STRATEGY, "127.0.0.1", 0, embedder=emb, default_k=DEFAULT_K)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    base_url = f"http://127.0.0.1:{srv.server_address[1]}"
    return srv, base_url, thread


@pytest.fixture
def running(serve_index: SimpleNamespace) -> SimpleNamespace:
    """A running server on port 0; yields the base_url."""
    ctx = serve_index
    srv, base_url, thread = _start_server(ctx)
    try:
        yield SimpleNamespace(base_url=base_url, server=srv, **ctx.__dict__)
    finally:
        srv.shutdown()
        srv.server_close()
        thread.join(timeout=5)


class _SlowMock(MockEmbedder):
    """Mock that sleeps on embed_query so concurrent threads overlap on the lock."""

    def embed_query(self, text: str):
        time.sleep(0.05)
        return super().embed_query(text)


class _FailOnRequest(MockEmbedder):
    """Mock whose warm-up succeeds but per-request embed_query raises (index error)."""

    def embed_query(self, text: str):
        if text != "warmup":
            raise FileNotFoundError("index files missing at request time")
        return super().embed_query(text)


# --------------------------------------------------------------------------- #
# HTTP helpers (stdlib only)
# --------------------------------------------------------------------------- #
# Transient connection errors (e.g. macOS "Invalid argument" under concurrent
# load on an ephemeral port) are retried a few times before giving up.
_CONNECT_RETRIES = 4
_CONNECT_SLEEP = 0.05


def _open_req(req: urllib.request.Request):
    last: BaseException | None = None
    for _ in range(_CONNECT_RETRIES):
        try:
            return urllib.request.urlopen(req, timeout=10)
        except urllib.error.HTTPError:
            raise  # definitive HTTP response -> handled by the caller, no retry
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            last = exc
            time.sleep(_CONNECT_SLEEP)
    assert last is not None
    raise last


def _post(base_url: str, path: str, payload: dict):
    data = json.dumps(payload).encode("utf-8")
    try:
        with _open_req(
            urllib.request.Request(
                base_url + path,
                data=data,
                method="POST",
                headers={"Content-Type": "application/json"},
            )
        ) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        try:
            return exc.code, json.loads(raw)
        except ValueError:
            return exc.code, raw


def _get(base_url: str, path: str):
    try:
        with _open_req(urllib.request.Request(base_url + path, method="GET")) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        try:
            return exc.code, json.loads(raw)
        except ValueError:
            return exc.code, raw


def _post_raw(base_url: str, path: str, payload: dict) -> tuple[int, bytes]:
    """POST and return the RAW response bytes (for sha256 byte-identity checks)."""
    data = json.dumps(payload).encode("utf-8")
    try:
        with _open_req(
            urllib.request.Request(
                base_url + path,
                data=data,
                method="POST",
                headers={"Content-Type": "application/json"},
            )
        ) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


# --------------------------------------------------------------------------- #
# build_server() shape + warm-up
# --------------------------------------------------------------------------- #
class TestBuildServerAndWarmup:
    def test_returns_threading_httpserver(self, serve_index):
        import http.server

        srv = build_server(serve_index.cfg, STRATEGY, "127.0.0.1", 0, embedder=serve_index.mock)
        try:
            assert isinstance(srv, http.server.ThreadingHTTPServer)
            assert srv.server_address[1] != 0  # real port was bound
            assert len(srv.store) == serve_index.n
        finally:
            srv.server_close()

    def test_warmup_log_line(self, serve_index, capsys):
        srv = build_server(serve_index.cfg, STRATEGY, "127.0.0.1", 0, embedder=serve_index.mock)
        try:
            out = capsys.readouterr().out
        finally:
            srv.server_close()
        assert "gwclub serve:" in out
        assert f"стратегия {STRATEGY}" in out
        assert str(serve_index.n) in out
        assert "прогрета" in out
        assert "порт" in out


# --------------------------------------------------------------------------- #
# POST /search: happy path
# --------------------------------------------------------------------------- #
class TestServeHappy:
    def test_full_content_returned_not_snippet(self, running):
        target = next(c for c in running.chunks if len(c.content) > 200)
        status, results = _post(running.base_url, "/search", {"query": target.content, "k": 3})

        assert status == 200
        assert isinstance(results, list) and len(results) >= 1
        first = results[0]
        assert set(first) == {"chunk_id", "score", "source", "title", "section", "content"}
        assert first["chunk_id"] == target.chunk_id
        assert first["content"] == target.content  # FULL content, not a 200-char snippet
        assert len(first["content"]) > 200
        assert first["source"] == target.source
        assert isinstance(first["score"], float)
        assert first["score"] == round(first["score"], 4)  # already rounded to 4 decimals

    def test_k_from_body_and_default(self, running):
        target = running.chunks[0]
        # explicit k in body
        status, results = _post(running.base_url, "/search", {"query": target.content, "k": 2})
        assert status == 200
        assert len(results) == 2
        # no k in body -> default (5), clamped by index size
        status, results = _post(running.base_url, "/search", {"query": target.content})
        assert status == 200
        assert len(results) == min(DEFAULT_K, running.n)


# --------------------------------------------------------------------------- #
# POST /search: error handling
# --------------------------------------------------------------------------- #
class TestServeErrors:
    @pytest.mark.parametrize("bad", ["", "   ", "\t\n", {}])
    def test_empty_query_returns_400(self, running, bad):
        payload = bad if isinstance(bad, dict) else {"query": bad}
        status, body = _post(running.base_url, "/search", payload)

        assert status == 400
        assert body == {"error": "Пустой запрос"}

    def test_query_wrong_type_returns_400(self, running):
        status, body = _post(running.base_url, "/search", {"query": 123})

        assert status == 400
        assert body == {"error": "Пустой запрос"}

    def test_unknown_path_returns_404(self, running):
        status, _body = _post(running.base_url, "/other", {"query": "x"})

        assert status == 404

    def test_unknown_method_returns_404(self, running):
        status, _body = _get(running.base_url, "/search")

        assert status == 404

    def test_index_error_returns_500(self, serve_index):
        srv, base_url, thread = _start_server(serve_index, embedder=_FailOnRequest(dim=DIM))
        try:
            status, body = _post(base_url, "/search", {"query": "запрос", "k": 3})
        finally:
            srv.shutdown()
            srv.server_close()
            thread.join(timeout=5)

        assert status == 500
        assert "error" in body
        assert "index files missing" in body["error"]


# --------------------------------------------------------------------------- #
# k clamping
# --------------------------------------------------------------------------- #
class TestKClamp:
    def test_k_larger_than_50_clamped_to_50_or_index(self, running):
        status, results = _post(running.base_url, "/search", {"query": "запрос", "k": 1000})
        assert status == 200
        assert len(results) == min(50, running.n)

    def test_k_zero_clamped_to_1(self, running):
        status, results = _post(running.base_url, "/search", {"query": "запрос", "k": 0})
        assert status == 200
        assert len(results) == 1

    def test_k_negative_clamped_to_1(self, running):
        status, results = _post(running.base_url, "/search", {"query": "запрос", "k": -5})
        assert status == 200
        assert len(results) == 1


# --------------------------------------------------------------------------- #
# Empty index
# --------------------------------------------------------------------------- #
class TestEmptyIndex:
    def test_empty_index_returns_200_empty_list(self, tmp_path):
        mock = MockEmbedder(dim=DIM)
        store = FaissStore(DIM)  # no vectors added
        store.save(tmp_path / f"index-{STRATEGY}.faiss")
        SQLiteStore(tmp_path / f"index-{STRATEGY}.sqlite").replace_all(
            [],
            embedding_model=mock.model_name,
            dim=mock.dim,
            prefix_mode="none",
            strategy=STRATEGY,
            params_json="{}",
        )
        base = load_config()
        cfg = dataclasses.replace(base, paths=dataclasses.replace(base.paths, index_dir=str(tmp_path)))
        ctx = SimpleNamespace(cfg=cfg, mock=mock, n=0)

        srv, base_url, thread = _start_server(ctx)
        try:
            status, results = _post(base_url, "/search", {"query": "любой запрос", "k": 5})
        finally:
            srv.shutdown()
            srv.server_close()
            thread.join(timeout=5)

        assert status == 200
        assert results == []


# --------------------------------------------------------------------------- #
# Concurrency
# --------------------------------------------------------------------------- #
class TestConcurrency:
    def test_parallel_requests_all_ok(self, serve_index):
        slow = _SlowMock(dim=DIM)
        srv, base_url, thread = _start_server(serve_index, embedder=slow)
        target = serve_index.chunks[0]
        outcomes: list[tuple[int, dict]] = []

        def worker() -> None:
            status, body = _post(base_url, "/search", {"query": target.content, "k": 3})
            outcomes.append((status, body))

        threads = [threading.Thread(target=worker) for _ in range(8)]
        try:
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=15)
        finally:
            srv.shutdown()
            srv.server_close()
            thread.join(timeout=5)

        assert len(outcomes) == 8
        for status, body in outcomes:
            assert status == 200
            assert body[0]["chunk_id"] == target.chunk_id


# --------------------------------------------------------------------------- #
# v1 byte-identity regression (stage-2 params must not change v1 behavior)
# --------------------------------------------------------------------------- #
class TestV1ByteIdentity:
    """Stage-2 (k_before/threshold/rerank) being optional means a v1 request --
    one carrying none of them -- answers byte-for-byte as before. The canonical
    400/404 responses are model-independent and are checked against the snapshot
    sha256 directly; the happy-200 response is checked for default-equivalence
    (a v1 request == the same request with every stage-2 param at its default).
    """

    #: sha256 of the v1 snapshot bodies (stage2-v1-snapshot.json).
    _SHA_400 = "0b31a98b84012ef7d7e01605f84960232940b732b5def56d6def5cd40bbc686a"
    _SHA_404 = "5fde544f5e3aedd351f3618d210538071094f9469703bf42088b6c7e33e0d400"

    def test_empty_query_400_sha256_matches_snapshot(self, running):
        status, raw = _post_raw(running.base_url, "/search", {"query": ""})
        assert status == 400
        assert hashlib.sha256(raw).hexdigest() == self._SHA_400

    def test_unknown_path_404_sha256_matches_snapshot(self, running):
        status, raw = _post_raw(running.base_url, "/unknown-path", {"query": "x"})
        assert status == 404
        assert hashlib.sha256(raw).hexdigest() == self._SHA_404

    def test_v1_request_matches_explicit_defaults(self, running):
        """A v1 request (k=3, no stage-2 keys) is byte-identical to the same
        request carrying every stage-2 param at its default."""
        payload = {"query": "как отформатировать флешку", "k": 3}
        status_v1, raw_v1 = _post_raw(running.base_url, "/search", payload)
        status_def, raw_def = _post_raw(
            running.base_url,
            "/search",
            {**payload, "rerank": False, "k_before": None, "threshold": None},
        )
        assert status_v1 == status_def == 200
        assert raw_v1 == raw_def
        assert hashlib.sha256(raw_v1).hexdigest() == hashlib.sha256(raw_def).hexdigest()
