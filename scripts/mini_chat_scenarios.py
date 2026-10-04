#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Автоматический прогон QA-сценариев мини-чата (T6 плана rag-answers-minichat).

Скрипт-драйвер: запускает scripts/mini_chat.py КАК ЕСТЬ субпроцессом
(сегментами, на общем --session), затем валидирует файл сессии и печатает
потоходную таблицу + итоговый вердикт PASS/FAIL. Сам mini_chat.py не
модифицируется и не импортируется — только CLI-контракт.

Сценарии:
  1. «Прошивка TANK 300»  — 10 ходов: цель-ack + 7 LLM-ходов + 1 «не знаю»
     (сегмент с --force-empty).
  2. «Сравнение режимов» — 12 ходов: цель-ack + 10 LLM-ходов + 1 «не знаю».

Проверяемые инварианты (на финальном JSON сессии):
  * task_state.goal не меняется после всех сегментов;
  * len(constraints) >= 2; len(clarifications) >= норма; каждое уточнение <= 200;
  * каждый LLM-ход содержит «Источники:» ровно один раз и >= 1 строку «Цитаты:»;
  * ход с --force-empty: ответ с «Не знаю», без блоков, без записи в sources_log;
  * len(sources_log) == число LLM-ходов.

Только стандартная библиотека. Третьи зависимости запрещены.
Сессии НИКОГДА не удаляются автоматически (--keep-sessions — по умолчанию).
"""

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time

# ---------------------------------------------------------------------------
# Константы (зеркалят контракт scripts/mini_chat.py — значения НЕ меняем)
# ---------------------------------------------------------------------------

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MINI_CHAT = os.path.join(REPO_ROOT, "scripts", "mini_chat.py")
MOCK_LLM = os.path.join(REPO_ROOT, "scripts", "mock-llm.py")
DEFAULT_SESSION_DIR = os.path.join(REPO_ROOT, "scripts", "minichat-sessions")

MOCK_HOST = "127.0.0.1"
MOCK_PORT = 18080
INDEXER_HOST = "127.0.0.1"
INDEXER_PORT = 18701
MOCK_LOG = "/tmp/mock-llm.log"

GOAL_ACK_PREFIX = "Принято. Цель задачи зафиксирована:"
# Санкционированный «не знаю» = ТОЛЬКО фиксированный ответ мини-чата
# (mini_chat.IDK_REPLY). Префикс «Не знаю» без продолжения ловил бы честные
# ответы реальной модели вида «Не знаю. В документах нет …» (с блоками
# источников) — на боевом LLM это ломало классификацию ходов.
IDK_PREFIX = "Не знаю по этому вопросу"
CLARIFICATION_LIMIT = 200  # как в mini_chat.py

QUOTE_LINE_RE = re.compile(r"^\[\d+\]\s*«", re.MULTILINE)
SEGMENT_TIMEOUT = 240  # сек на один сегмент (щедро: RAG+LLM на каждый ход)


# ---------------------------------------------------------------------------
# Сценарии
# ---------------------------------------------------------------------------

def scenario_1():
    """«Прошивка TANK 300»: 10 ходов, 3 сегмента."""
    return {
        "name": "sc1",
        "title": "Прошивка TANK 300",
        "goal": "настроить прошивку TANK 300",
        "min_clarifications": 6,
        # В списке сообщений 5+1+3 = 9 ходов: заголовок «10 ходов» ТЗ — ошибка
        # подсчёта (7 LLM + 1 goal-ack + 1 «не знаю» = 9, инвариант
        # len(sources_log) == 7 это подтверждает).
        "expected_turns": 9,
        "expected_llm_turns": 7,
        "segments": [
            {"label": "seg1 (обычный запуск)", "force_empty": False, "messages": [
                "цель: настроить прошивку TANK 300",
                "как отформатировать флешку",
                "только официальные инструкции используй",
                "как сбросить устройство к заводским настройкам",
                "не используй термины на английском",
            ]},
            {"label": "seg2 (--force-empty)", "force_empty": True, "messages": [
                "как включить режим телепортации",
            ]},
            {"label": "seg3 (обычный запуск)", "force_empty": False, "messages": [
                "ладно, какие индикаторы горят при зарядке",
                "кратко опиши порядок прошивки",
                "в стиле инструкции для новичка — с чего начать",
            ]},
        ],
    }


def scenario_2():
    """«Сравнение режимов»: 12 ходов, 3 сегмента."""
    return {
        "name": "sc2",
        "title": "Сравнение режимов",
        "goal": "сравнить режимы работы навигатора",
        "min_clarifications": 8,
        "expected_turns": 12,
        "expected_llm_turns": 10,
        "segments": [
            {"label": "seg1 (обычный запуск)", "force_empty": False, "messages": [
                "цель: сравнить режимы работы навигатора",
                "какие режимы есть у навигатора",
                "а теперь подробнее по режиму трекинга",
                "обязательно упомяни энергопотребление режимов",
                "какой режим выбрать для дальнего похода",
            ]},
            {"label": "seg2 (--force-empty)", "force_empty": True, "messages": [
                "как выглядит синяя кнопка на корпусе",
            ]},
            {"label": "seg3 (обычный запуск)", "force_empty": False, "messages": [
                "продолжим: чем режим трекинга отличается от маршрута",
                "используй сравнительную таблицу в ответе",
                "как переключаться между режимами",
                "подробнее про сохранение трека",
                "какие ограничения у демо-режима",
                "вернёмся к цели — подведи итог сравнения режимов",
            ]},
        ],
    }


# Ожидаемый тип каждого хода выводится из сценария:
#   первое сообщение сегмента 1 (со «цель: ») → goal-ack,
#   сообщение в сегменте --force-empty       → idk,
#   остальные                                → llm.
def expected_turn_types(scenario):
    kinds = []
    for segment in scenario["segments"]:
        for position, message in enumerate(segment["messages"]):
            if segment["force_empty"]:
                kinds.append("idk")
            elif position == 0 and message.lower().lstrip().startswith("цель:") and not kinds:
                kinds.append("goal-ack")
            else:
                kinds.append("llm")
    return kinds


# ---------------------------------------------------------------------------
# Preflight: живость мока/индексатора (автостарт мока при необходимости)
# ---------------------------------------------------------------------------

def port_open(host, port, timeout=1.5):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def ensure_mock_alive(report):
    """Мок должен слушать 18080; если нет — стартуем как дочерний процесс."""
    if port_open(MOCK_HOST, MOCK_PORT):
        report.append("preflight: мок-LLM 127.0.0.1:18080 — жив")
        return True
    report.append("preflight: мок-LLM не отвечает, запускаю %s (лог %s)" % (MOCK_LLM, MOCK_LOG))
    try:
        os.makedirs(os.path.dirname(MOCK_LOG), exist_ok=True)
        handle = open(MOCK_LOG, "a")
        subprocess.Popen(
            ["python3", MOCK_LLM],
            stdout=handle, stderr=handle, start_new_session=True,
        )
    except OSError as error:
        report.append("preflight: НЕ удалось запустить мок-LLM (%s)" % error)
        return False
    for _ in range(20):  # до 10 секунд
        time.sleep(0.5)
        if port_open(MOCK_HOST, MOCK_PORT):
            report.append("preflight: мок-LLM поднялся")
            return True
    report.append("preflight: мок-LLM не поднялся за 10 секунд")
    return False


def preflight(report, use_mock):
    ok = True
    if use_mock:
        if not ensure_mock_alive(report):
            ok = False
    if port_open(INDEXER_HOST, INDEXER_PORT):
        report.append("preflight: RAG-индексатор 127.0.0.1:%d — жив" % INDEXER_PORT)
    else:
        # Не фатально для запуска (fail-open в mini_chat), но ходы останутся
        # без блоков и сценарии упадут — честно предупреждаем заранее.
        report.append("preflight: ВНИМАНИЕ — RAG-индексатор 127.0.0.1:%d недоступен; "
                      "LLM-ходы будут без блоков «Источники:»" % INDEXER_PORT)
    return ok


# ---------------------------------------------------------------------------
# Запуск сегментов
# ---------------------------------------------------------------------------

def build_segment_command(scenario_name, segment, args):
    command = [sys.executable, MINI_CHAT]
    if args.mock:
        command.append("--mock")
    else:
        if args.llm_url:
            command.extend(["--llm-url", args.llm_url])
        if args.api_key:
            command.extend(["--api-key", args.api_key])
        if args.model:
            command.extend(["--model", args.model])
    if segment["force_empty"]:
        command.append("--force-empty")
    command.extend(["--session-dir", args.session_dir, "--session", scenario_name])
    return command


def run_segment(command, messages, report):
    """Кормим mini_chat.py сообщениями + /state + /exit через stdin.

    report — список строк (как в preflight); сбои запуска сегмента
    складываются в него, печатает их вызывающая сторона.
    """
    payload = "\n".join(messages) + "\n/state\n/exit\n"
    try:
        proc = subprocess.run(
            command, input=payload.encode("utf-8"),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=SEGMENT_TIMEOUT,
        )
    except subprocess.TimeoutExpired:
        report.append("ОШИБКА: сегмент превысил таймаут %d с: %s" % (SEGMENT_TIMEOUT, " ".join(command)))
        return False, ""
    if proc.returncode != 0:
        report.append("ОШИБКА: сегмент вернул код %d" % proc.returncode)
        report.append("stderr: %s" % proc.stderr.decode("utf-8", "replace").strip())
        return False, ""
    return True, proc.stdout.decode("utf-8", "replace")


def load_session(session_dir, name):
    path = os.path.join(session_dir, "%s.json" % name)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle), path
    except (OSError, ValueError) as error:
        return None, "%s (%s)" % (path, error)


def fresh_session_name(base, session_dir):
    """Свежее имя сессии: <base>-<штамп>-<pid>, гарантированно не существующее."""
    stamp = time.strftime("%Y%m%d-%H%M%S")
    name = "%s-%s-%d" % (base, stamp, os.getpid())
    attempt = 1
    while os.path.exists(os.path.join(session_dir, "%s.json" % name)):
        attempt += 1
        name = "%s-%s-%d-%d" % (base, stamp, os.getpid(), attempt)
    return name


# ---------------------------------------------------------------------------
# Классификация и валидация ходов
# ---------------------------------------------------------------------------

def classify_reply(reply):
    if reply.startswith(GOAL_ACK_PREFIX):
        return "goal-ack"
    if reply.startswith(IDK_PREFIX):
        return "idk"
    return "llm"


def extract_turns(session_data):
    """Ходы = пары (user, assistant) в истории, по порядку."""
    turns = []
    history = session_data.get("history", [])
    index = 0
    while index < len(history):
        entry = history[index]
        if entry.get("role") == "user" and index + 1 < len(history) \
                and history[index + 1].get("role") == "assistant":
            turns.append((entry.get("content", ""), history[index + 1].get("content", "")))
            index += 2
        else:
            index += 1
    return turns


def check_llm_blocks(reply):
    """Блоки LLM-хода: «Источники:» ровно один раз, >= 1 строка «Цитаты:»."""
    problems = []
    sources_count = reply.count("Источники:")
    quote_lines = len(QUOTE_LINE_RE.findall(reply))
    if sources_count != 1:
        problems.append("«Источники:» встречается %d раз (ожидался 1)" % sources_count)
    if quote_lines < 1:
        problems.append("нет строк «Цитаты:» ([N] «фрагмент»)")
    return sources_count, quote_lines, problems


# ---------------------------------------------------------------------------
# Прогон одного сценария
# ---------------------------------------------------------------------------

def run_scenario(scenario, args, goal_lines):
    print()
    print("=== СЦЕНАРИЙ %s «%s» ===" % (scenario["name"], scenario["title"]))
    print("Ожидается ходов: %d, из них LLM: %d, «не знаю»: 1, цель-ack: 1" %
          (scenario["expected_turns"], scenario["expected_llm_turns"]))

    session_name = fresh_session_name(scenario["name"], args.session_dir)
    print("Сессия: %s.json (каталог %s)" % (session_name, args.session_dir))

    failures = []          # причины FAIL уровня сценария
    turn_rows = []         # строки таблицы: (№, тип, sources, quotes, ok, reason)
    snapshots = []         # (метка сегмента, goal, clar, constr, sources_log, history)
    kinds = expected_turn_types(scenario)

    for segment in scenario["segments"]:
        print()
        print("--- %s ---" % segment["label"])
        command = build_segment_command(session_name, segment, args)
        segment_report = []    # строки отчёта сегмента (сбои запуска и т.п.)
        ok, stdout = run_segment(command, segment["messages"], segment_report)
        for line in segment_report:
            print(line)
        if not ok:
            failures.append("сегмент %s не завершился успешно" % segment["label"])
            break

        data, path_or_error = load_session(args.session_dir, session_name)
        if data is None:
            failures.append("не удалось прочитать сессию: %s" % path_or_error)
            break

        state = data.get("task_state", {})
        snapshot = (segment["label"], state.get("goal", ""),
                    len(state.get("clarifications", [])),
                    len(state.get("constraints", [])),
                    len(data.get("sources_log", [])),
                    len(data.get("history", [])))
        snapshots.append(snapshot)

        # Валидируем только что отыгранные ходы (новые пары user/assistant).
        turns = extract_turns(data)
        start_index = len(turn_rows)
        for offset, (user_text, reply) in enumerate(turns[start_index:]):
            number = start_index + offset + 1
            observed = classify_reply(reply)
            expected = kinds[number - 1] if number - 1 < len(kinds) else "?"
            reasons = []
            if observed != expected:
                reasons.append("тип хода: ожидался %s, получен %s" % (expected, observed))
            sources_count = reply.count("Источники:")
            quote_lines = len(QUOTE_LINE_RE.findall(reply))
            if observed == "llm":
                _, _, block_problems = check_llm_blocks(reply)
                reasons.extend(block_problems)
            elif observed == "goal-ack":
                if sources_count or quote_lines:
                    reasons.append("у цель-ack не должно быть блоков")
            else:  # idk
                if sources_count or quote_lines:
                    reasons.append("у хода «не знаю» не должно быть блоков")
                if not reply.startswith(IDK_PREFIX):
                    reasons.append("ответ не начинается с «Не знаю»")
            row_ok = not reasons
            turn_rows.append((number, observed, sources_count, quote_lines,
                              row_ok, "; ".join(reasons)))

    # -- финальная валидация на файле сессии --------------------------------
    data, path_or_error = load_session(args.session_dir, session_name)
    if data is None:
        failures.append("не удалось прочитать финальную сессию: %s" % path_or_error)
        data = {}

    state = data.get("task_state", {})
    goal = state.get("goal", "")
    clarifications = state.get("clarifications", [])
    constraints = state.get("constraints", [])
    sources_log = data.get("sources_log", [])
    turns = extract_turns(data)

    print()
    print("Потоходная таблица:")
    print("  %-4s %-9s %-11s %-8s %-6s %s" % ("ход", "тип", "Источники:", "Цитаты:", "вердикт", "причина"))
    for number, kind, sources_count, quote_lines, row_ok, reason in turn_rows:
        print("  %-4d %-9s %-11d %-8d %-8s %s" %
              (number, kind, sources_count, quote_lines,
               "PASS" if row_ok else "FAIL", reason))
    if not turn_rows:
        print("  (ходы не зафиксированы)")

    print()
    print("Инварианты финальной сессии:")

    def check(label, condition, detail):
        verdict = "PASS" if condition else "FAIL"
        print("  [%s] %s — %s" % (verdict, label, detail))
        if not condition:
            failures.append(label)
        return condition

    check(
        "цель не изменилась после всех сегментов",
        goal == scenario["goal"],
        "goal=%r" % goal,
    )
    check(
        "уточнений >= %d" % scenario["min_clarifications"],
        len(clarifications) >= scenario["min_clarifications"],
        "фактически %d" % len(clarifications),
    )
    check(
        "каждое уточнение <= %d символов" % CLARIFICATION_LIMIT,
        all(len(item) <= CLARIFICATION_LIMIT for item in clarifications),
        "максимум %d" % max((len(item) for item in clarifications), default=0),
    )
    check(
        "ограничений >= 2",
        len(constraints) >= 2,
        "фактически %d: %s" % (len(constraints), "; ".join(constraints)),
    )
    check(
        "всего ходов = %d" % scenario["expected_turns"],
        len(turns) == scenario["expected_turns"],
        "фактически %d" % len(turns),
    )
    llm_turns = sum(1 for _, kind, _, _, _, _ in turn_rows if kind == "llm")
    idk_turns = sum(1 for _, kind, _, _, _, _ in turn_rows if kind == "idk")
    check(
        "len(sources_log) == число LLM-ходов (%d)" % scenario["expected_llm_turns"],
        len(sources_log) == scenario["expected_llm_turns"] == llm_turns,
        "sources_log=%d, LLM-ходов=%d" % (len(sources_log), llm_turns),
    )
    check(
        "ровно один санкционированный ход «не знаю», без блоков",
        idk_turns == 1,
        "ходов «не знаю»: %d" % idk_turns,
    )

    print()
    print("Трекинг цели и памяти задачи по сегментам:")
    print("  %-28s %-8s %-14s %-12s %-12s %s" %
          ("сегмент", "цель", "уточнения", "ограничения", "sources_log", "история"))
    for label, snap_goal, clar, constr, log, hist in snapshots:
        kept = "сохранена" if snap_goal == scenario["goal"] else "ИЗМЕНИЛАСЬ: %r" % snap_goal
        print("  %-28s %-8s %-14d %-12d %-12d %d (%s)" %
              (label, "да" if snap_goal == scenario["goal"] else "НЕТ",
               clar, constr, log, hist, kept))
    goal_lines.append((scenario["name"], scenario["title"], scenario["goal"], snapshots))

    verdict = "PASS" if not failures else "FAIL"
    print()
    if failures:
        print("SCENARIO %s: FAIL" % scenario["name"].upper())
        for reason in failures:
            print("  причина: %s" % reason)
    else:
        print("SCENARIO %s: PASS" % scenario["name"].upper())
    print("Файл сессии сохранён (не удаляется): %s" %
          os.path.join(args.session_dir, "%s.json" % session_name))
    return not failures


# ---------------------------------------------------------------------------
# Точка входа
# ---------------------------------------------------------------------------

def build_parser():
    parser = argparse.ArgumentParser(
        prog="mini_chat_scenarios.py",
        description="QA-драйвер мини-чата: прогоняет сценарии через "
                    "scripts/mini_chat.py (CLI, без изменений) и валидирует сессии.",
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--mock", dest="mock", action="store_true", default=True,
                       help="мок-LLM на 127.0.0.1:18080 (по умолчанию)")
    group.add_argument("--no-mock", dest="mock", action="store_false",
                       help="реальный LLM: нужны --llm-url/--api-key/--model")
    parser.add_argument("--llm-url", default="",
                        help="передаётся в mini_chat.py для реального LLM (не-mock режим)")
    parser.add_argument("--api-key", default="",
                        help="передаётся в mini_chat.py (никогда не пишется в сессию)")
    parser.add_argument("--model", default="",
                        help="имя модели для реального LLM")
    parser.add_argument("--session-dir", default=DEFAULT_SESSION_DIR,
                        help="каталог файлов сессий (по умолчанию scripts/minichat-sessions)")
    parser.add_argument("--keep-sessions", action="store_true", default=True,
                        help="сессии никогда не удаляются автоматически (по умолчанию; "
                             "флаг оставлен для явности)")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    report_lines = []
    print("=== PREFLIGHT ===")
    ready = preflight(report_lines, args.mock)
    for line in report_lines:
        print(line)
    if not ready:
        print("PREFLIGHT: FAIL — окружение не готово, сценарии не запускаются")
        return 1

    os.makedirs(args.session_dir, exist_ok=True)
    goal_lines = []
    ok1 = run_scenario(scenario_1(), args, goal_lines)
    ok2 = run_scenario(scenario_2(), args, goal_lines)

    print()
    print("=== ИТОГ ===")
    print("SCENARIO 1: %s" % ("PASS" if ok1 else "FAIL"))
    print("SCENARIO 2: %s" % ("PASS" if ok2 else "FAIL"))
    return 0 if (ok1 and ok2) else 1


if __name__ == "__main__":
    sys.exit(main())
