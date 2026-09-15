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
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Маркер суммаризационного запроса (согласовано с задачей 4/6: промпт
# суммаризации содержит в system-сообщении эту фразу).
SUMMARY_MARKER = "Сводка ранее"

# Маркер запроса на извлечение фактов (согласовано с FactPrompts.FACTS_MARKER,
# задача 5): system-промпт содержит эту фразу — отвечаем детерминированным KV-JSON.
FACTS_MARKER = "Извлечение фактов"

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

_lock = threading.Lock()
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


def _pick_reply(messages):
    """Сводка/факты — фиксированные ответы по маркерам; tool-протокол памяти —
    по итерации цикла (вызов → финальный текст); иначе — реплика из ротации."""
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
        if self.path.rstrip("/") == "/v1/models":
            with _lock:
                models = sorted(_seen_models) or ["mock-1"]
            self._send_json(200, {"data": [{"id": m} for m in models]})
            return
        self._send_json(404, {"error": {"message": "Неизвестный эндпоинт: %s" % self.path}})

    def do_POST(self):
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
        with _lock:
            _seen_models.add(model)

        usage = _compute_usage(raw_body, messages)
        reply = _pick_reply(messages)
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
