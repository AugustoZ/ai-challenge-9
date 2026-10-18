#!/usr/bin/env python3
"""Собирает RAG-корпус документации проекта для бота «Штурман».

Источники — живые репозитории; в корпус идут СИМЛИНКИ (истина не копируется,
устаревание исключено):

- ai-challenge-9  (веб-Штурман):  корневые *.md, frontend/*.md,
  mcp-servers/README.md, sandbox/README.md, config/pipelines/README.md,
  openspec/**/*.md
- shturman-bot: корневые *.md

Раскладка — ``<корпус>/ru/<репо>/<относительный путь>``: загрузчик
gwclub_indexer читает ``ru/**/*.md`` (AGENTS.md в этой раскладке НЕ
исключается — он и есть главная база знаний проекта).

Запуск:  python3 scripts/build_project_corpus.py [--index]
--index  — дополнительно перестроить индекс (gwclub index, venv индексера).

После ребилда индекса перезапустите serve :18702 (без manifest hot-reload
не подхватывает: см. shturman-dev SKILL).
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

BOT_ROOT = Path(__file__).resolve().parent.parent
PROJECTS = BOT_ROOT.parent
REPO_MAIN = PROJECTS / "ai-challenge-9"
CORPUS = REPO_MAIN / "docs"
INDEXER = Path.home() / "GW-CLUB" / "gw-club-indexer"
INDEXER_VENV_GWCLUB = INDEXER / ".venv" / "bin" / "gwclub"
CONFIG = BOT_ROOT / "config-rag-project.toml"

# Классификация источников: (репо, относительный путь) — только документация.
_RULES: list[tuple[str, tuple[str, ...]]] = [
    (REPO_MAIN.name, ("*.md",)),
    (REPO_MAIN.name, ("frontend/*.md",)),
    (REPO_MAIN.name, ("mcp-servers/README.md", "sandbox/README.md",
                      "config/pipelines/README.md")),
    (REPO_MAIN.name, ("openspec/**/*.md",)),
    ("shturman-bot", ("*.md",)),
]
# Точечные исключения внутри правил (в исходниках мусора нет, но пусть будет).
_EXCLUDE_NAMES = {"LICENSE.md"}


def _iter_sources():
    seen: set[Path] = set()
    for repo, patterns in _RULES:
        root = PROJECTS / repo
        for pat in patterns:
            for path in sorted(root.glob(pat)):
                if not path.is_file() or path.name in _EXCLUDE_NAMES:
                    continue
                if path.resolve() in seen:
                    continue
                seen.add(path.resolve())
                yield repo, path.relative_to(root)


def build_corpus() -> int:
    """Пересоздаёт дерево симлинков в CORPUS; возвращает число документов."""
    if CORPUS.exists():
        for old in sorted(CORPUS.rglob("*"), reverse=True):
            if old.is_symlink() or old.is_file():
                old.unlink()
            elif old.is_dir():
                old.rmdir()
    CORPUS.mkdir(parents=True, exist_ok=True)
    n = 0
    for repo, rel in _iter_sources():
        target_dir = CORPUS / "ru" / repo / rel.parent
        target_dir.mkdir(parents=True, exist_ok=True)
        link = target_dir / rel.name
        link.symlink_to((PROJECTS / repo / rel).resolve())
        n += 1
    return n


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--index", action="store_true",
                    help="после сборки корпуса перестроить индекс")
    args = ap.parse_args()

    n = build_corpus()
    print(f"корпус: {n} документов → {CORPUS}/ru/ (симлинки на живые файлы)")
    if not args.index:
        return 0
    proc = subprocess.run(
        [str(INDEXER_VENV_GWCLUB), "index", "--config", str(CONFIG),
         "--strategy", "structure"],
        cwd=str(INDEXER),
    )
    if proc.returncode != 0:
        print("gwclub index упал — serve :18702 продолжает на старом индексе",
              file=sys.stderr)
        return proc.returncode
    print("индекс готов. Перезапустите serve :18702, чтобы он его подхватил.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
