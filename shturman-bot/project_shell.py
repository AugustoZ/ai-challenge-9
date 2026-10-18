"""Шелл-тул «мини-агента»: белый список, классификация, исполнение.

Модель запрашивает команды маркером ``<shell>команда</shell>``. Безопасность:

- команды из белого списка (config.json ``agent.shell_allow`` или дефолт)
  классифицируются как ``allow`` — исполняются сразу, вывод возвращается
  модели;
- всё прочее — ``gate`` (в т.ч. команды с шелл-метасимволами ``| && ; > ``
  ``$`` — их нельзя верифицировать по первому токену): копятся в сессии
  и исполняются только после подтверждения «применяй» пользователем;
- ``git commit``/``git push`` и команды с аргументами-секретами
  (``config.json``, ``.env``) — ``denied`` всегда, в гейт не попадают
  (правило «агент не коммитит»; белый список не канал утечки секретов).

Исполнение — ``subprocess`` БЕЗ ``shell=True`` (разбор ``shlex.split``),
cwd — корень проекта на каждый запуск, таймаут с убийством процесса,
вывод обрезается до капа. Команды не получают env секретов от бота —
наследуют окружение процесса бота (секреты модели — в config.json, его
шеллу читать запрещено классификацией).
"""

from __future__ import annotations

import os
import re
import shlex
import subprocess

#: Дефолтный белый список: чтение репозитория, тесты, компиляция, ветки.
#: Формат записи — префикс токенов команды (``shlex.split``): запись
#: ``git status`` матчит только ``git status …``, ``npm run`` — любые
#: ``npm run <скрипт>``.
DEFAULT_SHELL_ALLOW: tuple[str, ...] = (
    "git status",
    "git log",
    "git diff",
    "git branch",
    "git checkout",
    "git switch",
    "git stash list",
    "ls",
    "grep",
    "find",
    "wc",
    "npm test",
    "npm run",
    "./gradlew test",
    "./gradlew check",
    "python -m py_compile",
    "python3 -m py_compile",
)

#: Имена файлов-секретов: аргумент в команде — отказ (denied).
SECRET_BASENAMES = ("config.json", ".env")

#: Шелл-метасимволы: команда с ними не верифицируется по первому токену.
_METACHAR_RE = re.compile(r"[|&;><`$\n]")

#: Максимальная длина команды от модели.
MAX_CMD_CHARS = 500

#: Таймаут исполнения одной команды, сек.
DEFAULT_TIMEOUT_S = 120

#: Кап вывода команды в контекст модели.
MAX_OUTPUT_CHARS = 4000

ALLOW = "allow"
GATE = "gate"
DENIED = "denied"


def parse_allow(config: dict | None) -> list[str]:
    """Белый список из config.json (секция ``agent.shell_allow``) или дефолт.

    Некорректные записи (не строки, пустые) пропускаются; пустой список
    в конфиге НЕ заменяет дефолт (пустой allow сделал бы шелл мёртвым) —
    чтобы полностью выключить автоматическое исполнение, секцию убирают
    вместе с вызовом тула.
    """
    section = (config or {}).get("agent") if isinstance(config, dict) else None
    raw = section.get("shell_allow") if isinstance(section, dict) else None
    if not isinstance(raw, list):
        return list(DEFAULT_SHELL_ALLOW)
    entries = [str(item).strip() for item in raw if str(item or "").strip()]
    return entries or list(DEFAULT_SHELL_ALLOW)


def split_tokens(cmd: str) -> list[str] | None:
    """Токены команды (shlex.split) или None, если разбор невозможен."""
    try:
        return shlex.split(cmd or "")
    except ValueError:
        return None


def has_metachars(cmd: str) -> bool:
    """Шелл-метасимволы в команде — верификация по токенам невозможна."""
    return bool(_METACHAR_RE.search(cmd or ""))


def references_secret(cmd: str) -> bool:
    """Аргумент-секрет в команде: config.json / .env (в т.ч. с путём)."""
    tokens = split_tokens(cmd) or []
    for token in tokens[1:] or []:
        base = os.path.basename(token)
        if base == "config.json" or base == ".env" or base.startswith(".env."):
            return True
    return False


def _is_denied_git(tokens: list[str]) -> bool:
    """git commit / git push — агент не коммитит (гардрейл проекта)."""
    return len(tokens) >= 2 and tokens[0] == "git" and tokens[1] in ("commit", "push")


def classify(cmd: str, allow: list[str]) -> str:
    """Класс команды: ``allow`` (исполнить сразу) / ``gate`` / ``denied``.

    Порядок проверок важен: секрет и commit/push — отказ даже если
    входят в белый список; метасимволы и неразбираемая команда — гейт
    (не allow и не denied — решать пользователю).
    """
    cmd = (cmd or "").strip()
    if not cmd or len(cmd) > MAX_CMD_CHARS:
        return GATE
    tokens = split_tokens(cmd)
    if references_secret(cmd) or (tokens and _is_denied_git(tokens)):
        return DENIED
    if tokens is None or has_metachars(cmd):
        return GATE
    for entry in allow or []:
        entry_tokens = split_tokens(entry)
        if entry_tokens and tokens[: len(entry_tokens)] == entry_tokens:
            return ALLOW
    return GATE


def _cap_output(text: str, max_chars: int = MAX_OUTPUT_CHARS) -> str:
    if len(text) <= max_chars:
        return text
    return text[:max_chars] + "\n… (вывод обрезан)"


def run(cmd: str, cwd: str, timeout_s: int = DEFAULT_TIMEOUT_S,
        max_chars: int = MAX_OUTPUT_CHARS) -> dict:
    """Исполнить команду в cwd. Возвращает {ok, output, code, error}.

    Без ``shell=True``: аргументы — уже разобранные токены. Таймаут и
    отсутствие бинарника — не исключения, а поля результата (цикл агента
    не должен падать на команде модели).
    """
    cmd = (cmd or "").strip()
    tokens = split_tokens(cmd)
    if not tokens:
        return {"ok": False, "output": "", "code": None,
                "error": "пустая или неразбираемая команда"}
    try:
        proc = subprocess.run(  # noqa: S603 - токены без shell=True
            tokens, cwd=cwd, capture_output=True, text=True,
            timeout=timeout_s,
        )
    except subprocess.TimeoutExpired:
        return {"ok": False, "output": "", "code": None,
                "error": f"команда превысила таймаут {timeout_s} с"}
    except FileNotFoundError:
        return {"ok": False, "output": "", "code": None,
                "error": f"команда не найдена: {tokens[0]}"}
    except OSError as exc:
        return {"ok": False, "output": "", "code": None,
                "error": f"ошибка запуска: {exc}"}
    out = proc.stdout or ""
    if proc.stderr:
        out += ("\n[stderr]\n" + proc.stderr) if out else proc.stderr
    return {
        "ok": proc.returncode == 0,
        "output": _cap_output(out.strip(), max_chars),
        "code": proc.returncode,
        "error": None,
    }
