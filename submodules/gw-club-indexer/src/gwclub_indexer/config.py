"""Configuration for the gw-club-docs indexing pipeline (Task 2).

The single source of truth for every default value is :data:`DEFAULTS` below;
it is the exact content of the project-root ``config.toml``. ``config.toml`` is
an *optional* override file -- if it is absent (or ``load_config`` is called
with ``path=None``) the in-code defaults are used verbatim. No value in this
module is hardcoded anywhere other than :data:`DEFAULTS`.

Public API::

    from gwclub_indexer.config import Config, load_config, ConfigError

    cfg = load_config()                 # defaults (no config.toml required)
    cfg = load_config(Path("config.toml"))  # defaults merged with that file
    cfg = load_config(Path("missing.toml"))  # missing file -> defaults

Raises:
    ConfigError: any validation rule is violated (see
    :func:`_validate`). ``ConfigError`` subclasses ``ValueError``.
"""

from __future__ import annotations

import copy
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Any

__all__ = [
    "DEFAULTS",
    "Config",
    "ConfigError",
    "FiltersConfig",
    "FixedChunkingConfig",
    "LocalEmbeddingConfig",
    "OpenAICompatConfig",
    "PathsConfig",
    "StructureChunkingConfig",
    "load_config",
]


class ConfigError(ValueError):
    """Raised when a config value is missing or fails a validation rule."""


# --------------------------------------------------------------------------- #
# Typed sections
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class PathsConfig:
    """Filesystem locations used by the pipeline."""

    docs_root: str
    index_dir: str
    groundtruth_file: str
    reports_dir: str


@dataclass(frozen=True)
class FiltersConfig:
    """Corpus filtering rules applied before chunking."""

    exclude_paths: list[str]
    exclude_draft: bool
    min_chars: int


@dataclass(frozen=True)
class FixedChunkingConfig:
    """Fixed-size (token-window) chunking parameters."""

    max_tokens: int
    overlap_tokens: int


@dataclass(frozen=True)
class StructureChunkingConfig:
    """Structure-aware chunking parameters (split on headings, merge small)."""

    max_tokens: int
    overlap_tokens: int
    merge_short_sections: bool
    min_section_tokens: int


@dataclass(frozen=True)
class LocalEmbeddingConfig:
    """Parameters for the local (sentence-transformers) embedding provider."""

    model: str
    device: str


@dataclass(frozen=True)
class OpenAICompatConfig:
    """Parameters for an OpenAI-compatible embeddings endpoint."""

    base_url: str
    api_key_env: str
    model: str


@dataclass(frozen=True)
class Config:
    """Top-level assembly of every config section."""

    paths: PathsConfig
    filters: FiltersConfig
    fixed_chunking: FixedChunkingConfig
    structure_chunking: StructureChunkingConfig
    embedding_provider: str
    local_embedding: LocalEmbeddingConfig
    openai_compat: OpenAICompatConfig
    default_search_strategy: str
    rerank_model: str | None = None


# --------------------------------------------------------------------------- #
# Defaults -- the single source of truth (mirrors project-root config.toml)
# --------------------------------------------------------------------------- #
DEFAULTS: dict[str, Any] = {
    "paths": {
        "docs_root": "/Users/augustoz/AI Advent Challenge 9/gw-club-docs",
        "index_dir": "data/index",
        "groundtruth_file": "eval/groundtruth.yaml",
        "reports_dir": "reports",
    },
    "filters": {
        "exclude_paths": [
            "ru/test.md",
            "ru/test-page.md",
            "ru/test_haval_table.md",
            "ru/Test-install.md",
        ],
        "exclude_draft": True,
        "min_chars": 60,
    },
    "chunking": {
        "fixed": {"max_tokens": 400, "overlap_tokens": 60},
        "structure": {
            "max_tokens": 400,
            "overlap_tokens": 60,
            "merge_short_sections": True,
            "min_section_tokens": 40,
        },
    },
    "embedding": {
        "provider": "local",
        "model": "intfloat/multilingual-e5-small",
        "device": "auto",
        "openai_compat": {
            "base_url": "http://localhost:11434/v1",
            "api_key_env": "OPENAI_API_KEY",
            "model": "nomic-embed-text",
        },
    },
    "search": {
        "default_strategy": "structure",
        "rerank_model": None,
    },
}

_ALLOWED_PROVIDERS = frozenset({"local", "openai_compat"})
_ALLOWED_DEVICES = frozenset({"auto", "mps", "cpu"})


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    """Return a new dict: ``base`` with ``override`` applied on top.

    Nested dicts are merged recursively so a partial ``config.toml`` only
    replaces the keys it explicitly sets.
    """
    merged = copy.deepcopy(base)
    for key, value in override.items():
        if key in merged and isinstance(merged[key], dict) and isinstance(value, dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = copy.deepcopy(value)
    return merged


def _require(section: dict[str, Any], key: str, path: str) -> Any:
    """Fetch a required key from a section, raising a clear ConfigError."""
    try:
        return section[key]
    except KeyError:
        raise ConfigError(f"Missing required config value: {path}.{key}") from None


def _validate(
    *,
    fixed: FixedChunkingConfig,
    structure: StructureChunkingConfig,
    provider: str,
    device: str,
) -> None:
    """Enforce the documented validation rules; raise ConfigError on breach."""

    for name, chunk in (("chunking.fixed", fixed), ("chunking.structure", structure)):
        if chunk.max_tokens <= 0:
            raise ConfigError(f"{name}.max_tokens must be > 0 (got {chunk.max_tokens})")
        if chunk.overlap_tokens >= chunk.max_tokens:
            raise ConfigError(
                f"{name}.overlap_tokens ({chunk.overlap_tokens}) must be "
                f"< max_tokens ({chunk.max_tokens})"
            )

    if provider not in _ALLOWED_PROVIDERS:
        raise ConfigError(
            f"embedding.provider must be one of {sorted(_ALLOWED_PROVIDERS)} "
            f"(got {provider!r})"
        )

    if device not in _ALLOWED_DEVICES:
        raise ConfigError(
            f"embedding.device must be one of {sorted(_ALLOWED_DEVICES)} "
            f"(got {device!r})"
        )


def _build(data: dict[str, Any]) -> Config:
    """Construct and validate a :class:`Config` from a merged data dict."""
    paths_d = _require(data, "paths", "config")
    filters_d = _require(data, "filters", "config")
    chunking_d = _require(data, "chunking", "config")
    embedding_d = _require(data, "embedding", "config")
    search_d = _require(data, "search", "config")

    fixed_d = _require(chunking_d, "fixed", "config.chunking")
    structure_d = _require(chunking_d, "structure", "config.chunking")

    fixed = FixedChunkingConfig(
        max_tokens=int(_require(fixed_d, "max_tokens", "config.chunking.fixed")),
        overlap_tokens=int(
            _require(fixed_d, "overlap_tokens", "config.chunking.fixed")
        ),
    )
    structure = StructureChunkingConfig(
        max_tokens=int(
            _require(structure_d, "max_tokens", "config.chunking.structure")
        ),
        overlap_tokens=int(
            _require(structure_d, "overlap_tokens", "config.chunking.structure")
        ),
        merge_short_sections=bool(
            _require(structure_d, "merge_short_sections", "config.chunking.structure")
        ),
        min_section_tokens=int(
            _require(structure_d, "min_section_tokens", "config.chunking.structure")
        ),
    )

    provider = str(_require(embedding_d, "provider", "config.embedding"))
    device = str(_require(embedding_d, "device", "config.embedding"))
    local_model = str(_require(embedding_d, "model", "config.embedding"))

    openai_d = _require(embedding_d, "openai_compat", "config.embedding")

    _validate(
        fixed=fixed,
        structure=structure,
        provider=provider,
        device=device,
    )

    return Config(
        paths=PathsConfig(
            docs_root=str(_require(paths_d, "docs_root", "config.paths")),
            index_dir=str(_require(paths_d, "index_dir", "config.paths")),
            groundtruth_file=str(_require(paths_d, "groundtruth_file", "config.paths")),
            reports_dir=str(_require(paths_d, "reports_dir", "config.paths")),
        ),
        filters=FiltersConfig(
            exclude_paths=[
                str(p) for p in _require(filters_d, "exclude_paths", "config.filters")
            ],
            exclude_draft=bool(_require(filters_d, "exclude_draft", "config.filters")),
            min_chars=int(_require(filters_d, "min_chars", "config.filters")),
        ),
        fixed_chunking=fixed,
        structure_chunking=structure,
        embedding_provider=provider,
        local_embedding=LocalEmbeddingConfig(model=local_model, device=device),
        openai_compat=OpenAICompatConfig(
            base_url=str(
                _require(openai_d, "base_url", "config.embedding.openai_compat")
            ),
            api_key_env=str(
                _require(openai_d, "api_key_env", "config.embedding.openai_compat")
            ),
            model=str(_require(openai_d, "model", "config.embedding.openai_compat")),
        ),
        default_search_strategy=str(
            _require(search_d, "default_strategy", "config.search")
        ),
        rerank_model=search_d.get("rerank_model"),
    )


def load_config(path: Path | None = None) -> Config:
    """Load the pipeline configuration.

    Args:
        path: Optional path to a ``config.toml`` override file. When ``None``
            (or the file does not exist) the in-code :data:`DEFAULTS` are used.
            The file is parsed with the stdlib ``tomllib`` and its values are
            deep-merged over the defaults.

    Returns:
        A fully validated :class:`Config`.

    Raises:
        ConfigError: if the merged config violates a validation rule.
    """
    if path is not None:
        p = Path(path)
        if p.is_file():
            with p.open("rb") as fh:
                override = tomllib.load(fh)
            data = _deep_merge(DEFAULTS, override)
            return _build(data)
    # path is None, or the file is absent -> pure defaults
    return _build(copy.deepcopy(DEFAULTS))
