"""Unit tests for ``gwclub_indexer.config`` (Task 2).

TDD: these tests are written FIRST (red), then the config module is
implemented to make them green. They exercise the documented defaults,
TOML-override merging, and validation rules. They never touch the real
corpus (``/Users/augustoz/AI Advent Challenge 9/gw-club-docs``) or the real
project-root ``config.toml`` -- only ``tmp_path`` / ``monkeypatch``.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from gwclub_indexer.config import (
    Config,
    ConfigError,
    FiltersConfig,
    FixedChunkingConfig,
    LocalEmbeddingConfig,
    OpenAICompatConfig,
    PathsConfig,
    StructureChunkingConfig,
    load_config,
)

_EXCLUDE_PATHS = [
    "ru/test.md",
    "ru/test-page.md",
    "ru/test_haval_table.md",
    "ru/Test-install.md",
]


def _write_toml(tmp_path: Path, body: str) -> Path:
    p = tmp_path / "config.toml"
    p.write_text(body, encoding="utf-8")
    return p


def test_defaults_without_file(tmp_path, monkeypatch):
    """``load_config(path=None)`` returns full defaults without any filesystem
    access. ``monkeypatch.chdir`` guarantees the cwd has no ``config.toml``.
    """
    monkeypatch.chdir(tmp_path)  # cwd deliberately has no config.toml
    cfg = load_config(path=None)

    assert isinstance(cfg, Config)

    # [paths]
    assert isinstance(cfg.paths, PathsConfig)
    assert cfg.paths.docs_root == "/Users/augustoz/AI Advent Challenge 9/gw-club-docs"
    assert cfg.paths.index_dir == "data/index"
    assert cfg.paths.groundtruth_file == "eval/groundtruth.yaml"
    assert cfg.paths.reports_dir == "reports"

    # [filters]
    assert isinstance(cfg.filters, FiltersConfig)
    assert cfg.filters.exclude_paths == _EXCLUDE_PATHS
    assert cfg.filters.exclude_draft is True
    assert cfg.filters.min_chars == 60

    # [chunking.fixed]
    assert isinstance(cfg.fixed_chunking, FixedChunkingConfig)
    assert cfg.fixed_chunking.max_tokens == 400
    assert cfg.fixed_chunking.overlap_tokens == 60

    # [chunking.structure]
    assert isinstance(cfg.structure_chunking, StructureChunkingConfig)
    assert cfg.structure_chunking.max_tokens == 400
    assert cfg.structure_chunking.overlap_tokens == 60
    assert cfg.structure_chunking.merge_short_sections is True
    assert cfg.structure_chunking.min_section_tokens == 40

    # [embedding]
    assert cfg.embedding_provider == "local"
    assert isinstance(cfg.local_embedding, LocalEmbeddingConfig)
    assert cfg.local_embedding.model == "intfloat/multilingual-e5-small"
    assert cfg.local_embedding.device == "auto"

    # [embedding.openai_compat]
    assert isinstance(cfg.openai_compat, OpenAICompatConfig)
    assert cfg.openai_compat.base_url == "http://localhost:11434/v1"
    assert cfg.openai_compat.api_key_env == "OPENAI_API_KEY"
    assert cfg.openai_compat.model == "nomic-embed-text"

    # [search]
    assert cfg.default_search_strategy == "structure"


def test_toml_override(tmp_path):
    """A provided TOML overrides defaults; unlisted keys keep their defaults."""
    toml = _write_toml(
        tmp_path,
        """
[paths]
docs_root = "/tmp/custom-docs"
index_dir = "out/index"

[filters]
min_chars = 120
exclude_draft = false

[chunking.fixed]
max_tokens = 250
overlap_tokens = 40

[chunking.structure]
max_tokens = 300
overlap_tokens = 50
merge_short_sections = false
min_section_tokens = 25

[embedding]
provider = "openai_compat"
model = "my/local-model"
device = "cpu"

[embedding.openai_compat]
base_url = "http://example.com/v1"
api_key_env = "MY_KEY"
model = "nomic-embed-text"

[search]
default_strategy = "fixed"
""",
    )
    cfg = load_config(toml)

    # overridden keys
    assert cfg.paths.docs_root == "/tmp/custom-docs"
    assert cfg.paths.index_dir == "out/index"
    assert cfg.filters.min_chars == 120
    assert cfg.filters.exclude_draft is False
    assert cfg.fixed_chunking.max_tokens == 250
    assert cfg.fixed_chunking.overlap_tokens == 40
    assert cfg.structure_chunking.max_tokens == 300
    assert cfg.structure_chunking.overlap_tokens == 50
    assert cfg.structure_chunking.merge_short_sections is False
    assert cfg.structure_chunking.min_section_tokens == 25
    assert cfg.embedding_provider == "openai_compat"
    assert cfg.local_embedding.model == "my/local-model"
    assert cfg.local_embedding.device == "cpu"
    assert cfg.openai_compat.base_url == "http://example.com/v1"
    assert cfg.openai_compat.api_key_env == "MY_KEY"
    assert cfg.default_search_strategy == "fixed"

    # unlisted keys keep their defaults
    assert cfg.paths.groundtruth_file == "eval/groundtruth.yaml"
    assert cfg.paths.reports_dir == "reports"
    assert cfg.filters.exclude_paths == _EXCLUDE_PATHS
    assert cfg.openai_compat.model == "nomic-embed-text"


def test_missing_file_falls_back_to_defaults(tmp_path, monkeypatch):
    """Passing a path that does not exist still yields defaults (config.toml
    is an optional override, never mandatory)."""
    monkeypatch.chdir(tmp_path)
    cfg = load_config(tmp_path / "does_not_exist.toml")
    assert cfg.paths.docs_root == "/Users/augustoz/AI Advent Challenge 9/gw-club-docs"
    assert cfg.fixed_chunking.max_tokens == 400
    assert cfg.embedding_provider == "local"


def test_search_default_strategy(tmp_path, monkeypatch):
    """``search.default_strategy`` defaults to ``structure``."""
    monkeypatch.chdir(tmp_path)
    assert load_config(path=None).default_search_strategy == "structure"


def test_overlap_ge_max_raises(tmp_path):
    """overlap_tokens >= max_tokens is invalid."""
    toml = _write_toml(
        tmp_path,
        "[chunking.fixed]\nmax_tokens = 100\noverlap_tokens = 100\n",
    )
    with pytest.raises(ConfigError):
        load_config(toml)


def test_structure_overlap_ge_max_raises(tmp_path):
    """overlap_tokens >= max_tokens is invalid for structure chunking too."""
    toml = _write_toml(
        tmp_path,
        "[chunking.structure]\nmax_tokens = 50\noverlap_tokens = 60\n",
    )
    with pytest.raises(ConfigError):
        load_config(toml)


def test_zero_max_tokens_raises(tmp_path):
    """max_tokens <= 0 is invalid."""
    toml = _write_toml(
        tmp_path,
        "[chunking.fixed]\nmax_tokens = 0\noverlap_tokens = 10\n",
    )
    with pytest.raises(ConfigError):
        load_config(toml)


def test_bad_provider_raises(tmp_path):
    """provider must be one of {local, openai_compat}."""
    toml = _write_toml(tmp_path, '[embedding]\nprovider = "nope"\n')
    with pytest.raises(ConfigError):
        load_config(toml)


def test_bad_device_raises(tmp_path):
    """device must be one of {auto, mps, cpu}."""
    toml = _write_toml(tmp_path, '[embedding]\ndevice = "gpu"\n')
    with pytest.raises(ConfigError):
        load_config(toml)
