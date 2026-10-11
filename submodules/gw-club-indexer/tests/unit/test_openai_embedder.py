"""TDD tests for the OpenAI-compatible embedder (Task 11).

``OpenAICompatEmbedder`` talks to any OpenAI-style ``POST /v1/embeddings``
endpoint (Ollama, LM Studio, local gateways) using ONLY the stdlib
``urllib.request`` -- no requests/httpx/openai SDK.

The tests run against a local ``http.server.ThreadingHTTPServer`` mock bound
to 127.0.0.1 on an ephemeral port (port 0). No real network is ever touched.
The mock:
  * serves ``POST /v1/embeddings``;
  * returns canned OpenAI-shaped JSON ``{"data": [{"object": "embedding",
    "index": i, "embedding": [...4 floats...]}]}`` -- deliberately in
    *reverse* index order so the client must sort by ``index``;
  * records every request (method, path, headers, body) for assertions;
  * can be switched to return an HTTP 500 or a non-JSON body.
"""

from __future__ import annotations

import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
import pytest

from gwclub_indexer.config import OpenAICompatConfig
from gwclub_indexer.embeddings.base import Embedder, EmbeddingError
from gwclub_indexer.embeddings.openai_compat import OpenAICompatEmbedder

DIM = 4
API_KEY_ENV = "GWCLUB_TEST_OPENAI_KEY"
SECRET_KEY = "sk-super-secret-test-key"


def raw_vector(index: int) -> list[float]:
    """Deterministic dim-4 payload; element 0 encodes the item index.

    Kept non-degenerate (second element 1.0) so L2 normalization is well
    defined and order preservation stays observable after normalization.
    """
    return [float(index % 7) + 1.0, 1.0, 0.5, 0.25]


def normalized(index: int) -> np.ndarray:
    vec = np.asarray(raw_vector(index), dtype=np.float32)
    return vec / np.linalg.norm(vec)


class _MockHandler(BaseHTTPRequestHandler):
    """Records requests; answers with canned OpenAI embeddings JSON."""

    def do_POST(self) -> None:
        srv = self.server.mock  # wrapper attached in MockEmbeddingsServer.__init__
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        srv.record(
            {
                "method": self.command,
                "path": self.path,
                "headers": {k.lower(): v for k, v in self.headers.items()},
                "body": body,
            }
        )

        if self.path != srv.endpoint:
            self._send(404, json.dumps({"error": f"no route {self.path}"}))
            return
        if srv.fail_status is not None:
            self._send(srv.fail_status, "mock server exploded")
            return
        if srv.raw_body is not None:
            self._send(200, srv.raw_body)
            return

        try:
            doc = json.loads(body)
            inputs = doc["input"]
            if not isinstance(inputs, list):
                raise ValueError("input must be a list")  # noqa: TRY004  # API contract error, not a type bug
        except Exception:  # noqa: BLE001  # intentional: any malformed body -> 400
            self._send(400, json.dumps({"error": "bad request body"}))
            return

        # Reverse index order on purpose: the client must sort by "index".
        data = [
            {"object": "embedding", "index": i, "embedding": srv.vector(i)}
            for i in reversed(range(len(inputs)))
        ]
        self._send(
            200, json.dumps({"object": "list", "model": doc.get("model"), "data": data})
        )

    def _send(self, code: int, text: str) -> None:
        payload = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args: object) -> None:  # silence test output
        pass


class MockEmbeddingsServer:
    """ThreadingHTTPServer wrapper with recorded requests + failure switches."""

    endpoint = "/v1/embeddings"

    def __init__(self) -> None:
        self.requests: list[dict] = []
        self.fail_status: int | None = None
        self.raw_body: str | None = None
        self._requests_lock = threading.Lock()
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), _MockHandler)
        self.httpd.daemon_threads = True
        self.httpd.mock = self  # handler callback handle

    @property
    def base_url(self) -> str:
        host, port = self.httpd.server_address[:2]
        return f"http://{host}:{port}/v1"

    @staticmethod
    def vector(index: int) -> list[float]:
        return raw_vector(index)

    def record(self, entry: dict) -> None:
        with self._requests_lock:
            self.requests.append(entry)

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


@pytest.fixture()
def mock_server():
    srv = MockEmbeddingsServer()
    thread = threading.Thread(target=srv.httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield srv
    finally:
        srv.close()
        thread.join(timeout=5)


def make_embedder(server: MockEmbeddingsServer, **kwargs) -> OpenAICompatEmbedder:
    cfg = OpenAICompatConfig(
        base_url=server.base_url,
        api_key_env=API_KEY_ENV,
        model="test-embed-model",
    )
    return OpenAICompatEmbedder(cfg, **kwargs)


def closed_port() -> int:
    """Grab an ephemeral port and release it -> nothing is listening there."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


# --------------------------------------------------------------------------- #
# Happy path
# --------------------------------------------------------------------------- #
def test_happy_path_shapes_headers_and_body(mock_server) -> None:
    emb = make_embedder(mock_server)
    texts = ["a", "b", "c"]

    out = emb.embed_passages(texts)

    assert out.shape == (3, DIM)
    assert out.dtype == np.float32
    assert np.allclose(np.linalg.norm(out, axis=1), 1.0, atol=1e-5)

    assert len(mock_server.requests) == 1
    req = mock_server.requests[0]
    assert req["method"] == "POST"
    assert req["path"] == "/v1/embeddings"
    assert req["headers"]["content-type"] == "application/json"
    assert req["headers"]["authorization"].startswith("Bearer ")

    body = json.loads(req["body"])
    assert body["model"] == "test-embed-model"
    # Raw texts, NO E5-style prefixes (PREFIX_MODE == "none").
    assert body["input"] == ["a", "b", "c"]

    # Values must come from the server vectors (normalized), index-sorted.
    for i in range(3):
        np.testing.assert_allclose(out[i], normalized(i), atol=1e-5)


def test_embed_query_returns_single_vector(mock_server) -> None:
    emb = make_embedder(mock_server)

    q = emb.embed_query("как подключить Android Auto")

    assert q.shape == (DIM,)
    assert q.dtype == np.float32
    assert np.isclose(float(np.linalg.norm(q)), 1.0, atol=1e-5)
    assert len(mock_server.requests) == 1
    assert json.loads(mock_server.requests[0]["body"])["input"] == [
        "как подключить Android Auto"
    ]  # no "query: " prefix either


def test_model_name_property(mock_server) -> None:
    assert make_embedder(mock_server).model_name == "test-embed-model"


def test_openai_embedder_satisfies_protocol(mock_server) -> None:
    assert isinstance(make_embedder(mock_server), Embedder)


# --------------------------------------------------------------------------- #
# Batching & ordering
# --------------------------------------------------------------------------- #
def test_batching_130_texts_three_requests_order_preserved(mock_server) -> None:
    emb = make_embedder(mock_server)
    texts = [f"document number {i}" for i in range(130)]

    out = emb.embed_passages(texts)

    assert out.shape == (130, DIM)
    assert out.dtype == np.float32

    # Exactly ceil(130 / 64) == 3 requests with 64 / 64 / 2 inputs.
    assert len(mock_server.requests) == 3
    sizes = [len(json.loads(r["body"])["input"]) for r in mock_server.requests]
    assert sizes == [64, 64, 2]

    # Concatenated inputs reconstruct the original text order exactly.
    sent = [t for r in mock_server.requests for t in json.loads(r["body"])["input"]]
    assert sent == texts

    # Server echoes per-request LOCAL indices in REVERSE order (OpenAI
    # contract: index is 0-based within each request). The client must sort
    # each chunk by index and concatenate chunks in request order, so global
    # row i must match the vector of local index (i % 64).
    for i in (0, 5, 63, 64, 70, 127, 129):
        np.testing.assert_allclose(out[i], normalized(i % 64), atol=1e-5)


# --------------------------------------------------------------------------- #
# Errors
# --------------------------------------------------------------------------- #
def test_connection_error_mentions_url() -> None:
    port = closed_port()
    cfg = OpenAICompatConfig(
        base_url=f"http://127.0.0.1:{port}/v1",
        api_key_env=API_KEY_ENV,
        model="test-embed-model",
    )
    emb = OpenAICompatEmbedder(cfg)

    with pytest.raises(EmbeddingError) as excinfo:
        emb.embed_passages(["hello"])

    msg = str(excinfo.value)
    assert "embedding request failed" in msg
    assert f"http://127.0.0.1:{port}/v1/embeddings" in msg


def test_http_500_raises_embedding_error_without_leaking_key(
    mock_server, monkeypatch
) -> None:
    monkeypatch.setenv(API_KEY_ENV, SECRET_KEY)
    mock_server.fail_status = 500
    emb = make_embedder(mock_server)

    with pytest.raises(EmbeddingError) as excinfo:
        emb.embed_passages(["boom"])

    msg = str(excinfo.value)
    assert "embedding endpoint returned 500" in msg
    assert SECRET_KEY not in msg, "API key must never appear in error messages"


def test_invalid_json_raises_invalid_response_error(mock_server) -> None:
    mock_server.raw_body = "{not json"
    emb = make_embedder(mock_server)

    with pytest.raises(EmbeddingError, match="invalid embeddings response"):
        emb.embed_passages(["oops"])


# --------------------------------------------------------------------------- #
# dim property: lazy with constructor hint
# --------------------------------------------------------------------------- #
def test_dim_lazy_raises_before_first_call_then_returns_4(mock_server) -> None:
    emb = make_embedder(mock_server)

    with pytest.raises(EmbeddingError, match="dim unknown before first embed call"):
        _ = emb.dim

    emb.embed_passages(["warm up"])
    assert emb.dim == DIM


def test_dim_constructor_hint_available_before_call(mock_server) -> None:
    emb = make_embedder(mock_server, dim=DIM)
    assert emb.dim == DIM


# --------------------------------------------------------------------------- #
# API key handling
# --------------------------------------------------------------------------- #
def test_api_key_read_from_env(mock_server, monkeypatch) -> None:
    monkeypatch.setenv(API_KEY_ENV, SECRET_KEY)
    emb = make_embedder(mock_server)

    emb.embed_passages(["keyed"])

    req = mock_server.requests[0]
    assert req["headers"]["authorization"] == f"Bearer {SECRET_KEY}"


def test_api_key_unset_means_empty_bearer_no_crash(mock_server, monkeypatch) -> None:
    monkeypatch.delenv(API_KEY_ENV, raising=False)
    emb = make_embedder(mock_server)

    out = emb.embed_passages(["no key needed"])

    assert out.shape == (1, DIM)
    assert mock_server.requests[0]["headers"]["authorization"] == "Bearer "
