#!/usr/bin/env python3
"""Тесты чистых функций харнесса сравнения RAG/no-RAG (задача 10 плана
shturman-rag-mode; TDD — файл написан ДО реализации scripts/rag_eval.py).

Паттерн — как в scripts/test_mock_llm_rag.py: только stdlib unittest, модуль
импортируется по пути через importlib.util.spec_from_file_location (запуск
`python3 scripts/test_rag_eval.py -v`). Сети нет: тестируются parse_questions,
check_substrings, check_sources, judge_display и format_report.

PyYAML в системном python3 нет — parse_questions понимает ФИКСИРОВАННЫЙ формат
eval/rag-questions.yaml (список маппингов с flow-списками и `#`-комментариями).
"""

import importlib.util
import os
import unittest

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.dirname(_SCRIPTS_DIR)
_EVAL_PATH = os.path.join(_REPO_ROOT, "eval", "rag-questions.yaml")


def _load_module():
    spec = importlib.util.spec_from_file_location(
        "rag_eval_under_test", os.path.join(_SCRIPTS_DIR, "rag_eval.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rag_eval = _load_module()

# Инлайн-фикстура: комментарии (полнострочные), пустые строки, инлайн flow-списки,
# значения с запятыми внутри кавычек отсутствуют (фиксированный формат файла).
FIXTURE = """\
# Контрольные вопросы для сравнения RAG / no-RAG
- question: "Как подготовить флешку?"
  # ожидание: программа и файловая система
  expectedSubstrings: ["Rufus", "NTFS"]
  expectedSources: ["ru/FAQ-all.md"]

- question: "Как активировать прошивку?"
  expectedSubstrings: ["GW-CLUB ПРЕМИУМ", "Обновить"]
  # источник один
  expectedSources: ["ru/FAQ-all.md"]
"""


class ParseQuestionsTest(unittest.TestCase):
    def test_inline_fixture_parses_comments_and_flow_lists(self):
        items = rag_eval.parse_questions(FIXTURE)
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0]["question"], "Как подготовить флешку?")
        self.assertEqual(items[0]["expectedSubstrings"], ["Rufus", "NTFS"])
        self.assertEqual(items[0]["expectedSources"], ["ru/FAQ-all.md"])
        # строка-комментарий между ключами не рвёт маппинг
        self.assertEqual(
            items[1]["expectedSubstrings"], ["GW-CLUB ПРЕМИУМ", "Обновить"]
        )
        self.assertEqual(items[1]["expectedSources"], ["ru/FAQ-all.md"])

    def test_real_questions_file_has_10_items_with_3_keys(self):
        items = rag_eval.parse_questions(_EVAL_PATH)
        self.assertEqual(len(items), 10)
        for item in items:
            self.assertEqual(
                sorted(item.keys()),
                ["expectedSources", "expectedSubstrings", "question"],
            )
            self.assertTrue(item["question"].strip())
            self.assertTrue(item["expectedSubstrings"])
            self.assertTrue(item["expectedSources"])

    def test_first_real_question_content(self):
        items = rag_eval.parse_questions(_EVAL_PATH)
        self.assertEqual(
            items[0]["question"],
            "Как правильно отформатировать флешку для установки прошивки?",
        )
        self.assertEqual(items[0]["expectedSubstrings"], ["Rufus", "NTFS"])
        self.assertEqual(items[-1]["expectedSources"], ["ru/wireless-androidAuto.md"])

    def test_missing_file_raises(self):
        with self.assertRaises(ValueError):
            rag_eval.parse_questions(
                os.path.join(_SCRIPTS_DIR, "нет-такого-файла.yaml")
            )

    def test_item_without_required_key_raises(self):
        with self.assertRaises(ValueError):
            rag_eval.parse_questions('- question: "Без ожиданий"\n')


class CheckSubstringsTest(unittest.TestCase):
    def test_case_insensitive_match(self):
        passed, missing = rag_eval.check_substrings(
            "Используйте RUFUS и файловую систему ntfs.", ["Rufus", "NTFS"]
        )
        self.assertTrue(passed)
        self.assertEqual(missing, [])

    def test_missing_reported_verbatim(self):
        passed, missing = rag_eval.check_substrings("Ответ про флешку.", ["Rufus"])
        self.assertFalse(passed)
        self.assertEqual(missing, ["Rufus"])

    def test_partial_match(self):
        passed, missing = rag_eval.check_substrings(
            "Rufus подойдёт.", ["Rufus", "NTFS"]
        )
        self.assertFalse(passed)
        self.assertEqual(missing, ["NTFS"])


class CheckSourcesTest(unittest.TestCase):
    RETRIEVED = [
        {"source": "ru/FAQ-all.md", "section": "1. Подготовка"},
        {"source": "ru/Гид.md", "section": "Настройка"},
    ]

    def test_mention_in_reply_passes_without_rag(self):
        passed, missing = rag_eval.check_sources(
            "См. ru/FAQ-all.md.", ["ru/FAQ-all.md"], [], rag_mode=False
        )
        self.assertTrue(passed)
        self.assertEqual(missing, [])

    def test_retrieved_sources_pass_only_in_rag_mode(self):
        # источник НЕ упомянут в ответе, но пришёл в retrievedSources
        passed, missing = rag_eval.check_sources(
            "Ответ без имён файлов.",
            ["ru/FAQ-all.md"],
            self.RETRIEVED,
            rag_mode=True,
        )
        self.assertTrue(passed)
        self.assertEqual(missing, [])

    def test_retrieved_sources_ignored_without_rag_mode(self):
        # тот же retrievedSources при rag:false НЕ считается — нужен текст ответа
        passed, missing = rag_eval.check_sources(
            "Ответ без имён файлов.",
            ["ru/FAQ-all.md"],
            self.RETRIEVED,
            rag_mode=False,
        )
        self.assertFalse(passed)
        self.assertEqual(missing, ["ru/FAQ-all.md"])

    def test_failure_when_nowhere(self):
        passed, missing = rag_eval.check_sources(
            "Ответ про другое.", ["ru/FAQ_Tank300.md"], self.RETRIEVED, rag_mode=True
        )
        self.assertFalse(passed)
        self.assertEqual(missing, ["ru/FAQ_Tank300.md"])

    def test_partial_sources(self):
        passed, missing = rag_eval.check_sources(
            "См. ru/FAQ-all.md.",
            ["ru/FAQ-all.md", "ru/wireless-androidAuto.md"],
            self.RETRIEVED,
            rag_mode=True,
        )
        self.assertFalse(passed)
        self.assertEqual(missing, ["ru/wireless-androidAuto.md"])

    def test_basename_mention_in_reply_passes(self):
        passed, missing = rag_eval.check_sources(
            "Подробности — в FAQ-all.md.", ["ru/FAQ-all.md"], [], rag_mode=False
        )
        self.assertTrue(passed)
        self.assertEqual(missing, [])


class JudgeDisplayTest(unittest.TestCase):
    def test_none_renders_dash(self):
        self.assertEqual(rag_eval.judge_display(None), "—")

    def test_overall_score_rendered(self):
        self.assertEqual(rag_eval.judge_display({"overall": 8}), "8")

    def test_unexpected_shape_renders_dash(self):
        self.assertEqual(rag_eval.judge_display({"foo": "bar"}), "—")


def _row(question, mode, substr_ok, sources_ok, judge=None, pt=100, ct=50, ms=1234,
         retrieved_empty=False):
    return {
        "question": question,
        "mode": mode,
        "substr_ok": substr_ok,
        "substr_total": 2,
        "substr_passed": substr_ok == 2,
        "sources_passed": sources_ok,
        "judge": judge,
        "prompt_tokens": pt,
        "completion_tokens": ct,
        "elapsed_ms": ms,
        "retrieved_empty": retrieved_empty,
    }


def _twenty_rows(rag_full=True):
    rows = []
    for i in range(1, 11):
        q = "Вопрос %d про прошивку?" % i
        rows.append(_row(q, "no-rag", 0, False))
        rows.append(
            _row(q, "rag", 2, True, pt=200, ct=80, ms=321)
        ) if rag_full else rows.append(_row(q, "rag", 0, False))
    return rows


class FormatReportTest(unittest.TestCase):
    def test_twenty_rows_with_summary_and_dash_judge(self):
        report = rag_eval.format_report(_twenty_rows(rag_full=True), "mock")
        data_rows = [
            ln
            for ln in report.splitlines()
            if ln.startswith("| ") and ("rag:вкл" in ln or "rag:выкл" in ln)
        ]
        self.assertEqual(len(data_rows), 20)
        self.assertIn("Модель: mock", report)
        self.assertIn(
            "RAG лучше no-RAG по числу полностью пройденных вопросов: 10/10 vs 0/10",
            report,
        )
        self.assertIn("—", report)  # judge == null → прочерк
        self.assertIn("✓ 2/2", report)
        self.assertIn("✗ 0/2", report)
        self.assertIn("rag:вкл", report)
        self.assertIn("rag:выкл", report)

    def test_summary_equal(self):
        rows = [
            _row("Вопрос?", "no-rag", 2, True),
            _row("Вопрос?", "rag", 2, True),
        ]
        report = rag_eval.format_report(rows, "mock")
        self.assertIn(
            "RAG равно no-RAG по числу полностью пройденных вопросов: 1/1 vs 1/1",
            report,
        )

    def test_summary_worse(self):
        rows = [
            _row("Вопрос один?", "no-rag", 2, True),
            _row("Вопрос один?", "rag", 0, False),
        ]
        report = rag_eval.format_report(rows, "mock")
        self.assertIn(
            "RAG хуже no-RAG по числу полностью пройденных вопросов: 0/1 vs 1/1",
            report,
        )

    def test_question_truncated(self):
        long_q = "Очень длинный вопрос " * 10
        report = rag_eval.format_report([_row(long_q, "no-rag", 0, False)], "mock")
        self.assertIn("…", report)
        self.assertNotIn(long_q, report)

    def test_missing_tokens_render_dash(self):
        row = _row("Вопрос?", "no-rag", 0, False, pt=None, ct=None, ms=None)
        report = rag_eval.format_report([row], "mock")
        self.assertIn("| — |", report)


class BuildChatPayloadTest(unittest.TestCase):
    def test_v1_payload_has_no_stage2_keys(self):
        payload = rag_eval.build_chat_payload("Вопрос?", True, "default", "mock", True)
        self.assertEqual(
            payload,
            {
                "message": "Вопрос?",
                "ragEnabled": True,
                "provider": "default",
                "model": "mock",
                "judgeEnabled": True,
            },
        )
        # T6-инвариант отсутствия override-ключей в v1-теле
        self.assertNotIn("ragThreshold", payload)
        self.assertNotIn("ragRerank", payload)
        self.assertNotIn("ragRewrite", payload)

    def test_filtered_override_only_threshold(self):
        payload = rag_eval.build_chat_payload(
            "Вопрос?", True, "default", "mock", False, {"ragThreshold": 0.88}
        )
        self.assertEqual(payload["ragThreshold"], 0.88)
        self.assertNotIn("ragRerank", payload)
        self.assertNotIn("ragRewrite", payload)

    def test_none_override_values_are_skipped(self):
        payload = rag_eval.build_chat_payload(
            "Вопрос?", False, "default", "mock", False,
            {"ragRerank": None, "ragThreshold": 0.5},
        )
        self.assertNotIn("ragRerank", payload)
        self.assertIn("ragThreshold", payload)

    def test_stage2_raw_only_rag_enabled(self):
        overrides = rag_eval.stage2_overrides("raw", 0.88)
        payload = rag_eval.build_chat_payload(
            "Вопрос?", True, "default", "mock", False, overrides
        )
        self.assertEqual(payload["ragEnabled"], True)
        self.assertNotIn("ragThreshold", payload)
        self.assertNotIn("ragRerank", payload)
        self.assertNotIn("ragRewrite", payload)

    def test_rerank_rewrite_combines_overrides(self):
        payload = rag_eval.build_chat_payload(
            "Вопрос?", True, "default", "mock", False,
            rag_eval.stage2_overrides("rerank+rewrite", 0.88),
        )
        self.assertTrue(payload["ragRerank"])
        self.assertTrue(payload["ragRewrite"])
        self.assertNotIn("ragThreshold", payload)


class Stage2ModesTest(unittest.TestCase):
    def test_overrides_per_base_mode(self):
        self.assertEqual(rag_eval.stage2_overrides("raw", 0.88), {})
        self.assertEqual(
            rag_eval.stage2_overrides("filtered", 0.88), {"ragThreshold": 0.88}
        )
        self.assertEqual(rag_eval.stage2_overrides("rerank", 0.88), {"ragRerank": True})

    def test_overrides_rewrite_variants(self):
        self.assertEqual(
            rag_eval.stage2_overrides("raw+rewrite", 0.88), {"ragRewrite": True}
        )
        self.assertEqual(
            rag_eval.stage2_overrides("filtered+rewrite", 0.88),
            {"ragThreshold": 0.88, "ragRewrite": True},
        )

    def test_unknown_mode_raises(self):
        with self.assertRaises(ValueError):
            rag_eval.stage2_overrides("bogus", 0.88)
        with self.assertRaises(ValueError):
            rag_eval.score_scale("bogus")

    def test_scale_tags_per_mode(self):
        self.assertEqual(rag_eval.score_scale("raw"), "cosine")
        self.assertEqual(rag_eval.score_scale("filtered"), "cosine")
        self.assertEqual(rag_eval.score_scale("rerank"), "rerank-sigmoid")
        self.assertEqual(rag_eval.score_scale("rerank+rewrite"), "rerank-sigmoid")
        self.assertEqual(rag_eval.score_scale("filtered+rewrite"), "cosine")

    def test_matrix_without_rewrite(self):
        self.assertEqual(
            rag_eval.build_mode_matrix(["raw", "filtered", "rerank"], False),
            ["raw", "filtered", "rerank"],
        )

    def test_matrix_with_rewrite_adds_same_modes(self):
        self.assertEqual(
            rag_eval.build_mode_matrix(["raw", "filtered", "rerank"], True),
            [
                "raw",
                "filtered",
                "rerank",
                "raw+rewrite",
                "filtered+rewrite",
                "rerank+rewrite",
            ],
        )


class ParseArgsStage2Test(unittest.TestCase):
    def test_no_new_flags_keeps_v1(self):
        args = rag_eval.parse_args(["--model", "mock"])
        self.assertIsNone(args.modes)
        self.assertFalse(args.rewrite)
        self.assertEqual(args.threshold, rag_eval.DEFAULT_FILTERED_THRESHOLD)

    def test_modes_and_rewrite_parsed(self):
        args = rag_eval.parse_args(
            ["--model", "mock", "--modes", "raw,filtered,rerank", "--rewrite"]
        )
        self.assertEqual(args.modes, ["raw", "filtered", "rerank"])
        self.assertTrue(args.rewrite)

    def test_unknown_mode_name_rejected(self):
        with self.assertRaises(SystemExit):
            rag_eval.parse_args(["--model", "mock", "--modes", "raw,bogus"])

    def test_threshold_out_of_range_rejected(self):
        with self.assertRaises(SystemExit):
            rag_eval.parse_args(["--model", "mock", "--threshold", "1.5"])
        with self.assertRaises(SystemExit):
            rag_eval.parse_args(["--model", "mock", "--threshold", "abc"])


def _thirty_rows():
    rows = []
    for i in range(1, 11):
        q = "Вопрос %d про прошивку?" % i
        rows.append(_row(q, "raw", 2, True, ms=100))
        rows.append(_row(q, "filtered", 2, i <= 8, ms=200, retrieved_empty=i > 8))
        rows.append(_row(q, "rerank", 2, True, ms=300))
    return rows


class ModeSummariesTest(unittest.TestCase):
    def test_aggregates_sources_median_and_empty(self):
        summaries = rag_eval.mode_summaries(_thirty_rows())
        by_mode = {s["mode"]: s for s in summaries}
        self.assertEqual(by_mode["raw"]["sources_passed"], 10)
        self.assertEqual(by_mode["filtered"]["sources_passed"], 8)
        self.assertEqual(by_mode["filtered"]["total"], 10)
        self.assertEqual(by_mode["filtered"]["empty_replies"], 2)
        self.assertEqual(by_mode["rerank"]["median_ms"], 300)
        self.assertEqual(by_mode["raw"]["median_ms"], 100)

    def test_median_skips_failures_and_none(self):
        rows = [
            _row("В1?", "filtered", 0, False, ms=None, retrieved_empty=True),
            _row("В2?", "filtered", 2, True, ms=500),
        ]
        (summary,) = rag_eval.mode_summaries(rows)
        self.assertEqual(summary["median_ms"], 500)
        self.assertEqual(summary["empty_replies"], 1)

    def test_summary_lines_in_stage2_report(self):
        report = rag_eval.format_report(
            _thirty_rows(), "mock", stage2=True, threshold=0.88
        )
        self.assertIn("- raw: источники 100% (10/10), медиана 100 мс, пустых ответов 0", report)
        self.assertIn("- filtered: источники 80% (8/10), медиана 200 мс, пустых ответов 2", report)
        self.assertIn("- rerank: источники 100% (10/10), медиана 300 мс, пустых ответов 0", report)

    def test_scale_tag_column_on_every_row(self):
        rows = [
            _row("В1?", "raw", 2, True),
            _row("В1?", "filtered", 2, True),
            _row("В1?", "rerank", 2, True),
        ]
        report = rag_eval.format_report(rows, "mock", stage2=True, threshold=0.88)
        self.assertIn("| В1? | raw | score: cosine |", report)
        self.assertIn("| В1? | filtered | score: cosine |", report)
        self.assertIn("| В1? | rerank | score: rerank-sigmoid |", report)
        self.assertIn("| Вопрос | Режим | Шкала |", report)
        self.assertNotIn("rag:вкл", report)

    def test_threshold_note_in_report_when_filtered(self):
        report = rag_eval.format_report(
            [_row("В1?", "filtered", 2, True)], "mock", stage2=True, threshold=0.88
        )
        self.assertIn("Порог filtered: 0.88", report)
        self.assertIn("хвост-кат", report)

    def test_v1_format_untouched_by_stage2_params(self):
        rows = _twenty_rows(rag_full=True)
        self.assertEqual(
            rag_eval.format_report(rows, "mock"),
            rag_eval.format_report(rows, "mock", stage2=False, threshold=0.88),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
