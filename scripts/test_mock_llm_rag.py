#!/usr/bin/env python3
"""Тесты детекции RAG_MARKER в scripts/mock-llm.py (задача 8 плана
shturman-rag-mode; TDD — файл написан ДО реализации).

Паттерн тестов маркеров в репо (FactPromptsTest.kt / HistoryCompressorTest.kt)
проверяет, что промпт НЕСЁТ маркер; поведение стороны mock-LLM до сих пор
фиксировали только живыми curl-смоками (evidence task-5-mock-*.txt). Этот файл
закрывает ту же пару на стороне mock: импортирует mock-llm.py по пути
(дефис в имени файла → importlib) и проверяет _pick_reply напрямую, без сети.

Запуск: python3 scripts/test_mock_llm_rag.py -v
"""

import importlib.util
import os
import unittest

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
_MOCK_PATH = os.path.join(_SCRIPTS_DIR, "mock-llm.py")


def _load_mock_module():
    spec = importlib.util.spec_from_file_location("mock_llm_under_test", _MOCK_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


mock_llm = _load_mock_module()

# Канонический RAG-блок в формате buildRagBlock (задача 4): заголовок
# RAG_MARKER + инструкция + строки `[N] source: <файл> · раздел: <section>`
# + текст чанка после каждой строки.
RAG_BLOCK = (
    "РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ\n"
    "Отвечай по приведённым документам, указывай источники в виде [файл · раздел].\n"
    "[1] source: ru/FAQ-all.md · раздел: 1. Подготовка флешки\n"
    "Флешка должна быть отформатирована в exFAT; размер сектора — 512 байт.\n"
    "[2] source: ru/Гид.md · раздел: 2. Настройка\n"
    "Настройка выполняется через меню «Параметры»."
)

LONG_CHUNK = "Б" * 250


def _rag_messages(block=None):
    return [
        {"role": "system", "content": block if block is not None else RAG_BLOCK},
        {"role": "user", "content": "Как подготовить флешку?"},
    ]


class RagMarkerTest(unittest.TestCase):
    def test_marker_in_plain_mode_returns_deterministic_reply_with_sources(self):
        reply1 = mock_llm._pick_reply(_rag_messages(), "plain")
        self.assertTrue(reply1.startswith("По базе знаний: "), reply1)
        self.assertIn("ru/FAQ-all.md", reply1)
        self.assertIn("1. Подготовка флешки", reply1)
        self.assertIn("ru/FAQ-all.md (раздел 1. Подготовка флешки)", reply1)
        self.assertIn("Флешка должна быть отформатирована в exFAT", reply1)
        # T2 (rag-answers-minichat): mock больше НЕ печатает секцию
        # «Источники:» — единственный эмиттер блоков источников теперь
        # бэкенд Штурмана; mock остаётся ответчиком простым ответом.
        self.assertNotIn("Источники:", reply1)
        # Стабильность: повторный идентичный вызов → идентичный ответ
        # (без ротации, без случайности).
        reply2 = mock_llm._pick_reply(_rag_messages(), "plain")
        self.assertEqual(reply1, reply2)

    def test_no_code_fences_in_rag_reply(self):
        reply = mock_llm._pick_reply(_rag_messages(), "plain")
        self.assertNotIn("```", reply)

    def test_first_chunk_truncated_to_200_chars(self):
        block = (
            "РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ\n"
            "[1] source: ru/FAQ-all.md · раздел: 1. Подготовка флешки\n"
            + LONG_CHUNK
        )
        reply = mock_llm._pick_reply(_rag_messages(block), "plain")
        self.assertIn(LONG_CHUNK[:200], reply)
        self.assertNotIn(LONG_CHUNK, reply)  # чанк 250 символов целиком не цитируется

    def test_refuse_mode_takes_priority_over_rag_marker(self):
        messages = [
            {"role": "system", "content": (
                "НЕПРЕЛОЖНЫЕ ИНВАРИАНТЫ (нарушать запрещено)\n"
                "Глобальные:\n1. Только Kotlin"
            )},
            {"role": "system", "content": RAG_BLOCK},
            {"role": "user", "content": "Как подготовить флешку?"},
        ]
        reply = mock_llm._pick_reply(messages, "refuse")
        self.assertTrue(
            reply.startswith("Не могу выполнить: это противоречит инварианту"),
            reply,
        )
        self.assertNotIn("По базе знаний", reply)

    def test_no_marker_keeps_old_rotation(self):
        messages = [
            {"role": "system", "content": "Бортовой журнал готов."},
            {"role": "user", "content": "Продолжай"},
        ]
        first = mock_llm._pick_reply(messages, "plain")
        second = mock_llm._pick_reply(messages, "plain")
        self.assertIn(first, mock_llm.REPLIES)
        self.assertIn(second, mock_llm.REPLIES)
        self.assertNotEqual(first, second)  # ротация продолжает двигаться

    def test_rag_marker_without_source_lines_falls_back_to_rotation(self):
        block = "РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ\nПросто инструкция без строк источников."
        reply = mock_llm._pick_reply(_rag_messages(block), "plain")
        self.assertIn(reply, mock_llm.REPLIES)

    def test_facts_marker_beats_rag_marker(self):
        messages = [
            {"role": "system", "content": "Извлечение фактов\n" + RAG_BLOCK},
            {"role": "user", "content": "Новое сообщение пользователя: цель — тест"},
        ]
        reply = mock_llm._pick_reply(messages, "plain")
        self.assertTrue(reply.startswith('[{"key": "цель"'), reply)

    def test_summary_marker_beats_rag_marker(self):
        messages = [
            {"role": "system", "content": "Сводка ранее\n" + RAG_BLOCK},
            {"role": "user", "content": "Сожми историю"},
        ]
        self.assertEqual(
            mock_llm._pick_reply(messages, "plain"), mock_llm.SUMMARY_REPLY
        )


# System-инструкция QueryRewriter (задача 5 плана shturman-rag-stage2):
# несёт маркер mock-llm REWRITE_MARKER (строковое совпадение «переписываешь
# поисковый запрос»).
REWRITE_SYSTEM = (
    "Ты переписываешь поисковый запрос пользователя для полнотекстового "
    "поиска по документации. Верни ТОЛЬКО переписанный запрос одной "
    "строкой, без пояснений."
)

REWRITE_USER = "как отформатировать флешку"


def _rewrite_messages(extra_system=None):
    messages = [{"role": "system", "content": REWRITE_SYSTEM}]
    if extra_system is not None:
        messages.append({"role": "system", "content": extra_system})
    messages.append({"role": "user", "content": REWRITE_USER})
    return messages


class RewriteMarkerTest(unittest.TestCase):
    def test_rewrite_marker_returns_last_user_text_unchanged(self):
        # identity: ответ == текст последнего user-сообщения, без маркеров,
        # без изменений; повторный идентичный вызов → идентичный ответ.
        reply = mock_llm._pick_reply(_rewrite_messages(), "plain")
        self.assertEqual(reply, REWRITE_USER)
        self.assertEqual(mock_llm._pick_reply(_rewrite_messages(), "plain"), reply)

    def test_rewrite_marker_beats_rag_marker(self):
        # payload содержит И rewrite-инструкцию, И RAG_MARKER → rewrite-путь
        # побеждает (identity, а не _rag_reply).
        messages = _rewrite_messages(extra_system=RAG_BLOCK)
        reply = mock_llm._pick_reply(messages, "plain")
        self.assertEqual(reply, REWRITE_USER)
        self.assertNotIn("По базе знаний", reply)

    def test_no_rewrite_marker_keeps_pick_reply_behavior(self):
        # без rewrite-маркера существующее _pick_reply не меняется:
        # RAG-блок по-прежнему идёт в _rag_reply.
        reply = mock_llm._pick_reply(_rag_messages(), "plain")
        self.assertTrue(reply.startswith("По базе знаний: "), reply)
        self.assertIn("ru/FAQ-all.md", reply)


if __name__ == "__main__":
    unittest.main(verbosity=2)
