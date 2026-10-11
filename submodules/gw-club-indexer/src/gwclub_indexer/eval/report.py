"""Strategy-comparison report generator (Task 15).

Turns the raw ``run_eval`` dict into a human-readable ``comparison.md`` whose
section order is normative: the winner rule is written STRICTLY BEFORE the
metrics table, so the reader is told how to judge before seeing the numbers
(the Metis anti-circularity measure).

Public API::

    from gwclub_indexer.eval.report import generate_report, run_eval_cli

    generate_report(results, cfg, Path("reports/comparison.md"))
    run_eval_cli(args)  # wired into `gwclub eval` by Task 16 (do NOT edit cli.py)
"""

from __future__ import annotations

import argparse
import sys
from datetime import UTC, datetime
from pathlib import Path

from gwclub_indexer.config import Config, load_config
from gwclub_indexer.eval.engine import run_eval

__all__ = [
    "DECISION_RULE_TEXT",
    "build_eval_subparser",
    "generate_report",
    "run_eval_cli",
]

#: The canonical winner-rule text; written verbatim into the report.
DECISION_RULE_TEXT = (
    "structure выигрывает, если Recall@5(structure) − Recall@5(fixed) ≥ 5 п.п.; "
    "при разнице < 5 п.п. сравнивается MRR@10; при равенстве MRR — паритет, "
    "дефолт search = structure (богаче метаданные)"
)

#: Any strategy's Recall@5 gap of at least this many percentage points is decisive.
PP_THRESHOLD: float = 5.0


def _fmt(v: float) -> str:
    """Format a 0..1 fraction as a percentage (e.g. 0.87 -> '87.4%')."""
    return f"{v * 100:.1f}%"


def _fmt_val(v: float) -> str:
    """Format an absolute value (percentile/token count) compactly."""
    return f"{v:.0f}"


def _decide_winner(results: dict) -> dict:
    """Apply the winner rule mechanically to ``results``.

    Returns ``{"winner": ..., "default_search": ...}`` where ``winner`` is
    ``"structure"``, ``"fixed"``, or ``"паритет"``, and ``default_search`` is
    the strategy the search layer should use by default.
    """
    fixed = results["strategies"]["fixed"]
    structure = results["strategies"]["structure"]
    diff = structure["recall_at_5"] - fixed["recall_at_5"]
    if diff >= PP_THRESHOLD / 100.0:
        return {"winner": "structure", "default_search": "structure"}
    if diff <= -PP_THRESHOLD / 100.0:
        return {"winner": "fixed", "default_search": "fixed"}
    # |diff| < 5 п.п. -> compare MRR@10.
    mrr_s = structure["mrr_at_10"]
    mrr_f = fixed["mrr_at_10"]
    if mrr_s > mrr_f:
        return {"winner": "structure", "default_search": "structure"}
    if mrr_f > mrr_s:
        return {"winner": "fixed", "default_search": "fixed"}
    return {"winner": "паритет", "default_search": "structure"}


def _params_section(results: dict) -> str:
    p = results["params"]
    fixed_cfg = results["strategies"]["fixed"]["params"]
    struct_cfg = results["strategies"]["structure"]["params"]
    return (
        f"Модель: `{p['model']}` · provider: `{p['provider']}` · dim: {p['embedding_dim']}  \n"
        f"Корпус: {p['n_docs']} документов · эталонов: {p['n_items']} "
        f"(heading: {p['origins']['heading']}, faq: {p['origins']['faq']})  \n"
        f"fixed: max_tokens={fixed_cfg['max_tokens']}, overlap={fixed_cfg['overlap_tokens']} "
        f"· structure: max_tokens={struct_cfg['max_tokens']}, overlap={struct_cfg['overlap_tokens']}  \n"
        f"Дата: {datetime.now(UTC).strftime('%Y-%m-%d %H:%M')}  "
    )


def _methodology_section() -> str:
    return (
        "Эталоны построены автоматически (задача evaluator, без LLM) и лежат в "
        "`eval/groundtruth.yaml`; их можно дополнять вручную. Типы эталонов: "
        "`heading` (заголовок документа, переиспользованный как запрос) и `faq` "
        "(человеческий вопрос из FAQ-файлов).\n\n"
        "Уровень релевантности — **(source, section)**: эталон привязан к файлу и "
        "к заголовочному трейлу, НЕ к конкретному идентификатору чанка. Для каждой "
        "стратегии эталон маппится на её чанки через 4 правила: чанк релевантен, "
        "если `source` совпадает И (секция чанка равна/родитель/потомок секции эталона "
        "ИЛИ заголовок эталона встречается в содержимом чанка). Это снимает смещение "
        "сравнения, ведь fixed-чанки не несут секций.\n\n"
        "Top-k = 10 (поиск по косинусной близости в FAISS). Метрики: "
        "**Recall@5** = среднее по эталонам от |top5 ∩ релевантные| / max(1, |релевантные|); "
        "**MRR@10** = среднее от 1/ранг первого релевантного в top10 (0, если нет); "
        "**Recall@10** информационно. Пустой релевантный набор даёт вклад 0 и учитывается "
        "в знаменателе среднего."
    )


def _results_table(results: dict) -> str:
    fixed = results["strategies"]["fixed"]
    structure = results["strategies"]["structure"]
    rows: list[tuple[str, str, str]] = [
        ("Recall@5", _fmt(fixed["recall_at_5"]), _fmt(structure["recall_at_5"])),
        ("MRR@10", _fmt(fixed["mrr_at_10"]), _fmt(structure["mrr_at_10"])),
        ("Recall@10", _fmt(fixed["recall_at_10"]), _fmt(structure["recall_at_10"])),
        (
            "coherence",
            "n/a" if fixed["coherence"] is None else f"{fixed['coherence']:.3f}",
            "n/a"
            if structure["coherence"] is None
            else f"{structure['coherence']:.3f}",
        ),
        (
            "p50 токенов",
            _fmt_val(fixed["length"]["p50"]),
            _fmt_val(structure["length"]["p50"]),
        ),
        (
            "p90 токенов",
            _fmt_val(fixed["length"]["p90"]),
            _fmt_val(structure["length"]["p90"]),
        ),
        (
            "max токенов",
            _fmt_val(fixed["length"]["max"]),
            _fmt_val(structure["length"]["max"]),
        ),
        (
            "% <30 токенов",
            _fmt(fixed["length"]["pct_below_30"]),
            _fmt(structure["length"]["pct_below_30"]),
        ),
        (
            "% >max",
            _fmt(fixed["length"]["pct_over_max_tokens"]),
            _fmt(structure["length"]["pct_over_max_tokens"]),
        ),
        ("чанков", str(fixed["n_chunks"]), str(structure["n_chunks"])),
    ]
    lines = ["| Метрика | fixed | structure |", "|---|---|---|"]
    lines.extend(f"| {name} | {f} | {s} |" for name, f, s in rows)
    return "\n".join(lines)


def _verdict_section(results: dict) -> str:
    verdict = _decide_winner(results)
    winner = verdict["winner"]
    default_search = verdict["default_search"]
    return (
        f"Применяем правило к результатам. **Победитель: {winner}**. Дефолтная "
        f"стратегия для `search`: **{default_search}**."
    )


def _caveats_section() -> str:
    return (
        "1. **Heading-as-query тривиально благоприятствует structure** — такие запросы "
        "являются дословными заголовками, а структурированные чанки несут заголовок, "
        "поэтому легко попадают в топ. Это не обязательно отражает реальный поиск.\n"
        "2. **FAQ-вопросы — честный тест**: это человеческие вопросы, сформулированные "
        "не как заголовки; они не дают structure преимущества по форме и лучше "
        "характеризуют качество поиска.\n"
        "3. **Coherence — прокси-метрика**: она измеряет внутреннюю согласованность "
        "чанков (интра-похожесть предложений vs соседний чанк), а не качество "
        "ответа; её следует читать как ориентир, а не как истину."
    )


def generate_report(results: dict, cfg: Config, out_path: Path) -> Path:
    """Write ``comparison.md`` to ``out_path`` and return it.

    Section order is fixed (methodology -> winner rule -> metrics table), so the
    winner rule always precedes the table.
    """
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fixed = results["strategies"]["fixed"]
    structure = results["strategies"]["structure"]
    stats = (
        f"Эталонов: {results['params']['n_items']} · fixed: "
        f"{fixed['n_chunks']} чанков · structure: {structure['n_chunks']} чанков"
    )
    content = "\n\n".join(
        [
            (
                f"# Сравнение стратегий chunking\n\n{stats}\n\n"
                f"### Параметры\n\n{_params_section(results)}"
            ),
            f"## Методика\n\n{_methodology_section()}",
            f"## Правило победителя\n\n{DECISION_RULE_TEXT}",
            f"## Результаты\n\n{_results_table(results)}",
            f"## Вердикт\n\n{_verdict_section(results)}",
            f"## Оговорки\n\n{_caveats_section()}",
        ]
    )
    out_path.write_text(content, encoding="utf-8")
    return out_path


# --------------------------------------------------------------------------- #
# CLI wiring (owned here; Task 16 pulls it into cli.py)
# --------------------------------------------------------------------------- #
def build_eval_subparser(subparsers) -> None:
    """Register the ``eval`` subcommand on ``subparsers`` (argparse)."""
    sub = subparsers.add_parser(
        "eval", help="Run the strategy comparison and write reports/comparison.md."
    )
    sub.add_argument(
        "--config",
        default=None,
        help="Path to an optional config.toml (defaults to in-code defaults).",
    )
    sub.set_defaults(func=run_eval_cli)


def run_eval_cli(args: argparse.Namespace) -> int:
    """``gwclub eval`` — run the engine, write the report, print one summary line.

    Raises nothing on the expected error path: a missing index artifact is
    reported to stderr and mapped to exit code 1 (no traceback).
    """
    cfg: Config = load_config(
        Path(args.config) if getattr(args, "config", None) else None
    )
    try:
        results = run_eval(cfg)
    except FileNotFoundError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    out_path = generate_report(
        results, cfg, Path(cfg.paths.reports_dir) / "comparison.md"
    )
    fixed = results["strategies"]["fixed"]
    structure = results["strategies"]["structure"]
    n_items = results["params"]["n_items"]
    print(
        f"Eval: {n_items} items, Recall@5 fixed={fixed['recall_at_5']:.2f} "
        f"structure={structure['recall_at_5']:.2f} → {out_path}"
    )
    return 0
