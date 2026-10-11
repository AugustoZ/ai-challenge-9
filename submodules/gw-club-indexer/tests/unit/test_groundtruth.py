"""Unit tests for gwclub_indexer.eval.groundtruth and cli.gwclub groundtruth (Task 12).

The generator is deterministic and rule-based -- no LLM/api is ever called.
Fixtures are built directly from inline strings (no corpus on disk); a fast,
read-only real-corpus test checks the acceptance criteria.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from gwclub_indexer.cli import main as cli_main
from gwclub_indexer.config import load_config
from gwclub_indexer.eval.groundtruth import (
    HEADER_COMMENT,
    GroundTruthItem,
    generate_ground_truth,
    load_ground_truth,
    save_ground_truth,
)
from gwclub_indexer.loader import Document, load_documents

PROJECT_ROOT = Path(__file__).resolve().parents[2]


def _doc(source: str, content: str, frontmatter: dict | None = None) -> Document:
    """Build a :class:`Document` directly from an inline string (no corpus)."""
    return Document(
        source=source,
        title=source,
        section_h1="",
        locale="ru",
        content=content,
        frontmatter=frontmatter or {},
    )


def _as_tuple(item: GroundTruthItem) -> tuple[str, str, str, str]:
    return (item.query, item.source, item.section, item.origin)


def _fixture_docs() -> list[Document]:
    """Three mini documents that exercise every branch of the generator.

    - ``ru/install.md``: a non-FAQ doc with H2/H3 headings -> heading items.
    - ``ru/FAQ-all.md``: a FAQ doc with an H3 question and a ``?`` list item
      -> faq items (the H3 question also overlaps a heading item -> dedup).
    - ``ru/draft.md``: declared ``draft: true`` -> must contribute nothing.
    """
    install = _doc(
        "ru/install.md",
        "# ROX Premium\n"
        "## 1. Установка прошивки\n"
        "### Какая флешка нужна для установки прошивки?\n"
        "Тело раздела.\n"
        "## 2. Активация и оплата\n"
        "Тело.\n",
    )
    faq = _doc(
        "ru/FAQ-all.md",
        "# FAQ общие вопросы\n"
        "## 1. Подготовка флешки\n"
        "### Какая флешка нужна для установки прошивки?\n"
        "Ответ.\n"
        "- Как отформатировать флешку для установки прошивки?\n",
    )
    draft = _doc(
        "ru/draft.md",
        "# Черновик\n## Секция для пропуска\nТело.\n",
        frontmatter={"draft": True},
    )
    return [install, faq, draft]


def test_counts_and_dedup_faq_wins() -> None:
    items = generate_ground_truth(load_config(), _fixture_docs())

    assert len(items) == 5
    assert sum(1 for i in items if i.origin == "heading") == 3
    assert sum(1 for i in items if i.origin == "faq") == 2

    # dedup: the question appears once and survives as "faq" (not "heading")
    question = "Какая флешка нужна для установки прошивки?"
    matches = [i for i in items if i.query == question]
    assert len(matches) == 1
    assert matches[0].origin == "faq"


def test_heading_items_use_trail_including_heading() -> None:
    items = generate_ground_truth(load_config(), _fixture_docs())

    by_query = {i.query: i for i in items}
    assert by_query["1. Установка прошивки"].section == "1. Установка прошивки"
    h2 = by_query["2. Активация и оплата"]
    assert h2.section == "2. Активация и оплата"
    assert h2.origin == "heading"


def test_short_heading_skipped() -> None:
    """A one-word/very-short heading never becomes a heading query."""
    doc = _doc(
        "ru/short.md",
        "# ROX\n## Установка\nТело.\n## 1. Установка прошивки\nТело.\n",
    )
    items = generate_ground_truth(load_config(), [doc])
    queries = [i.query for i in items]
    assert "Установка" not in queries  # single word -> skipped
    assert "1. Установка прошивки" in queries


def test_draft_doc_contributes_nothing() -> None:
    """A draft passed directly is skipped -- the generator must not trust the loader."""
    base = _fixture_docs()[:2]  # install + faq
    with_draft = _fixture_docs()  # install + faq + draft

    items_base = generate_ground_truth(load_config(), base)
    items_draft = generate_ground_truth(load_config(), with_draft)

    assert len(items_draft) == len(items_base)
    assert not any(i.source == "ru/draft.md" for i in items_draft)


def test_cap_respected_when_faq_overflows() -> None:
    """Many FAQ questions still never exceed MAX_ITEMS."""
    questions = "\n".join(
        f"### Вопрос номер {i:02d} для проверки лимита?" for i in range(1, 41)
    )
    faq = _doc("ru/FAQ-all.md", f"# FAQ\n## 1. Вопросы\n{questions}\n")
    items = generate_ground_truth(load_config(), [faq])

    assert 15 <= len(items) <= 30
    assert len(items) == 30
    assert all(i.origin == "faq" for i in items)


def test_yaml_roundtrip(tmp_path: Path) -> None:
    """save_ground_truth + load_ground_truth round-trip faithfully."""
    items = generate_ground_truth(load_config(), _fixture_docs())
    path = tmp_path / "groundtruth.yaml"

    save_ground_truth(items, path)

    text = path.read_text(encoding="utf-8")
    assert text.startswith(HEADER_COMMENT)

    loaded = load_ground_truth(path)
    assert {_as_tuple(i) for i in loaded} == {_as_tuple(i) for i in items}
    assert all(isinstance(i, GroundTruthItem) for i in loaded)


def test_load_unknown_origin_raises(tmp_path: Path) -> None:
    """An origin outside {heading, faq} raises ValueError."""
    path = tmp_path / "bad.yaml"
    path.write_text(
        "- query: x\n  source: ru/a.md\n  section: s\n  origin: bogus\n",
        encoding="utf-8",
    )
    with pytest.raises(ValueError):
        load_ground_truth(path)


def test_load_accepts_mapping_with_items_key(tmp_path: Path) -> None:
    """load_ground_truth also accepts a mapping whose keys is ``items``."""
    path = tmp_path / "wrapped.yaml"
    path.write_text(
        "items:\n"
        "  - query: q\n    source: ru/a.md\n    section: s\n    origin: heading\n",
        encoding="utf-8",
    )
    loaded = load_ground_truth(path)
    assert len(loaded) == 1
    assert loaded[0].query == "q"
    assert loaded[0].origin == "heading"


# --------------------------------------------------------------------------- #
# CLI: `gwclub groundtruth`
# --------------------------------------------------------------------------- #
def test_cli_groundtruth_writes_file_and_returns_zero(tmp_path: Path, capsys) -> None:
    """``gwclub groundtruth --output <tmp>`` writes YAML, prints a summary, exit 0."""
    out = tmp_path / "gt.yaml"
    code = cli_main(["groundtruth", "--output", str(out)])

    captured = capsys.readouterr()
    assert code == 0
    assert out.is_file()
    assert captured.out.startswith("Ground truth:")
    assert "→" in captured.out

    items = load_ground_truth(out)
    assert 15 <= len(items) <= 30
    assert all(i.query and i.source and i.section for i in items)


def test_cli_help_exits_zero(capsys) -> None:
    """``gwclub --help`` is valid and lists the groundtruth subcommand."""
    code = cli_main(["--help"])
    captured = capsys.readouterr()
    assert code == 0
    assert "groundtruth" in captured.out


# --------------------------------------------------------------------------- #
# Real-corpus acceptance check (read-only, fast, offline)
# --------------------------------------------------------------------------- #
def test_real_corpus_ground_truth() -> None:
    """Generating on the real ru/ corpus yields 15-30 well-formed items."""
    cfg = load_config(PROJECT_ROOT / "config.toml")
    docs = load_documents(cfg)
    items = generate_ground_truth(cfg, docs)

    assert 15 <= len(items) <= 30
    assert all(i.query and i.source and i.section for i in items)
    assert {i.origin for i in items} <= {"heading", "faq"}
    # The documented "faq" items come from the three FAQ files only.
    faq_sources = {i.source for i in items if i.origin == "faq"}
    assert not any(s.endswith("faq.md") for s in faq_sources)
