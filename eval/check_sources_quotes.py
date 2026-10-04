#!/usr/bin/env python3
"""Автоматический чекер гарантированных блоков «Источники:»/«Цитаты:» (T7,
план rag-answers-minichat).

Бэкенд Shturman после каждого RAG-ответа детерминированно добавляет блоки
(appendSourcesBlocks): «Источники:» ровно один раз, строки
`[N] <source> · <section> · chunk <id> · <score %.4f>`, при наличии
непустых цитат — «Цитаты:» со строками `[N] «<фрагмент ≤300>»` (та же
нумерация [N]; пустой chunk → без строки цитаты; ноль цитат → блок
«Цитаты:» отсутствует целиком — ВАЛИДНЫЙ случай).

Что проверяется на каждый вопрос из eval/rag-questions.yaml:
  а) «Источники:» встречается в ответе РОВНО один раз;
  б) ≥1 строка `[N] <source> · <section> · chunk <id> · <score>` (score —
     ровно 4 знака после точки);
  в) «Цитаты:» присутствует, ЛИБО отсутствует (валидный случай нуля цитат);
  г) каждая цитата ≤300 символов, одна строка, обёрнута в «…», без ```;
  д) перекрёстная сверка: повторный POST --indexer-url {"query","k":5} —
     каждый chunk_id из блока «Источники:» есть в ответе индексера;
  е) семантический судья: доля слово-токенов ТЕЛА ответа (текст до
     «Источники:», кириллица/латиница, нижний регистр), найденных в
     объединении токенов цитат, ≥ --overlap-min (по умолчанию 0.3).
     Метрика = |тело ∩ цитаты| / |тело| («ответ основан на процитированном»).
     Выбор откалиброван на живом моке (замер 2026-10-04): тело содержит эхо
     только верхнего чанка, остальные цитаты законно слабо пересекаются,
     поэтому Jaccard (0.17–0.31), Dice (0.30–0.48) и среднее по цитатам
     (0.22–0.52) ложно роняли валидные ответы, а body-containment даёт
     0.59–0.79 на всех 10 вопросах — устойчивый запас к порогу 0.3.

expectedSources/expectedSubstrings из YAML — ИНФОРМАЦИОННЫЕ колонки (НЕ
гейт): мок цитирует текст чанков; совпадение подстрок — забота
scripts/rag_eval.py. Ответ, начинающийся с «Не знаю» (санкционированный
шорткат бэкенда при пустых passages), помечается IDK-SANCTIONED и
исключается из требований к блокам; флаг --idk-probe форсирует этот случай
(ragThreshold:1.0 в теле запроса).

Только python3 stdlib (argparse, json, os, re, sys, urllib). Парсер вопросов
— по образцу scripts/rag_eval.py:75-122 (скопирован подход, НЕ импорт).
Аутентификация — как в scripts/rag_eval.py:470: POST /api/auth/users
{"name","pin"} → token; при 4xx — GET /api/auth/users → POST /api/auth/login
{"userId","pin"}; заголовок X-Auth-Token. --api-key добавляет
Authorization: Bearer; --auth-token подставляет готовый токен.

Выход: таблица PASS/FAIL + итог «CHECKER: N/N PASS»; exit 0 только при
полном PASS (IDK-SANCTIONED и валидное отсутствие «Цитаты:» — пройдено).
Индексер недоступен → chunk-проверка FAIL с причиной indexer-unavailable
(негативный сценарий T7: fail-open бэкенда ⇒ ответ без блоков).

Запуск: python3 eval/check_sources_quotes.py --base-url http://127.0.0.1:8199
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "http://127.0.0.1:8199"
DEFAULT_INDEXER_URL = "http://127.0.0.1:18701/search"
DEFAULT_QUESTIONS = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "rag-questions.yaml"
)
DEFAULT_OVERLAP_MIN = 0.3
DEFAULT_TIMEOUT = 120
INDEXER_K = 5  # k бэкенда в config-test.json; сверяем те же top-5
# Верхняя граница таймаута пробы индексатора (сек): индексатор локальный,
# дольше ждать смысла нет даже при большом --timeout.
INDEXER_PROBE_TIMEOUT_S = 30
QUOTE_MAX_CHARS = 300

SOURCES_HEADER = "Источники:"
QUOTES_HEADER = "Цитаты:"
IDK_PREFIX = "Не знаю"

# [N] <source> · <section> · chunk <chunkId> · <score ровно 4 знака>
SOURCE_LINE_RE = re.compile(
    r"^\[(\d+)\]\s(.+?)\s·\s(.+?)\s·\schunk\s(\S+)\s·\s(\d+\.\d{4})$"
)
# [N] «фрагмент» — от первой « до последней » в конце строки: фрагмент может
# содержать ВЛОЖЕННЫЕ «…» (текст чанков их не вычищает — санитайзер убирает
# только ``` и лишние пробелы), поэтому «...» ловим жадно до конца строки.
QUOTE_LINE_RE = re.compile(r"^\[(\d+)\]\s«(.*)»$")
WORD_RE = re.compile(r"[а-яёa-z0-9]+")


# --- Вопросы (подход scripts/rag_eval.py:75-122, копия подхода) --------------


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
            raise ValueError("строка %d вне списка вопросов: %r" % (lineno, raw))
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


# --- Разбор блоков ответа ----------------------------------------------------


def split_reply(reply):
    """Ответ → (тело до первого «Источники:», число вхождений заголовка)."""
    first = reply.find(SOURCES_HEADER)
    if first < 0:
        return reply, 0
    return reply[:first], reply.count(SOURCES_HEADER)


def parse_blocks(reply):
    """Разобрать строки блоков после первого «Источники:».

    Возвращает dict: sources (строки формата (б)), quotes (строки формата
    (г)), quotes_present, malformed (список причин непопадания в формат).
    """
    first = reply.find(SOURCES_HEADER)
    rest = reply[first + len(SOURCES_HEADER):] if first >= 0 else ""
    sources, quotes, malformed = [], [], []
    quotes_present = False
    mode = None
    for line in rest.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if stripped == SOURCES_HEADER:
            mode = "s"
            continue
        if stripped == QUOTES_HEADER:
            mode = "q"
            quotes_present = True
            continue
        source_match = SOURCE_LINE_RE.match(stripped)
        quote_match = QUOTE_LINE_RE.match(stripped)
        if source_match and (mode in (None, "s")):
            sources.append(
                {
                    "n": int(source_match.group(1)),
                    "source": source_match.group(2).strip(),
                    "section": source_match.group(3).strip(),
                    "chunk_id": source_match.group(4),
                    "score": source_match.group(5),
                    "line": stripped,
                }
            )
        elif quote_match and (mode in (None, "q")):
            quotes.append({"n": int(quote_match.group(1)), "text": quote_match.group(2)})
        else:
            malformed.append("строка вне формата блока: %r" % stripped[:80])
    return {
        "sources": sources,
        "quotes": quotes,
        "quotes_present": quotes_present,
        "malformed": malformed,
    }


def token_set(text):
    """Слово-токены (кириллица/латиница/цифры, нижний регистр)."""
    return set(WORD_RE.findall(text.lower()))


def body_quote_overlap(body, quote_texts):
    """Доля токенов ТЕЛА, найденных в объединении токенов цитат.

    None — проверка неприменима (пустое тело или пустые токены цитат).
    """
    body_tokens = token_set(body)
    quote_tokens = set()
    for text in quote_texts:
        quote_tokens |= token_set(text)
    if not body_tokens or not quote_tokens:
        return None
    return len(body_tokens & quote_tokens) / float(len(body_tokens))


def chunk_cross_check(indexer_url, question, chunk_ids, timeout):
    """Повторный поиск в индексере → (ok, недостающие id, причина-ошибки).

    Ответ индексера (проба 2026-10-04, docker 18701): JSON-МАССИВ чанков
    {chunk_id, source, section, score, content}; на всякий случай допускаем
    и обёртку {"results": [...]}. Ошибка сети/не-JSON → FAIL.
    """
    payload = {"query": question, "k": INDEXER_K}
    request = urllib.request.Request(
        indexer_url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError) as exc:
        return False, [], "indexer-unavailable (%s)" % exc
    if isinstance(data, dict):
        data = data.get("results")
    if not isinstance(data, list):
        return False, [], "indexer-unexpected-shape"
    known = set()
    for item in data:
        if isinstance(item, dict) and item.get("chunk_id") is not None:
            known.add(str(item["chunk_id"]))
    missing = sorted({str(cid) for cid in chunk_ids} - known)
    return (not missing), missing, None


# --- Информационные проверки (НЕ гейт; подход scripts/rag_eval.py) -----------


def info_substrings(reply, substrings):
    """Сколько ожидаемых подстрок встретилось в ответе (без учёта регистра)."""
    low = reply.lower()
    return sum(1 for s in substrings if str(s).lower() in low)


def info_sources(reply, sources, retrieved):
    """Сколько ожидаемых источников отражено: имя/базовое имя в ответе ИЛИ
    в retrievedSources[].source (rag-режим, как check_sources в rag_eval)."""
    low = reply.lower()
    retrieved_names = set()
    for entry in retrieved or []:
        name = str(entry.get("source", "")).lower()
        if name:
            retrieved_names.add(name)
            retrieved_names.add(name.rsplit("/", 1)[-1])
    hits = 0
    for src in sources:
        sl = str(src).lower()
        base = sl.rsplit("/", 1)[-1]
        if sl in low or (bool(base) and base in low) or sl in retrieved_names or base in retrieved_names:
            hits += 1
    return hits


# --- Проверка одного ответа --------------------------------------------------


def check_answer(reply, question, indexer_url, timeout, overlap_min):
    """Полный чек одного ответа → строка отчёта.

    Вердикты: PASS / IDK-SANCTIONED / FAIL. Отсутствие «Цитаты:» при
    валидном случае (ноль непустых цитат) нарушением не считается.
    """
    row = {
        "question": question,
        "sources_count": 0,
        "quotes_count": 0,
        "chunk_ok": "n/a",
        "overlap": None,
        "verdict": "FAIL",
        "reasons": [],
    }
    if reply.lstrip().startswith(IDK_PREFIX):
        row["verdict"] = "IDK-SANCTIONED"
        return row

    body, header_occurrences = split_reply(reply)
    blocks = parse_blocks(reply)
    row["sources_count"] = header_occurrences
    row["quotes_count"] = len(blocks["quotes"])

    # (а): «Источники:» ровно один раз.
    if header_occurrences != 1:
        row["reasons"].append("«Источники:» встречается %d раз (ожидался 1)" % header_occurrences)
        if header_occurrences == 0:
            row["reasons"].append("блоки источников отсутствуют (fail-open бэкенда?)")
            return row

    # (б): ≥1 строка [N] source · section · chunk id · score(4 знака).
    if not blocks["sources"]:
        row["reasons"].append("нет строк формата [N] source · section · chunk id · score")
    for problem in blocks["malformed"]:
        row["reasons"].append(problem)

    # (в): «Цитаты:» присутствует ЛИБО отсутствует (0 непустых цитат — валидно).
    # Бэкенд — единственный эмиттер: отсутствие блока ⇒ ноль непустых цитат.
    # (в) нарушено только если строки после «Цитаты:» не распарсились (см. malformed).
    quotes_valid_absence = not blocks["quotes_present"] and not blocks["quotes"]

    # (г): каждая цитата ≤300, одна строка (иначе не совпала бы QUOTE_LINE_RE),
    # обёрнута в «…» (проверяется регэкспом), без ```.
    for quote in blocks["quotes"]:
        if not quote["text"].strip():
            row["reasons"].append("цитата [%d] пустая (пустой chunk не должен давать строку)" % quote["n"])
        if len(quote["text"]) > QUOTE_MAX_CHARS:
            row["reasons"].append(
                "цитата [%d] длиннее %d символов (%d)"
                % (quote["n"], QUOTE_MAX_CHARS, len(quote["text"]))
            )
        if "```" in quote["text"]:
            row["reasons"].append("цитата [%d] содержит ```" % quote["n"])

    # Единая нумерация [N]: индексы цитат ⊆ индексы источников.
    source_ids = {s["n"] for s in blocks["sources"]}
    quote_ids = {q["n"] for q in blocks["quotes"]}
    if quote_ids - source_ids:
        row["reasons"].append(
            "номера цитат без источника: %s" % ",".join(str(n) for n in sorted(quote_ids - source_ids))
        )

    # (д): перекрёстная сверка chunk_id с индексером.
    if blocks["sources"]:
        ok, missing, error = chunk_cross_check(
            indexer_url, question, [s["chunk_id"] for s in blocks["sources"]], timeout
        )
        row["chunk_ok"] = "ok" if ok else "FAIL"
        if not ok:
            detail = error if error else "нет в top-%d индексера: %s" % (INDEXER_K, ",".join(missing))
            row["reasons"].append("chunk-сверка: %s" % detail)

    # (е): семантический судья тело↔цитаты (при нуле цитат — неприменимо).
    if blocks["quotes"]:
        overlap = body_quote_overlap(body, [q["text"] for q in blocks["quotes"]])
        row["overlap"] = overlap
        if overlap is None:
            row["reasons"].append("overlap неприменим: пустое тело или пустые токены цитат")
        elif overlap < overlap_min:
            row["reasons"].append("overlap %.3f < порога %.2f" % (overlap, overlap_min))

    if not row["reasons"]:
        row["verdict"] = "PASS"
    return row


# --- Отчёт -------------------------------------------------------------------


def _truncate(text, limit):
    text = " ".join(str(text).split())
    if len(text) <= limit:
        return text
    return text[: limit - 1].rstrip() + "…"


def format_table(rows, total_sources_headers):
    """Таблица результатов + информационные колонки (НЕ гейт)."""
    lines = [
        "| #  | Вопрос | «Источники:» | Цитат | Chunk | Overlap | Подстроки* | Источн.* | Вердикт | Причина |",
        "|----|----------|------|-------|-------|---------|--------|-------|----------------|----------|",
    ]
    for idx, row in enumerate(rows, start=1):
        question = _truncate(row["question"], 42)
        reason = "; ".join(row["reasons"]) if row["reasons"] else "—"
        lines.append(
            "| %-2d | %s | %s | %s | %s | %s | %s | %s | %s | %s |"
            % (
                idx,
                question,
                row["sources_count"],
                row["quotes_count"],
                row["chunk_ok"],
                "—" if row["overlap"] is None else "%.2f" % row["overlap"],
                row["substr_cell"],
                row["srcinfo_cell"],
                row["verdict"],
                _truncate(reason, 60),
            )
        )
    lines.append("")
    lines.append(
        "* информационные колонки (НЕ гейт): совпадение expectedSubstrings/"
        "expectedSources из eval/rag-questions.yaml; мок цитирует текст чанков, "
        "полное совпадение подстрок — забота scripts/rag_eval.py."
    )
    lines.append(
        "Всего вхождений «Источники:» по не-IDK ответам: %d (ожидается %d — ровно 1 на ответ)."
        % (total_sources_headers, sum(1 for r in rows if r["verdict"] != "IDK-SANCTIONED"))
    )
    return "\n".join(lines)


# --- HTTP --------------------------------------------------------------------


def _post_json(url, payload, timeout, headers=None):
    data = json.dumps(payload).encode("utf-8")
    all_headers = {"Content-Type": "application/json"}
    if headers:
        all_headers.update(headers)
    request = urllib.request.Request(url, data=data, headers=all_headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, json.loads(response.read().decode("utf-8"))


def _get_json(url, timeout):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def obtain_token(base_url, timeout):
    """Создать пользователя «qa-checker» и взять токен (подход rag_eval.py:470).

    При 4xx (пользователь уже есть) — fallback: GET /api/auth/users →
    найти пользователя по имени → POST /api/auth/login.
    """
    name = "qa-checker"
    pin = "1234"
    try:
        _, data = _post_json(base_url + "/api/auth/users", {"name": name, "pin": pin}, timeout)
        return data["token"]
    except urllib.error.HTTPError as exc:
        if not (400 <= exc.code < 500):
            raise
    users = _get_json(base_url + "/api/auth/users", timeout)
    user = next((u for u in users if u.get("name") == name), None)
    if user is None:
        raise RuntimeError("не удалось создать пользователя %r и не нашли его в списке" % name)
    _, data = _post_json(
        base_url + "/api/auth/login", {"userId": user["id"], "pin": pin}, timeout
    )
    return data["token"]


def run_chat(base_url, token, question, timeout, idk_probe, api_key):
    """Один POST /api/chat {"message", "ragEnabled": true[, "ragThreshold":1.0]}."""
    body = {"message": question, "ragEnabled": True}
    if idk_probe:
        body["ragThreshold"] = 1.0
    headers = {"X-Auth-Token": token}
    if api_key:
        headers["Authorization"] = "Bearer %s" % api_key
    _, payload = _post_json(base_url + "/api/chat", body, timeout, headers)
    return payload


# --- CLI ---------------------------------------------------------------------


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Чекер гарантированных блоков «Источники:»/«Цитаты:» (T7); "
        "exit 0 только при полном PASS"
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help="адрес приложения (по умолчанию %(default)s)")
    parser.add_argument("--indexer-url", default=DEFAULT_INDEXER_URL,
                        help="URL индексера /search (по умолчанию %(default)s)")
    parser.add_argument("--questions", default=DEFAULT_QUESTIONS,
                        help="файл вопросов (по умолчанию %(default)s, относительно CWD)")
    parser.add_argument("--overlap-min", type=float, default=DEFAULT_OVERLAP_MIN,
                        help="порог overlap тела и цитат (по умолчанию %(default)s)")
    parser.add_argument("--api-key", default=None,
                        help="сквозной Authorization: Bearer <ключ> для /api/chat")
    parser.add_argument("--auth-token", default=None,
                        help="готовый X-Auth-Token (иначе создаётся/логинится qa-checker:1234)")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT,
                        help="таймаут HTTP-запросов, сек (по умолчанию %(default)s)")
    parser.add_argument("--idk-probe", action="store_true",
                        help="отправлять ragThreshold:1.0 — все ответы «Не знаю» "
                             "(проверка санкционированного шортката)")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)

    try:
        questions = parse_questions(args.questions)
    except ValueError as exc:
        print("Ошибка: %s" % exc, file=sys.stderr)
        return 2

    try:
        token = args.auth_token or obtain_token(args.base_url, args.timeout)
    except (urllib.error.URLError, OSError, RuntimeError, ValueError) as exc:
        print("Не удалось аутентифицироваться на %s: %s" % (args.base_url, exc), file=sys.stderr)
        return 2

    rows = []
    for item in questions:
        try:
            payload = run_chat(
                args.base_url, token, item["question"], args.timeout, args.idk_probe, args.api_key
            )
        except (urllib.error.URLError, OSError, ValueError) as exc:
            print("Предупреждение: запрос не удался (%s): %s" % (item["question"], exc), file=sys.stderr)
            rows.append(
                {
                    "question": item["question"],
                    "sources_count": 0,
                    "quotes_count": 0,
                    "chunk_ok": "n/a",
                    "overlap": None,
                    "substr_cell": "—",
                    "srcinfo_cell": "—",
                    "verdict": "FAIL",
                    "reasons": ["запрос /api/chat не удался: %s" % exc],
                }
            )
            continue
        row = check_answer(
            payload.get("reply", ""),
            item["question"],
            args.indexer_url,
            min(args.timeout, INDEXER_PROBE_TIMEOUT_S),
            args.overlap_min,
        )
        row["substr_cell"] = "%d/%d" % (
            info_substrings(payload.get("reply", ""), item["expectedSubstrings"]),
            len(item["expectedSubstrings"]),
        )
        row["srcinfo_cell"] = "%d/%d" % (
            info_sources(payload.get("reply", ""), item["expectedSources"], payload.get("retrievedSources")),
            len(item["expectedSources"]),
        )
        rows.append(row)

    total_sources_headers = sum(
        r["sources_count"] for r in rows if r["verdict"] != "IDK-SANCTIONED"
    )
    print(format_table(rows, total_sources_headers))
    print("")
    passed = sum(1 for r in rows if r["verdict"] in ("PASS", "IDK-SANCTIONED"))
    total = len(rows)
    print("CHECKER: %d/%d PASS" % (passed, total))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
