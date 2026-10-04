#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Мини-чат CLI: REPL с RAG-подсказкой, памятью задачи и блоками «Источники:»/«Цитаты:».

Автономный скрипт (только стандартная библиотека Python). Контракт блоков —
1:1 с бэкендом (см. src/main/kotlin/com/example/aiweb/rag/RagPrompts.kt):
после каждого успешного ответа LLM детерминированно добавляются блоки
«Источники:» и «Цитаты:»; модель их НЕ выводит сама.

Использование:
    python3 scripts/mini_chat.py [--mock] [--session ИМЯ] [прочие флаги]

Команды REPL:
    /state  — показать состояние задачи (не «ход» диалога)
    /new    — начать новую сессию (новый файл с суффиксом-временем)
    /exit   — сохранить сессию и выйти (не «ход» диалога)

Ключевые свойства:
  * сессия пишется АТОМАРНО (tmp-файл + os.replace) после КАЖДОГО хода;
  * в history хранятся только пары user/assistant (никаких system);
  * память задачи (цель/уточнения/ограничения) выводится детерминированными
    эвристиками и инжектится в system-промт каждый ход;
  * пустой RAG-результат → фиксированный ответ «Не знаю…» без вызова LLM;
  * ошибка RAG → предупреждение и ход БЕЗ RAG-блока (fail-open);
  * API-ключи в файл сессии никогда не попадают.
"""

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime

# ---------------------------------------------------------------------------
# Константы контракта (согласованы по смыслу с бэкендом; канонический текст
# INSTRUCTION/RAG_MARKER живёт в src/main/kotlin/com/example/aiweb/rag/RagPrompts.kt,
# здесь INSTRUCTION — самостоятельный пересказ, а не дословная копия)
# ---------------------------------------------------------------------------

#: Инструкция модели: отвечать строго по документам, не имитировать блоки.
INSTRUCTION = (
    "Отвечай строго по приведённым документам. НЕ выводи собственных секций "
    "„Источники:\" и „Цитаты:\" — их добавит система. Если документов "
    "недостаточно — честно скажи, что не знаешь."
)

#: Фиксированный ответ при пустом результате RAG (0 чанков) — без блоков.
IDK_REPLY = (
    "Не знаю по этому вопросу — в базе знаний нет достаточно релевантных "
    "материалов. Уточните, пожалуйста: о каком разделе или файле идёт речь?"
)

#: Маркер RAG-блока в system-сообщении (как в бэкенде).
RAG_MARKER = "РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ"

#: Ключевые слова-маркеры ограничений в сообщениях пользователя.
CONSTRAINT_KEYWORDS = (
    "только", "используй", "не использовать", "не упоминай",
    "обязательно", "кратко", "подробно", "в стиле",
)

#: Скользящее окно истории для промта (в сообщениях).
HISTORY_WINDOW = 10

#: k для RAG-поиска.
RAG_K = 5

#: Лимит уточнения (символов).
CLARIFICATION_LIMIT = 200

#: Лимит цитаты (символов), обрезка по границе предложения.
QUOTE_LIMIT = 300

#: Таймауты сети, секунды.
RAG_TIMEOUT = 10
LLM_TIMEOUT = 120

#: Промпт REPL.
PROMPT = "ты> "

#: URL мок-LLM и его параметры (флаг --mock).
MOCK_URL = "http://127.0.0.1:18080/v1/chat/completions"
MOCK_KEY = "mock-key"
MOCK_MODEL = "mock-1"

#: Индексатор по умолчанию.
DEFAULT_INDEXER_URL = "http://127.0.0.1:18701/search"


# ---------------------------------------------------------------------------
# HTTP (urllib, без сторонних зависимостей)
# ---------------------------------------------------------------------------

def http_post_json(url, payload, timeout, api_key=None):
    """POST JSON и разобрать ответ как JSON. Бросает исключение при ошибке.

    Аргумент api_key добавляет заголовок Authorization: Bearer <ключ>.
    """
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = "Bearer %s" % api_key
    request = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read().decode("utf-8")
    return json.loads(body)


def rag_search(indexer_url, query, k=RAG_K):
    """RAG-поиск по индексатору. Возвращает список чанков (может быть пустым).

    При сетевой ошибке/таймауте/битом JSON бросает исключение — вызывающий
    код обязан обработать его как fail-open (ход без RAG-блока).
    """
    result = http_post_json(indexer_url, {"query": query, "k": k}, RAG_TIMEOUT)
    if not isinstance(result, list):
        raise ValueError("индексатор вернул не список: %r" % type(result).__name__)
    chunks = []
    for item in result:
        if not isinstance(item, dict):
            continue
        chunks.append({
            "chunk_id": str(item.get("chunk_id", "")),
            "score": float(item.get("score", 0.0)),
            "source": str(item.get("source", "")),
            "title": str(item.get("title", "")),
            "section": str(item.get("section", "")),
            "content": str(item.get("content", "")),
        })
    return chunks


def call_llm(llm_url, api_key, model, messages):
    """OpenAI-совместимый вызов /v1/chat/completions; возвращает текст ответа."""
    payload = {"model": model, "messages": messages, "stream": False}
    parsed = http_post_json(llm_url, payload, LLM_TIMEOUT, api_key=api_key)
    choices = parsed.get("choices") or []
    if not choices:
        raise ValueError("в ответе LLM нет choices")
    message = (choices[0] or {}).get("message") or {}
    content = message.get("content")
    if not isinstance(content, str):
        raise ValueError("в ответе LLM нет текста content")
    return content


# ---------------------------------------------------------------------------
# Сборка промта
# ---------------------------------------------------------------------------

def build_rag_block(chunks):
    """RAG-блок для system-промта: маркер + нумерованные чанки ПОЛНЫМ текстом.

    Формат: «РЕЛЕВАНТНЫЕ ДОКУМЕНТЫ\\n[1] source: <source> · раздел: <section>\\n<content>».
    Пустой список → пустая строка (инжектить нечего).
    """
    if not chunks:
        return ""
    parts = [RAG_MARKER]
    for index, chunk in enumerate(chunks, 1):
        parts.append(
            "[%d] source: %s · раздел: %s\n%s"
            % (index, chunk["source"], chunk["section"], chunk["content"])
        )
    return "\n".join(parts)


def build_memory_block(task_state):
    """Блок «Память задачи:» для system-промта; пустые секции опускаются."""
    lines = ["Память задачи:"]
    if task_state["goal"]:
        lines.append("Цель: %s" % task_state["goal"])
    if task_state["clarifications"]:
        lines.append("Уточнения: %s" % "; ".join(task_state["clarifications"]))
    if task_state["constraints"]:
        lines.append("Ограничения: %s" % "; ".join(task_state["constraints"]))
    # Только заголовок без секций — блока нет вообще.
    if len(lines) == 1:
        return ""
    return "\n".join(lines)


def build_system_prompt(task_state, chunks):
    """System = инструкция + RAG-блок + память задачи (пустые части опускаются)."""
    parts = [INSTRUCTION]
    rag_block = build_rag_block(chunks)
    if rag_block:
        parts.append(rag_block)
    memory_block = build_memory_block(task_state)
    if memory_block:
        parts.append(memory_block)
    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Детерминированные блоки «Источники:» / «Цитаты:» (контракт с бэкендом)
# ---------------------------------------------------------------------------

def truncate_at_sentence(text, limit=QUOTE_LIMIT):
    """Обрезка текста до <= limit символов по границе предложения."""
    if len(text) <= limit:
        return text
    window = text[:limit]
    # Ищем ближайший к концу окна конец предложения (. ! ? …).
    for position in range(len(window) - 1, 0, -1):
        if window[position] in ".!?…":
            return window[:position + 1]
    return window


def sanitize_fragment(text):
    """Санитизация фрагмента цитаты: без ```-фенсов, пробелы схлопнуты, <=300."""
    cleaned = (text or "").replace("```", "")
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return truncate_at_sentence(cleaned)


def build_source_lines(chunks):
    """Строки блока «Источники:»: «[N] <source> · <section> · chunk <id> · <score>»."""
    lines = []
    for index, chunk in enumerate(chunks, 1):
        lines.append(
            "[%d] %s · %s · chunk %s · %.4f"
            % (index, chunk["source"], chunk["section"],
               chunk["chunk_id"], chunk["score"])
        )
    return lines


def build_quote_lines(chunks):
    """Строки блока «Цитаты:»; нумерация [N] — по позиции чанка (как в источниках).

    Чанк с пустым content строки не получает; все пустые → список пуст.
    """
    lines = []
    for index, chunk in enumerate(chunks, 1):
        fragment = sanitize_fragment(chunk["content"])
        if fragment:
            lines.append("[%d] «%s»" % (index, fragment))
    return lines


def assemble_final_reply(reply, chunks):
    """Финальный текст хода: ответ LLM + детерминированные блоки.

    RAG-ошибка (chunks is None) → блоков нет. Ноль цитат → блок «Цитаты:»
    опускается целиком.
    """
    if not chunks:
        return reply
    source_lines = build_source_lines(chunks)
    parts = [reply, "", "Источники:", "\n".join(source_lines)]
    quote_lines = build_quote_lines(chunks)
    if quote_lines:
        parts.append("Цитаты:")
        parts.append("\n".join(quote_lines))
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Сессия: структура, эвристики памяти задачи, атомарная запись
# ---------------------------------------------------------------------------

GOAL_PREFIX_RE = re.compile(r"^\s*цель:\s*(.*)$", re.IGNORECASE | re.DOTALL)


class Session:
    """Состояние сессии мини-чата + атомарное сохранение в JSON."""

    def __init__(self, session_dir, name):
        self.session_dir = session_dir
        self.name = name
        self.path = os.path.join(session_dir, "%s.json" % name)
        self.data = {
            "session_id": name,
            "created_at": datetime.now().astimezone().isoformat(timespec="seconds"),
            "history": [],          # только пары {"role", "content"} user/assistant
            "task_state": {"goal": "", "clarifications": [], "constraints": []},
            "sources_log": [],      # по списку строк «[N] …» на каждый ход с блоками
        }

    # -- загрузка существующего файла -------------------------------------

    def load(self):
        """Подхватить существующую сессию (или остаться на новой)."""
        if not os.path.exists(self.path):
            return False
        try:
            with open(self.path, "r", encoding="utf-8") as handle:
                stored = json.load(handle)
            for key in ("session_id", "created_at", "history", "task_state", "sources_log"):
                if key in stored:
                    self.data[key] = stored[key]
            # Гарантируем структуру task_state даже в урезанных файлах.
            state = self.data.setdefault("task_state", {})
            state.setdefault("goal", "")
            state.setdefault("clarifications", [])
            state.setdefault("constraints", [])
            return True
        except (OSError, ValueError) as error:
            print("Предупреждение: не удалось прочитать сессию (%s); начинаю новую." % error,
                  file=sys.stderr)
            return False

    # -- атомарная запись --------------------------------------------------

    def save(self):
        """АТОМАРНАЯ запись: tmp-файл в той же папке + os.replace.

        kill -9 в любой момент оставляет на диске либо старый, либо новый
        ЦЕЛИКОМ валидный JSON — промежуточных состояний не бывает.
        """
        os.makedirs(self.session_dir, exist_ok=True)
        tmp_path = "%s.%d.tmp" % (self.path, os.getpid())
        with open(tmp_path, "w", encoding="utf-8") as handle:
            json.dump(self.data, handle, ensure_ascii=False, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_path, self.path)

    # -- эвристики памяти задачи -------------------------------------------

    def apply_task_heuristics(self, message, is_first_message):
        """Детерминированное обновление task_state по сообщению пользователя.

        Правила:
          * сообщение вида «цель: …» (первое) фиксирует цель, префикс снимается;
          * ПЕРВОЕ сообщение без префикса становится целью как есть (fallback)
            И попадает в уточнения (оно же — первое содержательное уточнение);
          * все остальные сообщения → уточнения (дословно, <=200 символов);
          * сообщение с ключевым словом ограничения → ещё и в ограничения
            (полным текстом; проверяются ВСЕ сообщения, включая первое).
        Возвращает True, если сообщение зафиксировало цель через «цель: ».
        """
        state = self.data["task_state"]
        match = GOAL_PREFIX_RE.match(message)
        is_goal_declaration = False

        if match and not state["goal"]:
            # Явная декларация цели: префикс «цель: » снимается.
            state["goal"] = match.group(1).strip()
            is_goal_declaration = True
        elif is_first_message and not state["goal"]:
            # Fallback: первое сообщение без префикса становится целью как есть.
            state["goal"] = message
            # …и одновременно это первое уточнение (см. QA-сценарий B).
            state["clarifications"].append(message[:CLARIFICATION_LIMIT])
        else:
            # Последующие сообщения — уточнения, дословно с обрезкой.
            state["clarifications"].append(message[:CLARIFICATION_LIMIT])

        # Ограничения ищутся в любом сообщении, включая декларацию цели.
        lowered = message.lower()
        if any(keyword in lowered for keyword in CONSTRAINT_KEYWORDS):
            state["constraints"].append(message)
        return is_goal_declaration

    def user_message_count(self):
        """Сколько пользовательских сообщений уже в истории (для «первое ли»)."""
        return sum(1 for entry in self.data["history"] if entry.get("role") == "user")


# ---------------------------------------------------------------------------
# REPL
# ---------------------------------------------------------------------------

def run_turn(session, message, cfg):
    """Один ход диалога. Возвращает текст, показанный пользователю (или None).

    Порядок: эвристики → RAG/LLM → блоки → атомарная запись.
    Декларация «цель: …» — служебный ход-подтверждение: без RAG, без LLM,
    без блоков и без записи в sources_log (поэтому в QA-сценарии A
    len(sources_log) == 1 при двух парах истории).
    """
    is_first = session.user_message_count() == 0
    is_goal_declaration = session.apply_task_heuristics(message, is_first)

    if is_goal_declaration:
        ack = ("Принято. Цель задачи зафиксирована: %s\n"
               "Задавайте вопросы — буду отвечать по базе знаний." % session.data["task_state"]["goal"])
        session.data["history"].append({"role": "user", "content": message})
        session.data["history"].append({"role": "assistant", "content": ack})
        session.save()
        return ack

    # -- RAG-поиск каждого «вопросного» хода --------------------------------
    chunks = None          # None = RAG недоступен (fail-open)
    if cfg.force_empty:
        # Только для тестов: считаем результат RAG пустым, сеть не трогаем.
        chunks = []
    else:
        try:
            chunks = rag_search(cfg.indexer_url, message)
        except (urllib.error.URLError, OSError, ValueError) as error:
            print("Предупреждение: RAG-индексатор недоступен (%s) — "
                  "продолжаю ход без документов." % error, file=sys.stderr)
            chunks = None

    # -- пустой RAG → фиксированный ответ, LLM НЕ вызывается ----------------
    if chunks is not None and not chunks:
        session.data["history"].append({"role": "user", "content": message})
        session.data["history"].append({"role": "assistant", "content": IDK_REPLY})
        session.save()
        return IDK_REPLY

    # -- сборка промта: system + окно истории (10 сообщений) + текущий вопрос
    messages = [{"role": "system", "content": build_system_prompt(session.data["task_state"], chunks)}]
    messages.extend(session.data["history"][-HISTORY_WINDOW:])
    messages.append({"role": "user", "content": message})

    try:
        reply = call_llm(cfg.llm_url, cfg.api_key, cfg.model, messages)
    except (urllib.error.URLError, OSError, ValueError) as error:
        # Ошибка LLM: ход не завершён — ничего не пишем в сессию.
        print("Предупреждение: ошибка LLM (%s); ход не сохранён." % error, file=sys.stderr)
        return None

    # Детерминированные блоки (при RAG-ошибке chunks is None → блоков нет).
    final_text = assemble_final_reply(reply, chunks)

    session.data["history"].append({"role": "user", "content": message})
    session.data["history"].append({"role": "assistant", "content": final_text})
    if chunks:
        # sources_log: строки «[N] …» источники каждого хода с блоками.
        session.data["sources_log"].append(build_source_lines(chunks))
    session.save()
    return final_text


def print_task_state(session):
    """Команда /state: человекочитаемое состояние задачи (не ход диалога)."""
    state = session.data["task_state"]
    print("Состояние задачи:")
    print("  Цель: %s" % (state["goal"] or "— не задана —"))
    print("  Уточнения (%d):" % len(state["clarifications"]))
    for item in state["clarifications"]:
        print("    - %s" % item)
    print("  Ограничения (%d):" % len(state["constraints"]))
    for item in state["constraints"]:
        print("    - %s" % item)
    print("  Сообщений в истории: %d" % len(session.data["history"]))
    print("  Ходов с источниками: %d" % len(session.data["sources_log"]))


def start_new_session(session):
    """Команда /new: новая сессия в НОВОМ файле (старый сохраняется)."""
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    session.name = "%s-%s" % (session.name, stamp)
    session.path = os.path.join(session.session_dir, "%s.json" % session.name)
    session.data = {
        "session_id": session.name,
        "created_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "history": [],
        "task_state": {"goal": "", "clarifications": [], "constraints": []},
        "sources_log": [],
    }
    print("Начата новая сессия: %s" % session.path)


def repl(cfg):
    """Главный цикл: «ты> », команды /state /new /exit, ходы диалога."""
    session = Session(cfg.session_dir, cfg.session)
    if session.load():
        print("Сессия «%s» загружена (%d сообщений в истории)." %
              (session.name, len(session.data["history"])))
    else:
        session.save()
        print("Новая сессия: %s" % session.path)
    print("Команды: /state — состояние задачи, /new — новая сессия, /exit — выход.")

    while True:
        try:
            sys.stdout.write(PROMPT)
            sys.stdout.flush()
            raw = sys.stdin.readline()
        except KeyboardInterrupt:
            raw = ""            # Ctrl+C трактуем как выход с сохранением
        if raw == "":           # EOF (Ctrl+D / конец пайпа) = /exit
            line = "/exit"
        else:
            line = raw.strip()
        if not line:
            continue            # пустая строка — не ход

        if line == "/exit":
            session.save()
            print("Сессия сохранена: %s" % session.path)
            break
        if line == "/state":
            print_task_state(session)   # служебная команда, не ход
            continue
        if line == "/new":
            start_new_session(session)  # служебная команда, не ход
            continue
        if line.startswith("/"):
            print("Неизвестная команда: %s (доступны /state, /new, /exit)" % line)
            continue

        shown = run_turn(session, line, cfg)
        if shown is not None:
            print()
            print(shown)
            print()


# ---------------------------------------------------------------------------
# Конфигурация и точка входа
# ---------------------------------------------------------------------------

def load_config_defaults():
    """Значения apiUrl/apiKey/model из config.json в корне репозитория.

    Файл ищется относительно каталога скрипта (../config.json). Отсутствие
    или битый JSON — не ошибка: вернём пустые строки, флаги CLI их перекроют.
    """
    config_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, "config.json")
    defaults = {"apiUrl": "", "apiKey": "", "model": ""}
    try:
        with open(config_path, "r", encoding="utf-8") as handle:
            stored = json.load(handle)
        for field in defaults:
            value = stored.get(field)
            if isinstance(value, str):
                defaults[field] = value
    except (OSError, ValueError):
        pass
    return defaults


def build_parser(config_defaults):
    """Аргументы командной строки (описания — на русском)."""
    parser = argparse.ArgumentParser(
        prog="mini_chat.py",
        description="Мини-чат CLI: REPL с RAG-подсказкой, памятью задачи и "
                    "блоками «Источники:»/«Цитаты:» (контракт 1:1 с бэкендом).",
    )
    parser.add_argument(
        "--mock", action="store_true",
        help="использовать мок-LLM (%s, ключ %s, модель %s) вместо основного" %
             (MOCK_URL, MOCK_KEY, MOCK_MODEL),
    )
    parser.add_argument(
        "--indexer-url", default=DEFAULT_INDEXER_URL,
        help="URL RAG-индексатора (по умолчанию %s)" % DEFAULT_INDEXER_URL,
    )
    parser.add_argument(
        "--llm-url", default=config_defaults["apiUrl"],
        help="URL OpenAI-совместимого /v1/chat/completions "
             "(по умолчанию из config.json: apiUrl)",
    )
    parser.add_argument(
        "--api-key", default=config_defaults["apiKey"],
        help="API-ключ LLM (по умолчанию из config.json: apiKey); "
             "в файл сессии никогда не пишется",
    )
    parser.add_argument(
        "--model", default=config_defaults["model"],
        help="имя модели (по умолчанию из config.json: model)",
    )
    parser.add_argument(
        "--session-dir", default=os.path.join(
            os.path.dirname(os.path.abspath(__file__)), "minichat-sessions"),
        help="каталог файлов сессий (по умолчанию scripts/minichat-sessions, "
             "создаётся по мере необходимости)",
    )
    parser.add_argument(
        "--session", default="default",
        help="имя сессии; файл <каталог>/<имя>.json (по умолчанию «default»)",
    )
    parser.add_argument(
        "--force-empty", action="store_true",
        help="принудительно считать результаты RAG пустыми — только для тестов "
             "(QA-сценарий «не знаю»)",
    )
    return parser


def main(argv=None):
    cfg = build_parser(load_config_defaults()).parse_args(argv)

    # --mock перекрывает URL/ключ/модель целиком.
    if cfg.mock:
        cfg.llm_url = MOCK_URL
        cfg.api_key = MOCK_KEY
        cfg.model = MOCK_MODEL

    if not cfg.llm_url:
        print("Ошибка: не задан URL LLM (ни --llm-url, ни apiUrl в config.json, ни --mock).",
              file=sys.stderr)
        return 2
    if not cfg.model:
        print("Ошибка: не задана модель (ни --model, ни model в config.json, ни --mock).",
              file=sys.stderr)
        return 2

    try:
        repl(cfg)
    except BrokenPipeError:
        # stdout закрыт (например, | head) — тихо завершаемся.
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
