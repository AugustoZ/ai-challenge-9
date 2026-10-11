"""Unit tests for gwclub_indexer.loader (Task 3).

TDD fixtures live in tmp_path; one regular (non-integration) test reads the
real ru/ corpus read-only as the acceptance check.
"""

from __future__ import annotations

import dataclasses
from pathlib import Path

import pytest

from gwclub_indexer.config import Config, load_config
from gwclub_indexer.loader import Document, load_documents

PROJECT_ROOT = Path(__file__).resolve().parents[2]


def _make_cfg(docs_root: Path, **filter_overrides) -> Config:
    """Build a Config with paths/filters pointed at a scratch corpus."""
    base = load_config()  # in-code defaults, offline, no toml needed
    paths = dataclasses.replace(base.paths, docs_root=str(docs_root))
    filters = dataclasses.replace(
        base.filters,
        exclude_paths=["ru/excluded.md"],
        **filter_overrides,
    )
    return dataclasses.replace(base, paths=paths, filters=filters)


@pytest.fixture
def corpus(tmp_path: Path) -> Path:
    """Scratch docs_root mirroring the real corpus's quirks."""
    ru = tmp_path / "ru"
    ru.mkdir()

    (ru / "normal.md").write_text(
        "---\n"
        'title: "Norm Title"\n'
        "locale: ru\n"
        "draft: false\n"
        "---\n"
        "\n"
        "# Norm H1\n"
        "\n"
        "Normal body text, long enough to pass the min_chars filter.\n",
        encoding="utf-8",
    )
    (ru / "no_fm.md").write_text(
        "# No FM Title\n\nBody text long enough to pass the min_chars filter.\n",
        encoding="utf-8",
    )
    (ru / "draft.md").write_text(
        "---\n"
        'title: "Draft Title"\n'
        "draft: true\n"
        "---\n"
        "# Draft H1\n\nBody text long enough to pass the min_chars filter.\n",
        encoding="utf-8",
    )
    (ru / "excluded.md").write_text(
        "# Excluded H1\n\nBody text long enough to pass the min_chars filter.\n",
        encoding="utf-8",
    )
    (ru / "short.md").write_text("# Short\n\ntiny\n", encoding="utf-8")
    # Mirrors ru/tank_300_ultra_multimedia_firmware_guide.md: no frontmatter,
    # a `---` horizontal rule inside the body must stay in content.
    (ru / "hr_body.md").write_text(
        "# HR Title\n"
        "\n"
        "First paragraph before the rule, padded to exceed the minimum length.\n"
        "\n"
        "---\n"
        "\n"
        "Second paragraph after the horizontal rule, still part of the body.\n",
        encoding="utf-8",
    )
    (ru / "no_h1.md").write_text(
        "Just a plain paragraph with no heading at all, padded long enough here.\n",
        encoding="utf-8",
    )

    en = tmp_path / "en"
    en.mkdir()
    (en / "notes.md").write_text(
        "# EN Doc\n\nBody text long enough to pass the min_chars filter.\n",
        encoding="utf-8",
    )
    return tmp_path


@pytest.fixture
def docs(corpus: Path) -> list[Document]:
    """Default-filter load: excludes draft/excluded/short, ru/ only."""
    return load_documents(_make_cfg(corpus))


def _by_source(docs: list[Document]) -> dict[str, Document]:
    return {d.source: d for d in docs}


def test_expected_sources_loaded_sorted(docs: list[Document]) -> None:
    sources = [d.source for d in docs]
    assert sources == sorted(sources)
    assert sources == [
        "ru/hr_body.md",
        "ru/no_fm.md",
        "ru/no_h1.md",
        "ru/normal.md",
    ]


def test_normal_frontmatter_parsed(docs: list[Document]) -> None:
    d = _by_source(docs)["ru/normal.md"]
    assert isinstance(d, Document)
    assert d.source == "ru/normal.md"
    assert d.title == "Norm Title"
    assert d.section_h1 == "Norm H1"
    assert d.locale == "ru"
    assert d.frontmatter == {"title": "Norm Title", "locale": "ru", "draft": False}
    assert d.content.startswith("# Norm H1")
    assert "---" not in d.content.splitlines()[0]
    assert "Norm Title" not in d.content


def test_file_without_frontmatter_falls_back_to_h1(docs: list[Document]) -> None:
    d = _by_source(docs)["ru/no_fm.md"]
    assert d.title == "No FM Title"
    assert d.section_h1 == "No FM Title"
    assert d.locale == "ru"
    assert d.frontmatter == {}


def test_draft_excluded_by_default(corpus: Path) -> None:
    sources = {d.source for d in load_documents(_make_cfg(corpus))}
    assert "ru/draft.md" not in sources


def test_draft_included_when_exclude_draft_false(corpus: Path) -> None:
    cfg = _make_cfg(corpus, exclude_draft=False)
    sources = {d.source for d in load_documents(cfg)}
    assert "ru/draft.md" in sources


def test_excluded_path_skipped(docs: list[Document]) -> None:
    assert "ru/excluded.md" not in _by_source(docs)


def test_short_file_skipped(docs: list[Document]) -> None:
    assert "ru/short.md" not in _by_source(docs)


def test_hr_in_body_not_treated_as_frontmatter(docs: list[Document]) -> None:
    d = _by_source(docs)["ru/hr_body.md"]
    assert d.frontmatter == {}
    assert d.title == "HR Title"
    assert "---" in d.content
    assert "First paragraph before the rule" in d.content
    assert "Second paragraph after the horizontal rule" in d.content


def test_title_from_stem_when_no_h1(docs: list[Document]) -> None:
    d = _by_source(docs)["ru/no_h1.md"]
    assert d.title == "no_h1"
    assert d.section_h1 == ""


def test_en_directory_ignored(docs: list[Document]) -> None:
    assert not any(s.startswith("en/") for s in (d.source for d in docs))


# --------------------------------------------------------------------------- #
# Real-corpus acceptance check (read-only, fast, offline)
# --------------------------------------------------------------------------- #
def test_real_corpus_loads_33_ru_documents() -> None:
    cfg = load_config(PROJECT_ROOT / "config.toml")
    docs = load_documents(cfg)
    sources = [d.source for d in docs]

    # 38 ru files - 4 excluded test files - 1 draft = 33
    assert len(docs) == 33
    assert not any("test" in s.lower() for s in sources)
    assert all(d.locale == "ru" for d in docs)
    assert all(s.startswith("ru/") for s in sources)
    assert sources == sorted(sources)

    # The known frontmatter-less file with a `---` hr inside its body
    tank = _by_source(docs)["ru/tank_300_ultra_multimedia_firmware_guide.md"]
    assert tank.frontmatter == {}
    assert tank.locale == "ru"
    assert "---" in tank.content
    assert tank.title.startswith("Руководство")
