#!/usr/bin/env python3
"""Харнесс сравнения RAG / no-RAG и stage-2 RAG-режимов (задача 10).

v1 (по умолчанию, БЕЗ новых флагов): прогоняет контрольные вопросы из
eval/rag-questions.yaml в двух режимах (ragEnabled=false/true) против живого
приложения /api/chat, автоматически проверяет ответы по ожиданиям из YAML
и пишет Markdown-отчёт — формат колонок НЕ меняется (совместимость с фазой 1).

stage-2 (--modes): те же вопросы в режимах ChatRequest v2 через per-request
overrides (T4/T9):
- raw      = только ragEnabled:true (rag без фильтров);
- filtered = +ragThreshold (см. «ПОРОГ filtered» ниже);
- rerank   = +ragRerank:true.
Флаг --rewrite добавляет те же режимы вторым проходом с ragRewrite:true
(итого до 6 прогонов × 10 вопросов). Per-request kBefore override НЕТ
(LOCK OUT): kBefore=20 уже в конфиге приложения (T2: min(4k, 50)).

ПОРОГ filtered (по умолчанию 0.88) — ЗАЩИТНЫЙ ХВОСТ-КАТ по T2-probe
(gw-club-indexer/reports/stage2-probe.md §4): e5-cosine в top-20 сжат до
0.84–0.95 (Q1=0.8588, медиана 0.8694, Q3=0.8845, медиана rank10≈0.865),
поэтому абсолютный cosine-порог — НЕ фильтр релевантности, а обрезка хвоста
ранжирования (при k_before=20 режет примерно с rank 5+). Настоящая
фильтрация — реранкер на sigmoid-шкале с порогом 0.5 (режим rerank;
threshold=0.5 задаётся в конфиге, не здесь). Шкалы НЕ смешиваются: каждая
строка отчёта несёт тег «score: cosine» либо «score: rerank-sigmoid».

Только python3 stdlib (urllib, json, argparse, os, statistics): PyYAML в
системном python нет, поэтому парсер рассчитан на ФИКСИРОВАННЫЙ формат файла
вопросов — список маппингов `question: "..."` / `expectedSubstrings: [...]` /
`expectedSources: [...]` с полнострочными `#`-комментариями.

Сценарии завершения (код выхода ВСЕГДА 0 — отчёт, а не CI-гейт):
- успешно: полная таблица (v1: 20 строк; stage-2: режимы × вопросы) + итог
  в --out и на stdout;
- sidecar недоступен по --rag-url (precheck не 200): диагностика
  «sidecar не запущен» + подсказка запуска, короткий отчёт;
- вопросы не читаются / приложение недоступно: диагностика, короткий отчёт.

Запуск (v1): python3 scripts/rag_eval.py --base-url http://127.0.0.1:8099 --model mock
Запуск (stage-2): python3 scripts/rag_eval.py --model mock --modes raw,filtered,rerank [--rewrite]
"""

import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "http://127.0.0.1:8099"
DEFAULT_QUESTIONS = os.path.join("eval", "rag-questions.yaml")
DEFAULT_OUT = os.path.join("reports", "rag-comparison.md")
DEFAULT_RAG_URL = "http://127.0.0.1:18700/search"
SIDECAR_DOWN_HINT = "cd gw-club-indexer && uv run gwclub serve --port 18700"

# --- stage-2 режимы (ChatRequest v2 per-request overrides, T4/T9) -------------
# ПОРОГ filtered — ЗАЩИТНЫЙ ХВОСТ-КАТ по T2-probe (§4): e5-cosine top-20 сжат
# 0.84–0.95 (Q1=0.8588, медиана 0.8694, Q3=0.8845, rank5≈0.879, rank10≈0.865),
# поэтому абсолютный cosine-порог — НЕ фильтр релевантности, а обрезка хвоста
# ранжирования (0.88 режет ~с rank 5+ при k_before=20). Настоящая фильтрация —
# реранкер (режим rerank, sigmoid-порог 0.5 в конфиге). Диапазон порога (0, 1]
# валидируется Kotlin-роутом (T4); шкалы в отчёте НЕ смешиваются — у каждой
# строки тег «score: cosine» / «score: rerank-sigmoid».
DEFAULT_FILTERED_THRESHOLD = 0.88
STAGE2_MODES = ("raw", "filtered", "rerank")
REWRITE_SUFFIX = "+rewrite"
STAGE2_SCALE = {"raw": "cosine", "filtered": "cosine", "rerank": "rerank-sigmoid"}
STAGE2_OVERRIDE_KEYS = ("ragThreshold", "ragRerank", "ragRewrite")

# --- Чистые функции (тестируются scripts/test_rag_eval.py без сети) ---------


def parse_questions(source):
    """Разобрать вопросы из текста ИЛИ пути к файлу фиксированного формата.

    Возвращает список словарей {question, expectedSubstrings, expectedSources}.
    Полнострочные `#`-комментарии и пустые строки игнорируются; значения —
    строки в кавычках либо инлайн-списки ["a", "b"].
    """
    if "\n" in source:
        text = source
    else:
        try:
            with open(source, encoding="utf-8") as fh:
                text = fh.read()
        except OSError as exc:
            raise ValueError("не удалось прочитать файл вопросов %s: %s" % (source, exc))

    items = []
    current = None
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("- "):
            current = {}
            items.append(current)
            line = line[2:].strip()
        if current is None:
            raise ValueError(
                "строка %d вне списка вопросов: %r" % (lineno, raw)
            )
        key, sep, value = line.partition(":")
        if not sep:
            raise ValueError("строка %d без 'ключ: значение': %r" % (lineno, raw))
        current[key.strip()] = value.strip()

    questions = []
    for idx, item in enumerate(items, start=1):
        missing = [k for k in ("question", "expectedSubstrings", "expectedSources") if k not in item]
        if missing:
            raise ValueError("вопрос %d: нет ключей %s" % (idx, ", ".join(missing)))
        questions.append(
            {
                "question": _clean_scalar(item["question"]),
                "expectedSubstrings": _parse_flow_list(item["expectedSubstrings"]),
                "expectedSources": _parse_flow_list(item["expectedSources"]),
            }
        )
    return questions


def _clean_scalar(value):
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def _parse_flow_list(value):
    value = value.strip()
    if not (value.startswith("[") and value.endswith("]")):
        return [_clean_scalar(value)]
    inner = value[1:-1].strip()
    if not inner:
        return []
    return [_clean_scalar(part) for part in inner.split(",")]


def check_substrings(reply, substrings):
    """Все ожидаемые подстроки в ответе (без учёта регистра)? → (passed, missing)."""
    low = reply.lower()
    missing = [s for s in substrings if s.lower() not in low]
    return (not missing, missing)


def check_sources(reply, sources, retrieved, rag_mode):
    """Источники отражены в результате? → (passed, missing).

    Источник зачтён, если имя файла упомянуто в ответе (целиком или базовое
    имя, без учёта регистра) ИЛИ — только при rag_mode=True — он присутствует
    в retrievedSources[].source. При rag:false retrieved игнорируется.
    """
    low = reply.lower()
    retrieved_names = set()
    for entry in retrieved or []:
        name = str(entry.get("source", "")).lower()
        if name:
            retrieved_names.add(name)
            retrieved_names.add(name.rsplit("/", 1)[-1])
    missing = []
    for src in sources:
        sl = str(src).lower()
        base = sl.rsplit("/", 1)[-1]
        in_reply = sl in low or (bool(base) and base in low)
        in_retrieved = rag_mode and (sl in retrieved_names or base in retrieved_names)
        if not (in_reply or in_retrieved):
            missing.append(src)
    return (not missing, missing)


def judge_display(judge):
    """Judge-блок ChatResponse → строка для отчёта; null/неизвестно → «—»."""
    if isinstance(judge, dict):
        overall = judge.get("overall")
        if isinstance(overall, (int, float)) and not isinstance(overall, bool):
            return str(overall)
    return "—"


def build_chat_payload(question, rag_mode, provider, model, judge, overrides=None):
    """Тело POST /api/chat. Чистая функция (тестируется без сети).

    overrides — dict ChatRequest v2 (ragThreshold/ragRerank/ragRewrite, T4);
    ключ добавляется ТОЛЬКО при значении не None, чтобы v1-тело оставалось
    ровно прежним (инвариант отсутствия override-ключей, как в T6).
    """
    body = {
        "message": question,
        "ragEnabled": bool(rag_mode),
        "provider": provider,
        "model": model,
    }
    if judge:
        body["judgeEnabled"] = True
    for key in STAGE2_OVERRIDE_KEYS:
        value = (overrides or {}).get(key)
        if value is not None:
            body[key] = value
    return body


def stage2_overrides(mode, threshold):
    """Имя stage-2 режима → dict per-request overrides ChatRequest v2.

    raw = {} (в payload только ragEnabled:true); filtered = +ragThreshold;
    rerank = +ragRerank:true; «<режим>+rewrite» — то же с ragRewrite:true
    (rewrite выполняется ДО retrieve, T9). Неизвестное имя → ValueError.
    """
    if mode.endswith(REWRITE_SUFFIX):
        overrides = stage2_overrides(mode[: -len(REWRITE_SUFFIX)], threshold)
        overrides["ragRewrite"] = True
        return overrides
    if mode == "raw":
        return {}
    if mode == "filtered":
        return {"ragThreshold": threshold}
    if mode == "rerank":
        return {"ragRerank": True}
    raise ValueError("неизвестный stage-2 режим: %r" % (mode,))


def score_scale(mode):
    """Шкала score ответа sidecar для режима (тег строки отчёта, E8)."""
    base = mode[: -len(REWRITE_SUFFIX)] if mode.endswith(REWRITE_SUFFIX) else mode
    try:
        return STAGE2_SCALE[base]
    except KeyError:
        raise ValueError("неизвестный stage-2 режим: %r" % (mode,))


def build_mode_matrix(modes, rewrite):
    """Базовые stage-2 режимы → матрица прогонов; --rewrite добавляет те же
    режимы вторым проходом с суффиксом +rewrite."""
    matrix = list(modes)
    if rewrite:
        matrix.extend(mode + REWRITE_SUFFIX for mode in modes)
    return matrix


def mode_summaries(rows):
    """Сводка по режимам (в порядке появления): sources_passed %, медиана
    латентности, число пустых ответов. Чистая функция.

    Пустой ответ = retrievedSources пуст после порога (E1); неудавшийся
    запрос тоже без источников. Медиана — по непустым elapsed_ms.
    """
    modes = []
    for row in rows:
        if row["mode"] not in modes:
            modes.append(row["mode"])
    summaries = []
    for mode in modes:
        subset = [row for row in rows if row["mode"] == mode]
        latencies = sorted(
            row["elapsed_ms"] for row in subset if row["elapsed_ms"] is not None
        )
        summaries.append(
            {
                "mode": mode,
                "sources_passed": sum(1 for row in subset if row["sources_passed"]),
                "total": len(subset),
                "median_ms": statistics.median(latencies) if latencies else None,
                "empty_replies": sum(1 for row in subset if row.get("retrieved_empty")),
            }
        )
    return summaries


def _format_percent(part, total):
    if not total:
        return "—"
    return "%g" % (100.0 * part / total)


def _format_threshold(threshold):
    return "—" if threshold is None else ("%g" % float(threshold))


def format_report(rows, model, stage2=False, threshold=None):
    """Собрать Markdown-отчёт из строк-результатов. Чистая функция.

    stage2=False — прежний v1-формат (rag/no-rag, колонки байт-в-байт как в
    фазе 1). stage2=True — таблица режимов с тегом шкалы у каждой строки
    и сводкой по режимам; threshold — порог режима filtered для подписи.
    """
    if stage2:
        return _format_stage2_report(rows, model, threshold)
    return _format_v1_report(rows, model)


def _row_cells(row):
    """Общие ячейки строки таблицы: (substr, sources, judge, tokens, time)."""
    substr_cell = "%s %d/%d" % (
        "✓" if row["substr_passed"] else "✗",
        row["substr_ok"],
        row["substr_total"],
    )
    sources_cell = "✓" if row["sources_passed"] else "✗"
    if row["prompt_tokens"] is None or row["completion_tokens"] is None:
        tokens_cell = "—"
    else:
        tokens_cell = "%s/%s" % (row["prompt_tokens"], row["completion_tokens"])
    if row["elapsed_ms"] is None:
        time_cell = "—"
    else:
        time_cell = "%.0f мс" % float(row["elapsed_ms"])
    return (
        substr_cell,
        sources_cell,
        judge_display(row["judge"]),
        tokens_cell,
        time_cell,
    )


def _format_v1_report(rows, model):
    total_questions = len({row["question"] for row in rows}) if rows else 0
    rag_full = sum(
        1
        for row in rows
        if row["mode"] == "rag" and row["substr_passed"] and row["sources_passed"]
    )
    norag_full = sum(
        1
        for row in rows
        if row["mode"] == "no-rag" and row["substr_passed"] and row["sources_passed"]
    )
    if rag_full > norag_full:
        verdict = "лучше"
    elif rag_full < norag_full:
        verdict = "хуже"
    else:
        verdict = "равно"

    lines = [
        "# Сравнение RAG / no-RAG",
        "",
        "Модель: %s" % model,
        "Вопросы: %d × 2 режима" % total_questions,
        "",
        "| Вопрос | Режим | Подстроки | Источники | Judge | Токены (промпт/ответ) | Время |",
        "|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        substr_cell, sources_cell, judge_cell, tokens_cell, time_cell = _row_cells(row)
        mode_cell = "rag:вкл" if row["mode"] == "rag" else "rag:выкл"
        question = _truncate(row["question"], 60)
        lines.append(
            "| %s | %s | %s | %s | %s | %s | %s |"
            % (
                question,
                mode_cell,
                substr_cell,
                sources_cell,
                judge_cell,
                tokens_cell,
                time_cell,
            )
        )
    lines.append("")
    lines.append(
        "**Итог:** RAG %s no-RAG по числу полностью пройденных вопросов: %d/%d vs %d/%d"
        % (verdict, rag_full, total_questions, norag_full, total_questions)
    )
    lines.append("")
    return "\n".join(lines)


def _format_stage2_report(rows, model, threshold):
    """Markdown-отчёт stage-2: колонка «Шкала» (тег score у каждой строки,
    чтобы не сравнивать косинусы с сигмоидами) + сводка по режимам."""
    total_questions = len({row["question"] for row in rows}) if rows else 0
    modes = []
    for row in rows:
        if row["mode"] not in modes:
            modes.append(row["mode"])
    lines = [
        "# Сравнение RAG-режимов (stage-2)",
        "",
        "Модель: %s" % model,
        "Вопросы: %d × %d режимов" % (total_questions, len(modes)),
    ]
    if any(mode.split(REWRITE_SUFFIX)[0] == "filtered" for mode in modes):
        lines.append(
            "Порог filtered: %s — защитный хвост-кат по T2-probe (e5-cosine "
            "top-20 сжат 0.84–0.95; абсолютный порог режет хвост ранжирования, "
            "НЕ релевантность; реальный фильтр — реранкер, sigmoid 0.5)."
            % _format_threshold(threshold)
        )
    lines += [
        "",
        "| Вопрос | Режим | Шкала | Подстроки | Источники | Judge | Токены (промпт/ответ) | Время |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        substr_cell, sources_cell, judge_cell, tokens_cell, time_cell = _row_cells(row)
        question = _truncate(row["question"], 60)
        lines.append(
            "| %s | %s | score: %s | %s | %s | %s | %s | %s |"
            % (
                question,
                row["mode"],
                score_scale(row["mode"]),
                substr_cell,
                sources_cell,
                judge_cell,
                tokens_cell,
                time_cell,
            )
        )
    lines.append("")
    lines.append("**Итог по режимам:**")
    for summary in mode_summaries(rows):
        lines.append(
            "- %s: источники %s%% (%d/%d), медиана %s, пустых ответов %d"
            % (
                summary["mode"],
                _format_percent(summary["sources_passed"], summary["total"]),
                summary["sources_passed"],
                summary["total"],
                "—"
                if summary["median_ms"] is None
                else "%.0f мс" % float(summary["median_ms"]),
                summary["empty_replies"],
            )
        )
    lines.append("")
    return "\n".join(lines)


def _truncate(text, limit):
    text = " ".join(str(text).split())
    if len(text) <= limit:
        return text
    return text[: limit - 1].rstrip() + "…"


# --- HTTP-помощники (stdlib urllib) -----------------------------------------


def _post_json(url, payload, timeout):
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, json.loads(response.read().decode("utf-8"))


def _get_json(url, timeout):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def precheck_rag(rag_url, timeout=15):
    """Sidecar жив? POST {rag-url} {"query":"precheck","k":1} → HTTP 200."""
    try:
        status, _ = _post_json(rag_url, {"query": "precheck", "k": 1}, timeout)
        return status == 200
    except (urllib.error.URLError, OSError, ValueError):
        return False


def obtain_token(base_url, timeout=30):
    """Создать пользователя «RAG eval <unix-timestamp>» и взять токен.

    При 4xx (пользователь уже есть) — fallback: GET /api/auth/users →
    найти пользователя по имени → POST /api/auth/login.
    """
    name = "RAG eval %d" % int(time.time())
    pin = "1234"
    try:
        _, data = _post_json(
            base_url + "/api/auth/users", {"name": name, "pin": pin}, timeout
        )
        return data["token"]
    except urllib.error.HTTPError as exc:
        if not (400 <= exc.code < 500):
            raise
    users = _get_json(base_url + "/api/auth/users", timeout)
    user = next((u for u in users if u.get("name") == name), None)
    if user is None:
        raise RuntimeError(
            "не удалось создать пользователя %r и не нашли его в списке" % name
        )
    _, data = _post_json(
        base_url + "/api/auth/login", {"userId": user["id"], "pin": pin}, timeout
    )
    return data["token"]


def run_chat(base_url, token, question, rag_mode, provider, model, judge, timeout=120, overrides=None):
    """Один запрос /api/chat (свежая сессия — без sessionId) → словарь-результат."""
    body = build_chat_payload(question, rag_mode, provider, model, judge, overrides)
    request = urllib.request.Request(
        base_url + "/api/chat",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Auth-Token": token},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return {
        "reply": payload.get("reply", ""),
        "ragEnabled": payload.get("ragEnabled"),
        "retrievedSources": payload.get("retrievedSources") or [],
        "judge": payload.get("judge"),
        "promptTokens": payload.get("promptTokens"),
        "completionTokens": payload.get("completionTokens"),
        "elapsedMs": payload.get("elapsedMs"),
    }


# --- CLI ---------------------------------------------------------------------


def _threshold_type(value):
    try:
        number = float(value)
    except ValueError:
        raise argparse.ArgumentTypeError(
            "порог должен быть числом, получено: %r" % (value,)
        )
    if not (0.0 < number <= 1.0):
        raise argparse.ArgumentTypeError(
            "порог должен быть в диапазоне (0, 1] (Kotlin-роут валидирует "
            "ragThreshold), получено: %s" % (value,)
        )
    return number


def _modes_type(value):
    names = [part.strip() for part in value.split(",") if part.strip()]
    if not names:
        raise argparse.ArgumentTypeError("пустой список stage-2 режимов")
    unknown = [name for name in names if name not in STAGE2_MODES]
    if unknown:
        raise argparse.ArgumentTypeError(
            "неизвестные stage-2 режимы: %s (доступны: %s)"
            % (", ".join(unknown), ", ".join(STAGE2_MODES))
        )
    return names


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Сравнение RAG / no-RAG и stage-2 RAG-режимов (отчёт, exit 0 всегда)"
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help="адрес приложения (по умолчанию %(default)s)")
    parser.add_argument("--model", required=True, help="модель для /api/chat (например mock)")
    parser.add_argument("--provider", default="default",
                        help="провайдер (по умолчанию %(default)s)")
    parser.add_argument("--questions", default=DEFAULT_QUESTIONS,
                        help="файл вопросов (по умолчанию %(default)s, относительно CWD)")
    parser.add_argument("--judge", dest="judge", action="store_true", default=True,
                        help="включить judgeEnabled (по умолчанию)")
    parser.add_argument("--no-judge", dest="judge", action="store_false",
                        help="не просить судью")
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="файл отчёта (по умолчанию %(default)s)")
    parser.add_argument("--rag-url", default=DEFAULT_RAG_URL,
                        help="URL sidecar /search для precheck (по умолчанию %(default)s)")
    parser.add_argument("--modes", type=_modes_type, default=None,
                        help="stage-2 режимы через запятую из %s; включает stage-2 "
                             "прогон вместо v1 rag/no-rag (например raw,filtered,rerank)"
                             % ", ".join(STAGE2_MODES))
    parser.add_argument("--rewrite", action="store_true",
                        help="stage-2: добавить те же режимы вторым проходом с "
                             "ragRewrite:true")
    parser.add_argument("--threshold", type=_threshold_type,
                        default=DEFAULT_FILTERED_THRESHOLD,
                        help="порог режима filtered, диапазон (0, 1] (по умолчанию "
                             "%(default)s — защитный хвост-кат по T2-probe)")
    return parser.parse_args(argv)


def _write_report(path, text):
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def _write_short_report(path, title, lines):
    _write_report(path, "\n".join(["# " + title, ""] + lines + [""]))
    print("\n".join(["# " + title, ""] + lines + [""]))


def _mode_entries(matrix, threshold):
    """Внутренний цикл прогонов: список (mode, rag_mode, overrides).

    matrix is None → v1 (no-rag/rag, без overrides); иначе — stage-2 режимы
    (все с ragEnabled:true) с per-request overrides ChatRequest v2.
    """
    if matrix is None:
        return [("no-rag", False, None), ("rag", True, None)]
    return [(mode, True, stage2_overrides(mode, threshold)) for mode in matrix]


def main(argv=None):
    args = parse_args(argv)
    # Код выхода ВСЕГДА 0: харнесс — отчёт, а не CI-гейт.

    try:
        questions = parse_questions(args.questions)
    except ValueError as exc:
        print("Предупреждение: %s" % exc, file=sys.stderr)
        _write_short_report(
            args.out,
            "Сравнение RAG / no-RAG — не выполнено",
            ["Не удалось прочитать вопросы: %s" % args.questions, "", str(exc)],
        )
        return 0

    # Precheck sidecar: «sidecar не запущен» → диагностический отчёт, exit 0.
    if not precheck_rag(args.rag_url):
        message = [
            "sidecar не запущен — precheck %s не ответил HTTP 200." % args.rag_url,
            "",
            "Подсказка (запуск из корня индексера):",
            "  " + SIDECAR_DOWN_HINT,
        ]
        print("\n".join(message), file=sys.stderr)
        _write_short_report(
            args.out,
            "Сравнение RAG / no-RAG — не выполнено",
            message + ["", "Диагностический режим: прогон вопросов не выполнялся."],
        )
        return 0

    try:
        token = obtain_token(args.base_url)
    except (urllib.error.URLError, OSError, RuntimeError, ValueError) as exc:
        message = [
            "Приложение %s недоступно или аутентификация не прошла: %s" % (args.base_url, exc),
        ]
        print("\n".join(message), file=sys.stderr)
        _write_short_report(
            args.out,
            "Сравнение RAG / no-RAG — не выполнено",
            message + ["", "Диагностический режим: прогон вопросов не выполнялся."],
        )
        return 0

    matrix = build_mode_matrix(args.modes, args.rewrite) if args.modes else None
    rows = []
    for item in questions:
        for mode, rag_mode, overrides in _mode_entries(matrix, args.threshold):
            try:
                result = run_chat(
                    args.base_url,
                    token,
                    item["question"],
                    rag_mode,
                    args.provider,
                    args.model,
                    args.judge,
                    overrides=overrides,
                )
            except (urllib.error.URLError, OSError, ValueError) as exc:
                print(
                    "Предупреждение: запрос не удался (%s, %s): %s" % (item["question"], mode, exc),
                    file=sys.stderr,
                )
                rows.append(
                    {
                        "question": item["question"],
                        "mode": mode,
                        "substr_ok": 0,
                        "substr_total": len(item["expectedSubstrings"]),
                        "substr_passed": False,
                        "sources_passed": False,
                        "judge": None,
                        "prompt_tokens": None,
                        "completion_tokens": None,
                        "elapsed_ms": None,
                        "retrieved_empty": True,
                    }
                )
                continue
            substr_passed, substr_missing = check_substrings(
                result["reply"], item["expectedSubstrings"]
            )
            sources_passed, _ = check_sources(
                result["reply"],
                item["expectedSources"],
                result["retrievedSources"],
                rag_mode,
            )
            rows.append(
                {
                    "question": item["question"],
                    "mode": mode,
                    "substr_ok": len(item["expectedSubstrings"]) - len(substr_missing),
                    "substr_total": len(item["expectedSubstrings"]),
                    "substr_passed": substr_passed,
                    "sources_passed": sources_passed,
                    "judge": result["judge"],
                    "prompt_tokens": result["promptTokens"],
                    "completion_tokens": result["completionTokens"],
                    "elapsed_ms": result["elapsedMs"],
                    "retrieved_empty": len(result["retrievedSources"]) == 0,
                }
            )
            print(
                "  %s %s: подстроки %s, источники %s"
                % (
                    "✓" if substr_passed and sources_passed else "✗",
                    mode,
                    "ok" if substr_passed else "fail",
                    "ok" if sources_passed else "fail",
                ),
                file=sys.stderr,
            )

    report = format_report(
        rows, args.model, stage2=args.modes is not None, threshold=args.threshold
    )
    _write_report(args.out, report)
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
