#!/usr/bin/env python3
"""
Mock LLM provider для QA-цикла «Штурмана» (задача 5 плана
context-compression-shturman, решение 15). ТОЛЬКО stdlib, без зависимостей.

Зачем:
    Все QA-сценарии компрессии истории (задачи 6/7) должны работать без живого
    LLM-ключа. Это детерминированный OpenAI-совместимый стаб: сервер «Штурман»
    подключается к нему через config.dev.json (apiUrl → http://127.0.0.1:18080).

Эндпоинты:
    POST /v1/chat/completions — OpenAI Chat Completions:
      stream: false → обычный JSON (choices[0].message, usage)
      stream: true  → SSE: data: {chunk} … финальный chunk с usage … data: [DONE]
    GET  /v1/models — список моделей (для /api/models фронта)
    GET  /control  — состояние стаба: {"mode", "delay_ms", "requests"}
     POST /control  — частичное управление: {"mode": "plain|planning|execution|"
         "validation|done|illegal|refuse|violate|echo-system|plan-approve|"
         "start-execution|jump-approval|checkpoint|code-dump", "delay_ms": <мс>}
         (FSM-режимы задачи 5 плана task-state-fsm; «illegal» предлагает переход
         done→execution — сервер должен отклонить; инвариант-режимы — задача 5
         плана assistant-invariants; lifecycle-режимы — задача 8 плана
         task-lifecycle-gates: план/старт исполнения, нелегальный переход с
         planning и контрольная точка без блока; code-dump — фиксированный
         дамп кода (3 блока ```python) БЕЗ блока task-state, QA контент-гейта;
         задержка — «пауза в полёте», G2)

Детерминированные метрики (синхронно с SessionHistory.estimateTokens):
    usage.prompt_tokens     = ceil(сумма_длин_всех_сообщений / 2.5)
    usage.completion_tokens = 60 + len(JSON_запроса) % 40
    Сравнение токенов «до/после компрессии» измеримо: при компрессии
    prompt_tokens реально падает.

Поведение ответов:
    - ротация 6 заготовленных русских реплик (заметно разных, чтобы стороны
      mirror-режима визуально различались);
    - если в messages есть system-сообщение, содержащее фразу «Сводка ранее»
      (маркер запроса к суммаризатору) → фиксированный короткий «summary-ответ»;
     - если в messages есть system-сообщение, содержащее фразу «Извлечение фактов»
       (маркер запроса к экстрактору фактов, задача 5) → детерминированный KV-JSON
       [{"key":"цель","value":"<текст из последнего user-сообщения>"}];
     - если в messages есть system-сообщение, содержащее фразу «РЕЛЕВАНТНЫЕ
        ДОКУМЕНТЫ» (маркер RAG-блока, задача 8 плана shturman-rag-mode) →
        детерминированный ответ, цитирующий источники строк
        `[N] source: <файл> · раздел: <section>` (без ```-фенсов;
        refuse/invariant-режимы приоритетнее маркера);
      - если в messages есть system-сообщение, содержащее фразу «переписываешь
        поисковый запрос» (маркер запроса QueryRewriter, задача 5 плана
        shturman-rag-stage2) → детерминированный identity-ответ: текст
        последнего user-сообщения БЕЗ ИЗМЕНЕНИЙ; проверяется ПЕРВЫМ (до
        FACTS/SUMMARY, инвариант- и RAG-маркеров), rewrite — standalone
        суб-вызов, счётчик ротации не трогаем;
    - tools в теле запроса игнорируются; промт-протокол инструментов
      (PromptToolTransport, system-сообщение «Ты ассистент с доступом к
      инструментам») эмулируется минимально для E2E памяти агента:
      если в наборе инструментов есть memory_save и последнее сообщение
      пользователя содержит «запомни» → ответ блоком ```json с вызовом
      memory_save; если в контексте уже есть tool-результат (роль "tool")
      → финальный текст «сохранено». Остальные обращения — обычный текст.

Запуск:
    python3 scripts/mock-llm.py --port 18080

В фоне (QA-инфраструктура Wave 1–3, убивается F-волной):
    nohup python3 scripts/mock-llm.py --port 18080 > /tmp/mock-llm.log 2>&1 & \
        echo $! > /tmp/mock-llm.pid
"""

import argparse
import json
import math
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Маркер суммаризационного запроса (согласовано с задачей 4/6: промпт
# суммаризации содержит в system-сообщении эту фразу).
SUMMARY_MARKER = "Сводка ранее"

# Маркер запроса на извлечение фактов (согласовано с FactPrompts.FACTS_MARKER,
# задача 5): system-промпт содержит эту фразу — отвечаем детерминированным KV-JSON.
FACTS_MARKER = "Извлечение фактов"

# Маркер блока инвариантов (согласовано с InvariantPrompts.INVARIANTS_MARKER,
# задача 6 плана assistant-invariants): system-промпт содержит эту фразу —
# режим refuse отказывает, цитируя первый инвариант после заголовка.
INVARIANTS_MARKER = "НЕПРЕЛОЖНЫЕ ИНВАРИАНТЫ"

# Маркер RAG-блока (точная копия RagPrompts.RAG_MARKER, задача 4 плана
# shturman-rag-mode): system-промпт содержит эту фразу — отвечаем детерминированным
# ответом, цитирующим источники строк `[N] source: <файл> · раздел: <section>`,
# БЕЗ ```-фенсов (не пересекается с code-dump-логикой). refuse/invariant-режимы
# приоритетнее маркера (refuse + RAG-блок → refuse).
RAG_MARKER = "РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ"

# Маркер запроса на перепись поискового запроса (точная строка из
# system-промпта QueryRewriter, задача 5 плана shturman-rag-stage2 — жёсткий
# контракт с Kotlin-стороной): отвечаем ДЕТЕРМИНИРОВАННЫМ identity —
# текст последнего user-сообщения без изменений. ПОЧЕМУ identity, а не
# «умный» рерайт: mock проверяет МЕХАНИКУ fail-open рерайта (таймаут/ошибка
# → оригинал) и воспроизводимость сравнения rewrite on/off; КАЧЕСТВО
# переписи меряется только с реальным LLM (зафиксировано в плане, задача 7).
REWRITE_MARKER = "переписываешь поисковый запрос"

# Строка источника в RAG-блоке: `[N] source: <файл> · раздел: <section>`.
RAG_SOURCE_LINE = re.compile(r"^\[(\d+)\]\s*source:\s*(.+?)\s*·\s*раздел:\s*(.+?)\s*$")

# Маркер промт-протокола инструментов (PromptToolTransport.buildToolPrompt):
# system-сообщение начинается с этой фразы и перечисляет доступные инструменты.
TOOLS_MARKER = "Доступные инструменты"

# Имя инструмента долговременной памяти: вызов эмулируется только если он
# реально есть в наборе (memoryTools выключен — инструмента в промте нет).
MEMORY_SAVE_TOOL = "memory_save"

# Ответ-вызов инструмента: ровно один блок ```json по правилам промт-протокола
# (PromptToolTransport.parseToolCall разбирает tool + arguments).
TOOL_CALL_REPLY = (
    "```json\n"
    "{\"tool\": \"memory_save\", \"arguments\": "
    "{\"kind\": \"decision\", \"title\": \"Порт деплоя\", "
    "\"content\": \"Для деплоя используем порт 8083\"}}\n"
    "```"
)

# Финальный текст после tool-результата memory_save (вторая итерация цикла).
SAVED_REPLY = (
    "Готово: сохранил в долговременную память — для деплоя используем порт 8083. "
    "Запись видна в панели «Память», вкладка «Долговременная»."
)

# Фиксированный «summary-ответ» — короткий, как результат сжатия истории.
SUMMARY_REPLY = (
    "Сводка ранее: обсуждались параметры запуска и выбор моделей; "
    "приняты бюджет сводки 400 токенов и окно 5 сообщений; "
    "открытых вопросов не осталось."
)

# Ротация обычных реплик: заметно разные, чтобы стороны mirror различались.
REPLIES = [
    "Курс рассчитан: держим 30 узлов, запас хода 12 часов, отклонений нет.",
    "Понял, штурман. Записываю в бортовой журнал: все системы в норме, продолжаем движение.",
    "Готово. По моим расчётам расход топлива снизится примерно на восемь процентов.",
    "Принял. Проверяю курс повторно: связь устойчивая, манёвров не требуется.",
    "Зафиксировано в журнале. Если понадобится уточнить расчёт — скажи, пересчитаю.",
    "Принято к исполнению. Отмечаю в журнале и возвращаюсь к основному маршруту.",
]

# Режимы FSM-блока (задача 5 плана task-state-fsm): обычная реплика + блок
# ```task-state. «illegal» предлагает done→execution — сервер обязан отклонить.
TASK_MODES = {
    "planning": ("Составление плана полёта", "Перейти к исполнению"),
    "execution": ("Исполнение по плану", "Перейти к проверке"),
    "validation": ("Проверка результата", "Завершить или доработать"),
    "done": ("Задача завершена", "Начать новую задачу"),
    "illegal": ("Прыжок с done на execution", "Должен быть отклонён сервером"),
}

# Режимы вейт-гейтов жизненного цикла (задача 8 плана task-lifecycle-gates).
# stage пишется ЗАГЛАВНЫМ (сервер нормализует через .trim()?.uppercase(),
# TaskStateJson.kt:49); у checkpoint stage=None — блок не эмитится:
# ответ — вопрос о продолжении итерации без перехода этапа.
LIFECYCLE_MODES = {
    "plan-approve": {
        "stage": "PLAN_APPROVED",
        "step": "План полёта утверждён",
        "action": "Приступаю к реализации?",
        "text": "План утверждён и зафиксирован в бортовом журнале. "
                "К исполнению пока не приступал — работы в этапе планирования не велось. "
                "Приступаю к реализации?",
    },
    "start-execution": {
        "stage": "EXECUTION",
        "step": "Итерация реализации 1",
        "action": "Выполнить итерацию реализации",
        "text": "Приступаю к реализации: первая итерация выполнена — базовый курс "
                "рассчитан, отклонений не обнаружено. Продолжаем следующую итерацию?",
    },
    "jump-approval": {
        "stage": "EXECUTION",
        "step": "Прыжок с planning на execution",
        "action": "Нелегальный переход — должен быть отклонён сервером",
        "text": "Перехожу сразу к исполнению, минуя утверждение плана.",
    },
    "checkpoint": {
        "stage": None,
        "step": "Итерация без перехода",
        "action": "Продолжить текущую итерацию",
        "text": "Контрольная точка итерации: работа идёт, переход этапа задачи "
                "не произведён. Продолжаем текущую итерацию?",
    },
}

# Режим code-dump (QA контент-гейта): фиксированный «дамп ядра бота» —
# 3 блока ```python (хендлеры / диспетчер / хранилище, каждый 5–12 строк)
# БЕЗ блока task-state: контент-гейт должен срабатывать по объёму кода,
# а не по наличию маркера. Один фиксированный вариант, без параметризации.
CODE_DUMP_REPLY = (
    "Вот ядро бота: три файла, этого хватает для первого запуска.\n\n"
    "```python\n"
    "# bot/handlers.py — хендлеры входящих сообщений\n"
    "def on_start(message):\n"
    "    return \"Привет! Напиши /help, если нужны команды.\"\n"
    "\n"
    "def on_help(message):\n"
    "    return \"Команды: /start — приветствие, /time — время сервера.\"\n"
    "\n"
    "def on_time(message):\n"
    "    return \"Сейчас %s\" % now_str()\n"
    "```\n\n"
    "```python\n"
    "# bot/dispatch.py — диспетчер: маршрутизация команд\n"
    "COMMANDS = {\n"
    "    \"/start\": on_start,\n"
    "    \"/help\": on_help,\n"
    "    \"/time\": on_time,\n"
    "}\n"
    "\n"
    "def dispatch(message):\n"
    "    command = message.text.strip().split(\" \", 1)[0].lower()\n"
    "    handler = COMMANDS.get(command)\n"
    "    return handler(message) if handler else \"Неизвестная команда. /help\"\n"
    "```\n\n"
    "```python\n"
    "# bot/storage.py — хранилище пользователей (dict в памяти)\n"
    "USERS = {}\n"
    "\n"
    "def remember_user(chat_id, name):\n"
    "    USERS[chat_id] = {\"name\": name, \"seen\": now_str()}\n"
    "\n"
    "def known_user(chat_id):\n"
    "    return chat_id in USERS\n"
    "```\n"
    "Связка: диспетчер маршрутизирует команды в хендлеры, storage помнит пользователей."
)

# Режимы code-dump: mode → фиксированный ответ (отдельный словарь — не
# смешивать со структурами TASK_MODES/LIFECYCLE_MODES).
CODE_DUMP_MODES = {"code-dump": CODE_DUMP_REPLY}

# Режимы инвариантов (задача 5 плана assistant-invariants):
# refuse — отказ с цитированием инварианта, ЕСЛИ маркер блока в system
# (иначе обычный ротационный ответ: детерминированный тест инъекции);
# violate — фиксированное нарушение (проверка passthrough: сервер не
# блокирует); echo-system — дамп полученных system-сообщений (инъекция).
INVARIANT_MODES = {
    "refuse": "отказ, если маркер блока инвариантов в system-сообщениях",
    "violate": "фиксированное предложение, нарушающее инвариант",
    "echo-system": "дамп полученных system-сообщений",
}

# Фиксированный ответ режима violate: нарушает гипотетический инвариант
# («не предлагать jQuery») — проверка, что блокировки на сервере нет.
VIOLATE_REPLY = (
    "Предлагаю переписать всё на jQuery и разложить состояние в глобальные "
    "переменные — это быстрее всего."
)

# Управление режимом (QA-сценарии): {"mode": ..., "delay_ms": ...} через
# GET/POST /control; задержка нужна сценарию «пауза в полёте» (G2).
_mode = {"mode": "plain", "delay_ms": 0}
_requests = 0  # счётчик запросов к /v1/chat/completions (команда «пауза» не должна его двигать)


def _task_block(mode):
    """Формирует реплику с блоком ```task-state для режима из TASK_MODES."""
    step, action = TASK_MODES[mode]
    stage = "execution" if mode == "illegal" else mode
    block = (
        "```task-state\n"
        "{\"stage\": \"%s\", \"step\": \"%s\", \"expected_action\": \"%s\"}\n"
        "```" % (stage, step, action)
    )
    return _pick_rotation() + "\n\n" + block


def _control_state():
    """Снимок состояния стаба для GET/POST /control."""
    with _lock:
        return {"mode": _mode["mode"], "delay_ms": _mode["delay_ms"], "requests": _requests}


def _control_update(payload):
    """Применяет частичное обновление режима (mode/delay_ms), возвращает состояние."""
    if isinstance(payload, dict):
        with _lock:
            if "mode" in payload:
                mode = payload["mode"]
                if (
                    mode == "plain"
                    or mode in TASK_MODES
                    or mode in INVARIANT_MODES
                    or mode in LIFECYCLE_MODES
                    or mode in CODE_DUMP_MODES
                ):
                    _mode["mode"] = mode
                else:
                    return None
            if "delay_ms" in payload:
                delay = payload["delay_ms"]
                if isinstance(delay, int) and delay >= 0:
                    _mode["delay_ms"] = delay
                else:
                    return None
            return _control_state()
    return None

# RLock: _control_update держит блокировку и вызывает _control_state, которая
# берёт её повторно (threading.Lock не реентерабелен — POST /control зависал).
_lock = threading.RLock()
_counter = 0  # счётчик ротации реплик
_seen_models = set()  # модели, встречавшиеся в запросах (для GET /v1/models)


def _content_chars(message):
    """Длина content сообщения: строка — её длина; список частей — сумма text; null — 0."""
    content = message.get("content")
    if content is None:
        return 0
    if isinstance(content, str):
        return len(content)
    if isinstance(content, list):
        return sum(len(part.get("text", "")) for part in content if isinstance(part, dict))
    return 0


def _compute_usage(raw_body, messages):
    """Детерминированный usage (решение 15): prompt — chars/2.5, completion — от длины запроса."""
    total_chars = sum(_content_chars(m) for m in messages if isinstance(m, dict))
    prompt_tokens = math.ceil(total_chars / 2.5) if total_chars > 0 else 0
    completion_tokens = 60 + len(raw_body) % 40
    return {
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "total_tokens": prompt_tokens + completion_tokens,
    }


def _facts_reply(messages):
    """Детерминированный KV-JSON фактов: key="цель", value — текст из последнего
    user-сообщения (часть после «Новое сообщение пользователя: », если есть).
    Одинаковый вход → одинаковый выход, для воспроизводимых QA-сценариев."""
    last_user = ""
    for message in messages:
        if isinstance(message, dict) and message.get("role") == "user":
            content = message.get("content")
            if isinstance(content, str):
                last_user = content
    marker = "Новое сообщение пользователя: "
    idx = last_user.rfind(marker)
    text = last_user[idx + len(marker):] if idx >= 0 else last_user
    return json.dumps([{"key": "цель", "value": text}], ensure_ascii=False)


def _last_user_text(messages):
    """Текст последнего user-сообщения (строка или список частей)."""
    text = ""
    for message in messages:
        if isinstance(message, dict) and message.get("role") == "user":
            content = message.get("content")
            if isinstance(content, str):
                text = content
            elif isinstance(content, list):
                text = " ".join(
                    part.get("text", "") for part in content if isinstance(part, dict)
                )
    return text


def _first_invariant(content):
    """Первый инвариант после заголовка-маркера: первая непустая строка,
    не заголовок секции («Глобальные:»/«Сессия:»), нумерация списка обрезана.
    None, если после заголовка инвариантов нет."""
    lines = content.splitlines()
    for i, line in enumerate(lines):
        if INVARIANTS_MARKER in line:
            for following in lines[i + 1:]:
                text = following.strip()
                if not text or text in ("Глобальные:", "Сессия:"):
                    continue
                match = re.match(r"^(?:\d+[.)]|[-*])\s*", text)
                if match:
                    text = text[match.end():].strip()
                return text or None
    return None


def _parse_rag_entries(content):
    """Разбирает RAG-блок: список (source, section, [строки чанка]) по строкам
    `[N] source: <файл> · раздел: <section>`; чанк — строки до следующей
    нумерованной строки. Пустой список, если нумерованных строк нет."""
    entries = []
    current = None
    for line in content.splitlines():
        match = RAG_SOURCE_LINE.match(line.strip())
        if match:
            if current is not None:
                entries.append(current)
            current = (match.group(2), match.group(3), [])
        elif current is not None:
            current[2].append(line)
    if current is not None:
        entries.append(current)
    return entries


def _rag_reply(content):
    """Детерминированный RAG-ответ (задача 8 плана shturman-rag-mode;
    T2 rag-answers-minichat: печать списка ссылок на файлы исключена —
    блоки ссылок/цитат теперь эмитит только бэкенд Штурмана, mock
    остаётся ответчиком простым ответом): «По базе знаний: <source1>
    (раздел <section1>). <первые 200 символов первого чанка>».
    Без ```-фенсов.
    None, если в блоке нет нумерованных строк источников."""
    entries = _parse_rag_entries(content)
    if not entries:
        return None
    source, section, chunk_lines = entries[0]
    parts = ["По базе знаний: %s (раздел %s)." % (source, section)]
    chunk = "\n".join(chunk_lines).strip()
    if chunk:
        parts.append(chunk[:200])
    return " ".join(parts)


def _rewrite_reply(messages):
    """Детерминированный rewrite-ответ (задача 7 плана shturman-rag-stage2):
    identity — текст последнего user-сообщения БЕЗ ИЗМЕНЕНИЙ (ничего не
    обрезаем, ничего не добавляем). Почему identity: mock проверяет механику
    fail-open рерайта (таймаут/ошибка → оригинал) и воспроизводимость
    сравнения rewrite on/off; качество переписи меряется только с реальным
    LLM. Счётчик ротации не трогаем: одинаковый вход → одинаковый выход."""
    return _last_user_text(messages)


def _echo_system_reply(messages):
    """Детерминированный дамп system-сообщений: роль + первые 200 символов
    каждого, с разделителем-переносом (задача 5 плана assistant-invariants)."""
    lines = ["SYSTEM MESSAGES:"]
    for message in messages:
        if not isinstance(message, dict) or message.get("role") != "system":
            continue
        content = message.get("content")
        if content is None:
            text = ""
        elif isinstance(content, str):
            text = content
        else:
            text = " ".join(part.get("text", "") for part in content if isinstance(part, dict))
        lines.append("[%s] %s" % (message.get("role"), text[:200]))
    return "\n".join(lines)


def _pick_reply(messages, mode):
    """Rewrite (задача 7 shturman-rag-stage2) — identity последнего
    user-сообщения, проверяется ПЕРВЫМ (до всех остальных маркеров/режимов);
    сводка/факты — фиксированные ответы по маркерам; инвариант-режимы
    (задача 5 assistant-invariants) — refuse/violate/echo-system; FSM-режим —
    реплика с блоком task-state; tool-протокол памяти — по итерации цикла
    (вызов → финальный текст); иначе — реплика из ротации."""
    # Rewrite-маркер: standalone суб-вызов QueryRewriter — проверяем ДО
    # FACTS/SUMMARY, invariant-режимов, RAG-маркера и ротации (rewrite-промпт
    # не несёт RAG-маркера и не должен попадать в ротацию; ответ — identity,
    # счётчик ротации не трогаем).
    for message in messages:
        if not isinstance(message, dict) or message.get("role") != "system":
            continue
        content = message.get("content")
        if isinstance(content, str) and REWRITE_MARKER in content:
            print("mock-llm: rewrite-запрос (маркер «%s») — identity-ответ" % REWRITE_MARKER, flush=True)
            return _rewrite_reply(messages)

    for message in messages:
        if not isinstance(message, dict) or message.get("role") != "system":
            continue
        content = message.get("content")
        if not isinstance(content, str):
            continue
        if FACTS_MARKER in content:
            print("mock-llm: запрос на извлечение фактов (маркер «%s»)" % FACTS_MARKER, flush=True)
            return _facts_reply(messages)
        if SUMMARY_MARKER in content:
            return SUMMARY_REPLY

    if mode in INVARIANT_MODES:
        print("mock-llm: инвариант-режим «%s»" % mode, flush=True)
        if mode == "refuse":
            for message in messages:
                if not isinstance(message, dict) or message.get("role") != "system":
                    continue
                content = message.get("content")
                if isinstance(content, str) and INVARIANTS_MARKER in content:
                    first = _first_invariant(content)
                    if first is None:
                        print("mock-llm: маркер инвариантов без текста — обычный ответ", flush=True)
                        return _pick_rotation()
                    return (
                        "Не могу выполнить: это противоречит инварианту „%s“. "
                        "Это правило задано как непреодолимое ограничение." % first
                    )
            # Блока нет в system — обычный ротационный ответ (тест инъекции).
            return _pick_rotation()
        if mode == "violate":
            return VIOLATE_REPLY
        return _echo_system_reply(messages)

    # RAG-маркер (задача 8 плана shturman-rag-mode): после маркеров
    # FACTS/SUMMARY и ПОСЛЕ invariant-режимов (refuse приоритетнее RAG-блока),
    # до ротации — ответ детерминированный, не зависит от счётчика.
    for message in messages:
        if not isinstance(message, dict) or message.get("role") != "system":
            continue
        content = message.get("content")
        if isinstance(content, str) and RAG_MARKER in content:
            print("mock-llm: RAG-запрос (маркер «%s»)" % RAG_MARKER, flush=True)
            reply = _rag_reply(content)
            if reply is None:
                print("mock-llm: RAG-маркер без строк источников — обычный ответ", flush=True)
                return _pick_rotation()
            return reply

    if mode in LIFECYCLE_MODES:
        spec = LIFECYCLE_MODES[mode]
        print("mock-llm: lifecycle-режим «%s»" % mode, flush=True)
        if spec["stage"] is None:
            return spec["text"]
        block = (
            "```task-state\n"
            "{\"stage\": \"%s\", \"step\": \"%s\", \"expected_action\": \"%s\"}\n"
            "```" % (spec["stage"], spec["step"], spec["action"])
        )
        return spec["text"] + "\n\n" + block

    if mode in CODE_DUMP_MODES:
        print("mock-llm: code-dump-режим — дамп кода, без блока task-state", flush=True)
        return CODE_DUMP_MODES[mode]

    if mode in TASK_MODES:
        print("mock-llm: FSM-режим «%s» — блок task-state" % mode, flush=True)
        return _task_block(mode)

    # Follow-up раунд цикла: tool-результат уже в контексте — финальный текст
    # (проверка ВЫШЕ раунда вызова, иначе «запомни» в истории зациклит стаб).
    tool_contents = [
        m.get("content") or "" for m in messages
        if isinstance(m, dict) and m.get("role") == "tool"
    ]
    if tool_contents:
        if "долговременную память" in tool_contents[-1]:
            print("mock-llm: финал после memory_save", flush=True)
            return SAVED_REPLY
        return _pick_rotation()

    # Раунд вызова: промт-протокол перечисляет инструменты; memory_save + «запомни» → блок вызова
    last_user = _last_user_text(messages).lower()
    for message in messages:
        if not isinstance(message, dict) or message.get("role") != "system":
            continue
        content = message.get("content")
        if isinstance(content, str) and TOOLS_MARKER in content:
            if MEMORY_SAVE_TOOL in content and "запомни" in last_user:
                print("mock-llm: эмуляция вызова memory_save (промт-протокол)", flush=True)
                return TOOL_CALL_REPLY
            break
    return _pick_rotation()


def _pick_rotation():
    global _counter
    with _lock:
        reply = REPLIES[_counter % len(REPLIES)]
        _counter += 1
    return reply


def _split_reply(reply, parts=3):
    """Разбивает реплику на части для SSE-чанков (минимум одна часть)."""
    if not reply:
        return [""]
    size = max(1, math.ceil(len(reply) / parts))
    return [reply[i:i + size] for i in range(0, len(reply), size)]


class MockLLMHandler(BaseHTTPRequestHandler):
    server_version = "MockLLM/1.0"

    def _send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_sse(self, payload):
        self.wfile.write(("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode("utf-8"))
        self.wfile.flush()

    def do_GET(self):
        if self.path.rstrip("/") == "/control":
            self._send_json(200, _control_state())
            return
        if self.path.rstrip("/") == "/v1/models":
            with _lock:
                models = sorted(_seen_models) or ["mock-1"]
            self._send_json(200, {"data": [{"id": m} for m in models]})
            return
        self._send_json(404, {"error": {"message": "Неизвестный эндпоинт: %s" % self.path}})

    def do_POST(self):
        if self.path.rstrip("/") == "/control":
            length = int(self.headers.get("Content-Length") or 0)
            raw_body = self.rfile.read(length).decode("utf-8", "replace") if length > 0 else "{}"
            try:
                payload = json.loads(raw_body) if raw_body else {}
            except json.JSONDecodeError:
                payload = None
            state = _control_update(payload) if payload is not None else None
            if state is None:
                self._send_json(400, {"error": {"message": "Ожидаются mode (plain|%s) и/или delay_ms (int ≥ 0)"
                                                             % "|".join(sorted(
                                                                 set(TASK_MODES)
                                                                 | set(INVARIANT_MODES)
                                                                 | set(LIFECYCLE_MODES)
                                                                 | set(CODE_DUMP_MODES)
                                                             ))}})
            else:
                self._send_json(200, state)
            return

        if self.path.rstrip("/") != "/v1/chat/completions":
            self._send_json(404, {"error": {"message": "Неизвестный эндпоинт: %s" % self.path}})
            return

        length = int(self.headers.get("Content-Length") or 0)
        raw_body = self.rfile.read(length).decode("utf-8", "replace") if length > 0 else ""
        try:
            request = json.loads(raw_body) if raw_body else {}
        except json.JSONDecodeError:
            self._send_json(400, {"error": {"message": "Некорректный JSON запроса"}})
            return

        messages = request.get("messages") or []
        model = request.get("model") or "mock"
        global _requests
        with _lock:
            _seen_models.add(model)
            _requests += 1
            mode = _mode["mode"]
            delay_ms = _mode["delay_ms"]
        if delay_ms > 0:
            print("mock-llm: задержка ответа %d мс" % delay_ms, flush=True)
            time.sleep(delay_ms / 1000.0)

        usage = _compute_usage(raw_body, messages)
        reply = _pick_reply(messages, mode)
        created = int(time.time())

        if request.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.end_headers()
            chunk_base = {
                "id": "chatcmpl-mock-%d" % created,
                "object": "chat.completion.chunk",
                "created": created,
                "model": model,
            }
            # Первый чанк: роль без контента (формат OpenAI).
            self._send_sse(dict(chunk_base, choices=[{
                "index": 0, "delta": {"role": "assistant", "content": ""}, "finish_reason": None,
            }]))
            for piece in _split_reply(reply):
                self._send_sse(dict(chunk_base, choices=[{
                    "index": 0, "delta": {"content": piece}, "finish_reason": None,
                }]))
            # Финальный чанк: finish_reason + usage.
            self._send_sse(dict(chunk_base, choices=[{
                "index": 0, "delta": {}, "finish_reason": "stop",
            }], usage=usage))
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            return

        self._send_json(200, {
            "id": "chatcmpl-mock-%d" % created,
            "object": "chat.completion",
            "created": created,
            "model": model,
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": reply},
                "finish_reason": "stop",
            }],
            "usage": usage,
        })


def main():
    parser = argparse.ArgumentParser(description="Детерминированный OpenAI-совместимый mock LLM для QA")
    parser.add_argument("--port", type=int, default=18080, help="порт HTTP-сервера (по умолчанию 18080)")
    parser.add_argument("--host", default="127.0.0.1", help="адрес прослушивания (по умолчанию 127.0.0.1)")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), MockLLMHandler)
    print("mock-llm: POST /v1/chat/completions на http://%s:%d (stdlib, детерминированный usage)" % (args.host, args.port))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
