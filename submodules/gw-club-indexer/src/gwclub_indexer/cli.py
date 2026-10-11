"""Command-line entry point for the gwclub pipeline (Task 12).

Exposes the ``gwclub`` console script declared in ``pyproject.toml``. The
``main`` function dispatches to per-command handlers (``_cmd_groundtruth``,
``_cmd_index``, plus the ``search`` / ``eval`` handlers wired by Task 16) so
the CLI stays extensible.

Usage::

    gwclub --help
    gwclub groundtruth [--config PATH] [--output PATH]
    gwclub index [--config PATH] [--strategy {fixed,structure,both}]
    gwclub search [--config PATH] [--strategy {fixed,structure}] [-k N] [--json] QUERY
    gwclub eval [--config PATH]
"""

from __future__ import annotations

import argparse
from pathlib import Path

from gwclub_indexer.config import Config, load_config
from gwclub_indexer.eval.groundtruth import generate_ground_truth, save_ground_truth
from gwclub_indexer.eval.report import build_eval_subparser
from gwclub_indexer.indexer import run_index
from gwclub_indexer.loader import load_documents
from gwclub_indexer.search import build_search_subparser
from gwclub_indexer.serve import build_serve_subparser

__all__ = ["main"]


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="gwclub",
        description="Indexing pipeline for gw-club-docs (chunking + embeddings).",
    )
    subparsers = parser.add_subparsers(dest="command", metavar="COMMAND")

    groundtruth = subparsers.add_parser(
        "groundtruth", help="Generate deterministic eval ground truth (no LLM)."
    )
    groundtruth.add_argument(
        "--config",
        default=None,
        help="Path to an optional config.toml (defaults to in-code defaults).",
    )
    groundtruth.add_argument(
        "--output",
        default=None,
        help="Output YAML path (defaults to config paths.groundtruth_file).",
    )
    groundtruth.set_defaults(func=_cmd_groundtruth)

    index_cmd = subparsers.add_parser(
        "index",
        help="Build FAISS+SQLite index for the configured corpus.",
    )
    index_cmd.add_argument(
        "--config",
        default=None,
        help="Path to an optional config.toml (defaults to in-code defaults).",
    )
    index_cmd.add_argument(
        "--strategy",
        choices=["fixed", "structure", "both"],
        default="both",
        help="Chunking strategy to index (default: both).",
    )
    index_cmd.set_defaults(func=_cmd_index)

    build_search_subparser(subparsers)
    build_eval_subparser(subparsers)
    build_serve_subparser(subparsers)

    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI entry point. Returns an exit code (0 on success)."""
    parser = _build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:  # argparse raised for --help / errors
        return int(exc.code or 0)

    if getattr(args, "command", None) is None:
        parser.print_help()
        return 0

    return args.func(args)


def _cmd_groundtruth(args: argparse.Namespace) -> int:
    """``gwclub groundtruth`` -- generate and save the ground-truth YAML."""
    cfg: Config = load_config(Path(args.config) if args.config is not None else None)
    docs = load_documents(cfg)
    items = generate_ground_truth(cfg, docs)

    output = (
        Path(args.output)
        if args.output is not None
        else Path(cfg.paths.groundtruth_file)
    )
    save_ground_truth(items, output)

    heading_count = sum(1 for item in items if item.origin == "heading")
    faq_count = sum(1 for item in items if item.origin == "faq")
    print(
        f"Ground truth: {len(items)} items "
        f"({heading_count} heading / {faq_count} faq) → {output}"
    )
    return 0


def _cmd_index(args: argparse.Namespace) -> int:
    """``gwclub index`` -- build the FAISS+SQLite index (per strategy)."""
    cfg: Config = load_config(Path(args.config) if args.config is not None else None)
    strategies = ["fixed", "structure"] if args.strategy == "both" else [args.strategy]
    for strategy in strategies:
        stats = run_index(cfg, strategy)
        print(
            f"index[{stats.strategy}]: {stats.n_docs} docs → "
            f"{stats.n_chunks} chunks, dim {stats.dim}, "
            f"{stats.elapsed_s:.1f}s → {stats.faiss_path}"
        )
    return 0
