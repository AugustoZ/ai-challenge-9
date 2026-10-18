"""Состояние и стейт-машина «мини-агента» Telegram-бота Штурман.

Модуль без внешних зависимостей и без сети: сессия агента per-user
(``AgentSession``), FSM-состояния, TTL-истечение, реестр сессий,
разбор каталога моделей из config.json и ЧИСТЫЙ парсер текстовых
маркеров, которыми модель оформляет чтение/правки файлов.

Протокол маркеров (эта строка документации — основа системного промпта):

- ``<read path="относительный/путь">`` — запрос на чтение файла проекта
  (закрывающий ``</read>`` допустим и игнорируется; маркер валиден и БЕЗ
  закрывающего тега — read не имеет тела);
- ``<write path="файл">полный текст файла</write>`` — перезаписать файл
  целиком;
- ``<patch path="файл">старый фрагмент
  =====
  новый фрагмент</patch>`` — точечная замена: СНАЧАЛА старый фрагмент,
  затем строка из пяти и более символов ``=`` (``=====``) на отдельной
  строке, затем новый фрагмент;
- ``<report>итог работы</report>`` — сводка для отчёта пользователю.
- ``<shell>команда</shell>`` — запустить команду в корне проекта (тег
  С телом; исполнение и безопасность — в project_shell.py, парсер только
  извлекает команду).

Толерантность парсера: значение ``path`` — в двойных кавычках, одинарных
или вовсе без кавычек; пробелы и переносы строк вокруг тегов допускаются.
Незакрытый маркер (открывающий тег есть, закрывающего нет) игнорируется
ЦЕЛИКОМ — частичное содержимое не возвращается. Вложенность тегов одного
типа не обрабатывается (по договорённости её не возникает).

API-ключ модели здесь НЕ резолвится: хранится только имя переменной
окружения (``api_key_env``); само значение ключа не читается, не хранится
и не логируется.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field

# -----------------------------------------------------------------------
# 1. Состояния FSM
# -----------------------------------------------------------------------

IDLE = "idle"                    # сессии нет / сброшена
TASK_SET = "task_set"            # задача принята, идёт выбор модели
MODEL_SELECT = "model_select"    # модель выбрана, агент готовится
EXECUTE = "execute"              # агентный цикл: чтение/подготовка правок
AWAIT_CONFIRM = "await_confirm"  # правки показаны, ждём «применяй»/«отмена»
REPORT = "report"                # правки применены, отчёт отправляется
FOLLOWUP = "followup"            # отчёт без правок выдан: «спека»/«делай»
                                 # продолжают задачу с контекстом отчёта

#: Все допустимые состояния FSM (frozenset — для проверок переходов).
STATES = frozenset({IDLE, TASK_SET, MODEL_SELECT, EXECUTE, AWAIT_CONFIRM,
                    REPORT, FOLLOWUP})

#: TTL неактивной сессии: по истечении сессия считается отсутствующей.
SESSION_TTL_SEC = 3600

#: Кап лога шагов (старые записи вытесняются).
STEPS_LOG_CAP = 50

#: Таймаут py_compile одного файла, сек.
PY_COMPILE_TIMEOUT = 30


def set_state(session: "AgentSession", state: str) -> None:
    """Перевод сессии в состояние ``state`` с проверкой допустимости.

    Неизвестное состояние — ValueError (опечатка в FSM не должна молча
    создать невалидную сессию). Перевод обновляет ``updated_at``.
    """
    if state not in STATES:
        raise ValueError(f"Неизвестное состояние FSM: {state!r}")
    session.state = state
    touch(session)


# -----------------------------------------------------------------------
# 2. Сессия агента
# -----------------------------------------------------------------------


@dataclass
class AgentSession:
    """Состояние «мини-агента» одного пользователя (per-user, in-memory).

    ``task`` хранит ПОЛНЫЙ текст задачи — не обрезается. ``history`` —
    пары вопрос/ответ в формате messages (``{"role", "content"}``).
    ``pending_edits`` — правки, ожидающие гейта подтверждения, каждая:
    ``{"path", "kind": "write"|"patch", "new_text", "old_text"|None}``
    (для ``write`` ``old_text`` всегда None). ``applied`` — пути файлов,
    успешно записанных на диск после подтверждения. ``pending_commands`` —
    шелл-команды вне белого списка (project_shell.classify → gate):
    исполняются только после «применяй».
    """

    user_id: int
    chat_id: int
    state: str = IDLE
    task: str = ""
    steps_log: list[str] = field(default_factory=list)
    history: list[dict] = field(default_factory=list)
    pending_edits: list[dict] = field(default_factory=list)
    pending_commands: list[str] = field(default_factory=list)
    applied: list[str] = field(default_factory=list)
    # Отчёт аналитической фазы (report без правок): контекст для FOLLOWUP —
    # «спека»/«делай» продолжают задачу, не начиная анализ заново.
    last_report: str = ""
    # Результаты шелл-команд, исполненных после «применяй» без правок:
    # агент продолжает работу с этим выводом (аналитические задачи).
    command_outputs: list[dict] = field(default_factory=list)
    model_id: str = "local"
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


def new_session(user_id: int, chat_id: int, task: str = "") -> AgentSession:
    """Новая сессия в состоянии IDLE с проставленными таймстампами."""
    now = time.time()
    return AgentSession(
        user_id=user_id, chat_id=chat_id, task=task,
        created_at=now, updated_at=now,
    )


def touch(session: AgentSession) -> None:
    """Обновить ``updated_at`` (продлить TTL сессии)."""
    session.updated_at = time.time()


# -----------------------------------------------------------------------
# 3. TTL
# -----------------------------------------------------------------------


def is_expired(session: AgentSession, now: float | None = None) -> bool:
    """Истекла ли сессия: с момента ``updated_at`` прошло больше TTL."""
    current = time.time() if now is None else now
    return (current - session.updated_at) > SESSION_TTL_SEC


def active(session: AgentSession, now: float | None = None) -> bool:
    """Сессия активна: состояние не IDLE и TTL не истёк."""
    return session.state != IDLE and not is_expired(session, now)


# -----------------------------------------------------------------------
# 4. Реестр сессий
# -----------------------------------------------------------------------


class SessionRegistry:
    """In-memory реестр сессий ``{user_id: AgentSession}``.

    Потокобезопасность не нужна: asyncio-воркер бота однопоточный.
    ``get`` возвращает ТОЛЬКО активную сессию — истёкшая или сброшенная
    (IDLE) считается отсутствующей и вытесняется из реестра.
    """

    def __init__(self) -> None:
        self._sessions: dict[int, AgentSession] = {}

    def get(self, user_id: int) -> AgentSession | None:
        """Активная сессия пользователя или None (истёкшая = отсутствует)."""
        session = self._sessions.get(user_id)
        if session is None:
            return None
        if not active(session):
            del self._sessions[user_id]
            return None
        return session

    def set(self, session: AgentSession) -> None:
        """Сохранить/заменить сессию пользователя."""
        self._sessions[session.user_id] = session

    def drop(self, user_id: int) -> None:
        """Удалить сессию пользователя, если была (отсутствие — не ошибка)."""
        self._sessions.pop(user_id, None)


# -----------------------------------------------------------------------
# 5. Модельный каталог (секция agent.models config.json)
# -----------------------------------------------------------------------

#: Значения по умолчанию для локальной модели (id == "local").
LOCAL_MODEL_DEFAULTS: dict = {
    "id": "local",
    "label": "Локальная (qwen3.5-9b)",
    "api_base": "http://localhost:1234/v1",
    "model": "qwen3.5-9b-mlx",
    "api_key_env": "",
    "timeout_sec": 120,
    "max_tokens": 2000,
    "frequency_penalty": 0.3,
}

#: Дефолты бюджетов для облачных моделей (api_base/model — из записи).
CLOUD_MODEL_DEFAULTS: dict = {
    "timeout_sec": 300,
    "max_tokens": 256000,
    "frequency_penalty": 0.0,
}


def _env_or(env: dict, key: str, fallback: str) -> str:
    value = str(env.get(key) or "").strip()
    return value or fallback


def _as_int(value: object, fallback: int) -> int:
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return fallback


def _as_float(value: object, fallback: float) -> float:
    try:
        return float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return fallback


def _local_default_record(env: dict) -> dict:
    """Локальная модель со всеми дефолтами (env ``LM_API_BASE``/``LM_MODEL``)."""
    return {
        "id": LOCAL_MODEL_DEFAULTS["id"],
        "label": LOCAL_MODEL_DEFAULTS["label"],
        "api_base": _env_or(env, "LM_API_BASE", LOCAL_MODEL_DEFAULTS["api_base"]),
        "model": _env_or(env, "LM_MODEL", LOCAL_MODEL_DEFAULTS["model"]),
        "api_key_env": "",
        "timeout_sec": LOCAL_MODEL_DEFAULTS["timeout_sec"],
        "max_tokens": LOCAL_MODEL_DEFAULTS["max_tokens"],
        "frequency_penalty": LOCAL_MODEL_DEFAULTS["frequency_penalty"],
    }


def _model_record(item: dict, env: dict) -> dict | None:
    """Запись каталога из элемента секции agent.models или None (битая).

    Обязательны ``id`` и ``model``; недостающие поля берутся из дефолтов
    (локальная модель — с подстановкой env ``LM_API_BASE``/``LM_MODEL``).
    """
    model_id = str(item.get("id") or "").strip()
    model = str(item.get("model") or "").strip()
    if not model_id or not model:
        return None
    is_local = model_id == LOCAL_MODEL_DEFAULTS["id"]
    defaults = dict(LOCAL_MODEL_DEFAULTS) if is_local else {
        **{k: LOCAL_MODEL_DEFAULTS[k] for k in ("label", "api_base", "api_key_env")},
        **CLOUD_MODEL_DEFAULTS,
    }
    if is_local:
        # api_base/model локальной модели переопределяются env-ом
        # (env имеет приоритет над дефолтом, но не над явной записью).
        defaults["api_base"] = _env_or(env, "LM_API_BASE", LOCAL_MODEL_DEFAULTS["api_base"])
        defaults["model"] = _env_or(env, "LM_MODEL", LOCAL_MODEL_DEFAULTS["model"])
    defaults["model"] = model
    return {
        "id": model_id,
        "label": str(item.get("label") or defaults["label"]),
        "api_base": str(item.get("api_base") or defaults["api_base"]),
        "model": model,
        "api_key_env": str(item.get("api_key_env") or defaults["api_key_env"]),
        "timeout_sec": _as_int(item.get("timeout_sec"), defaults["timeout_sec"]),
        "max_tokens": _as_int(item.get("max_tokens"), defaults["max_tokens"]),
        "frequency_penalty": _as_float(
            item.get("frequency_penalty"), defaults["frequency_penalty"]),
    }


def parse_models(config: dict, env: dict | None = None) -> list[dict]:
    """Каталог моделей агента из распарсенного config.json бота.

    Секция ``config["agent"]["models"]``; у каждой записи обязательны
    ``id`` и ``model`` — записи без них пропускаются. Недостающие поля
    получают дефолты: для локальной (``id == "local"``) —
    ``LOCAL_MODEL_DEFAULTS`` с подстановкой env ``LM_API_BASE``/``LM_MODEL``,
    для облачных — ``CLOUD_MODEL_DEFAULTS`` (api_base — из записи либо
    локальный дефолт). ``env`` эмулирует os.environ (для смоков); ключ
    API не резолвится — только имя переменной в ``api_key_env``.

    Если секции нет (или это не список) — одна локальная запись со всеми
    дефолтами. Если секция есть, но валидных записей не осталось —
    пустой список (конфиг явно сломан, молча подменять его не честно).
    """
    env_map: dict = dict(os.environ) if env is None else env
    section = config.get("agent") if isinstance(config, dict) else None
    raw = section.get("models") if isinstance(section, dict) else None
    if not isinstance(raw, list):
        return [_local_default_record(env_map)]
    out: list[dict] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        record = _model_record(item, env_map)
        if record is not None:
            out.append(record)
    return out


# -----------------------------------------------------------------------
# 6. Парсер маркеров (чистые функции)
# -----------------------------------------------------------------------

_TAG_RE = re.compile(r"<(read|write|patch|report|shell)\b([^>]*)>", re.IGNORECASE | re.DOTALL)
_PATH_RE = re.compile(r"""\bpath\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'<>]+))""")
# Разделитель patch: строка из 5+ символов '=' (документиров).
_PATCH_SEP_RE = re.compile(r"^[ \t]*={5,}[ \t]*$", re.MULTILINE)
_EDIT_TAG_RE = re.compile(r"<(?:write|patch)\b", re.IGNORECASE)


def _attr_path(attrs: str) -> str:
    """Значение path из атрибутов тега: "x", 'x' или без кавычек."""
    match = _PATH_RE.search(attrs)
    if not match:
        return ""
    value = next(group for group in match.groups() if group is not None)
    return value.strip()


def _strip_marker_body(body: str) -> str:
    """Убрать ровно по одному переносу строки в начале/конце тела маркера.

    Модель обычно ставит перенос сразу после открывающего тега и перед
    закрывающим; внутренние переносы и отступы содержимого не трогаем.
    """
    if body.startswith("\r\n"):
        body = body[2:]
    elif body.startswith("\n"):
        body = body[1:]
    if body.endswith("\r\n"):
        body = body[:-2]
    elif body.endswith("\n"):
        body = body[:-1]
    return body


def parse_markers(text: str) -> list[dict]:
    """Маркеры ответа LLM в порядке следования.

    Возвращает записи: ``{"kind": "read", "path"}``,
    ``{"kind": "write", "path", "text"}``,
    ``{"kind": "patch", "path", "old_text", "new_text"}``,
    ``{"kind": "report", "text"}``. Для patch старый и новый фрагменты
    разделены строкой ``=====`` (5+ символов '=' на отдельной строке);
    patch без разделителя считается битым и пропускается целиком.
    Незакрытый write/patch/report игнорируется целиком; незакрытый read
    валиден (тег без тела, часто без закрывающего — см. докстринг модуля).
    """
    out: list[dict] = []
    pos = 0
    while True:
        match = _TAG_RE.search(text or "", pos)
        if not match:
            break
        kind = match.group(1).lower()
        if kind == "read":
            # Read — тег БЕЗ тела: закрывающий не ищем вовсе (модель пишет
            # и с </read>, и без него; чужой закрывающий тег захватывать
            # нельзя). Сканируем дальше от конца открывающего.
            path = _attr_path(match.group(2) or "")
            if path:
                out.append({"kind": "read", "path": path})
            pos = match.end()
            continue
        close = f"</{kind}>"
        end_idx = text.find(close, match.end())
        if end_idx == -1:
            # Незакрытый write/patch/report — частичное содержимое не
            # возвращается, пропускаем целиком.
            pos = match.end()
            continue
        body = text[match.end():end_idx]
        pos = end_idx + len(close)
        path = _attr_path(match.group(2) or "")
        if kind == "read":
            if path:
                out.append({"kind": "read", "path": path})
        elif kind == "write":
            if path:
                out.append({"kind": "write", "path": path, "text": _strip_marker_body(body)})
        elif kind == "patch":
            sep = _PATCH_SEP_RE.search(body) if path else None
            if sep:
                out.append({
                    "kind": "patch", "path": path,
                    "old_text": _strip_marker_body(body[:sep.start()]),
                    "new_text": _strip_marker_body(body[sep.end():]),
                })
        elif kind == "report":  # путь не нужен
            out.append({"kind": "report", "text": body.strip()})
        elif kind == "shell":
            cmd = body.strip()
            if cmd:
                out.append({"kind": "shell", "cmd": cmd})
    return out


def has_edit_markers(text: str) -> bool:
    """Есть ли в тексте правки (write/patch) — быстрый ответ без разбора."""
    return bool(_EDIT_TAG_RE.search(text or ""))


# -----------------------------------------------------------------------
# 7. Лог шагов
# -----------------------------------------------------------------------


def log_step(session: AgentSession, text: str) -> None:
    """Добавить строку в лог шагов с префиксом состояния (кап 50 записей).

    Лог собирает, что агент читал/правил, отказы по путям и результаты
    проверок; переиспользуется в отчёте и-инжектится в контекст.
    """
    session.steps_log.append(f"[{session.state}] {text}")
    overflow = len(session.steps_log) - STEPS_LOG_CAP
    if overflow > 0:
        del session.steps_log[:overflow]
    touch(session)


# -----------------------------------------------------------------------
# 8. Верификация применённых правок
# -----------------------------------------------------------------------


def verify_edits(project_root: str, session: AgentSession) -> list[dict]:
    """Проверить синтаксис применённых файлов из ``session.applied``.

    Для ``.py`` — ``python -m py_compile <файл>`` (интерпретатор —
    ``sys.executable``, таймаут 30 с). Для ``.ts``/``.tsx`` tsc здесь НЕ
    запускается (проверяется на верхнем уровне) — запись помечается
    ``check: "skipped"``; прочие расширения помечаются так же. Результат
    на файл: ``{"path", "ok": bool, "error": str, "check": str}``.
    """
    results: list[dict] = []
    for rel in session.applied or []:
        rel_norm = str(rel or "").strip()
        ext = os.path.splitext(rel_norm)[1].lower()
        if ext != ".py":
            results.append({"path": rel, "ok": True, "error": "", "check": "skipped"})
            continue
        full = os.path.join(project_root, rel_norm)
        if not os.path.isfile(full):
            results.append({"path": rel, "ok": False, "error": "Файл не найден.",
                            "check": "py_compile"})
            continue
        try:
            proc = subprocess.run(
                [sys.executable, "-m", "py_compile", full],
                capture_output=True, text=True, timeout=PY_COMPILE_TIMEOUT,
            )
        except subprocess.TimeoutExpired:
            results.append({"path": rel, "ok": False,
                            "error": f"py_compile не уложился в {PY_COMPILE_TIMEOUT} с.",
                            "check": "py_compile"})
            continue
        except OSError as exc:
            results.append({"path": rel, "ok": False,
                            "error": f"Не удалось запустить py_compile: {exc}",
                            "check": "py_compile"})
            continue
        ok = proc.returncode == 0
        error = "" if ok else (proc.stderr or proc.stdout or "Ошибка компиляции.").strip()
        results.append({"path": rel, "ok": ok, "error": error, "check": "py_compile"})
    return results
