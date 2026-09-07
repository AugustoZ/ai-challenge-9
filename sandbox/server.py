#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Sandbox-сервер инструментов с эффектами (P2, волна 1).

Исполняет Python-код и читает файлы ТОЛЬКО из рабочей папки /sandbox/workspace.
Только stdlib Python 3.12 (в контейнере) / 3.9+ (для локальных тестов).

HTTP-контракт (общий с Kotlin-клиентом оркестратора):
  POST /run  {"language":"python","code":"...","timeoutSeconds":15}
             -> 200 {"ok":true,"exitCode":0,"stdout":"...","stderr":"...","durationMs":123}
             -> 200 {"ok":false,"error":"человекочитаемый текст"}
  POST /read {"path":"report.txt"}
             -> 200 {"ok":true,"content":"...","truncated":false}
             -> 200 {"ok":false,"error":"..."}
  Некорректный JSON / отсутствие обязательного поля -> 400 {"ok":false,"error":"Некорректный запрос"}
  Неизвестный путь -> 404 {"ok":false,"error":"Не найдено"}

Модель безопасности:
  - запуск от non-root (uid 65534) на уровне образа/compose;
  - код исполняется как отдельный процесс (python3 -c <code>) в новой сессии
    (start_new_session=True) -> при таймауте убивается ВСЯ группа процессов;
  - resource-лимиты на дочерний процесс: RLIMIT_AS 512 МиБ, RLIMIT_CPU
    (таймаут+2 с), RLIMIT_FSIZE 10 МиБ, RLIMIT_NPROC 64;
  - минимальное окружение (PATH, HOME=/tmp, PYTHONDONTWRITEBYTECODE, LANG);
  - /read: только файлы внутри workspace — запрещены абсолютные пути, любые
    компоненты ".." и симлинки, разрешающиеся наружу workspace;
  - капы: тело запроса 1 МиБ, stdout/stderr 64 КиБ каждый, файл 2 МиБ.
"""

import json
import os
import resource
import signal
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = "0.0.0.0"
PORT = int(os.environ.get("SANDBOX_PORT", "8080"))

# Рабочая папка: единственное место, куда код может писать и откуда можно читать.
WORKSPACE = Path(os.environ.get("SANDBOX_WORKSPACE", "/sandbox/workspace")).resolve()

MAX_BODY_BYTES = 1 * 1024 * 1024        # кап тела запроса
MAX_OUTPUT_BYTES = 64 * 1024            # кап stdout/stderr
MAX_FILE_BYTES = 2 * 1024 * 1024        # кап размера читаемого файла
MAX_TIMEOUT_SECONDS = 60                # жёсткий серверный потолок таймаута
DEFAULT_TIMEOUT_SECONDS = 15            # если timeoutSeconds не передан

CHILD_MEM_LIMIT = 512 * 1024 * 1024     # RLIMIT_AS
CHILD_FSIZE_LIMIT = 10 * 1024 * 1024    # RLIMIT_FSIZE
CHILD_NPROC_LIMIT = 64                  # RLIMIT_NPROC

TRUNCATION_MARKER = (
    "\n... [вывод усечён: превышен лимит 64 КиБ] ...\n"
)

MSG_BAD_REQUEST = "Некорректный запрос"
MSG_NOT_FOUND = "Не найдено"
MSG_ONLY_PYTHON = "Поддерживается только python"
MSG_TIMEOUT = "Превышен таймаут выполнения ({seconds}s)"
MSG_PATH_ESCAPES = "Путь вне рабочей папки запрещён"
MSG_FILE_NOT_FOUND = "Файл не найден"
MSG_SPAWN_FAILED = "Не удалось запустить процесс: {detail}"


def ensure_workspace():
    """Создаёт рабочую папку, если её ещё нет (идемпотентно)."""
    WORKSPACE.mkdir(parents=True, exist_ok=True)


def set_child_limits(timeout_seconds):
    """preexec_fn: resource-лимиты и окружение безопасности дочернего процесса.

    Выполняется в дочернем процессе после fork() до exec(). Каждый лимит
    оборачиваем в try/except: на некоторых платформах (macOS) отдельные
    лимиты могут не применяться — это не повод ломать исполнение.
    """
    # Плохой ввод таймаута уже отброшен валидацией, но страхуемся.
    cpu_limit = max(int(timeout_seconds) + 2, 2)

    def apply():
        try:
            # Виртуальная память (адресное пространство): ~512 МиБ.
            resource.setrlimit(resource.RLIMIT_AS, (CHILD_MEM_LIMIT, CHILD_MEM_LIMIT))
        except Exception:
            pass
        try:
            # CPU-время: таймаут + 2 с запаса, чтобы успел сработать таймаут стеночных часов.
            resource.setrlimit(resource.RLIMIT_CPU, (cpu_limit, cpu_limit))
        except Exception:
            pass
        try:
            # Размер создаваемых файлов: 10 МиБ.
            resource.setrlimit(resource.RLIMIT_FSIZE, (CHILD_FSIZE_LIMIT, CHILD_FSIZE_LIMIT))
        except Exception:
            pass
        try:
            # Число процессов на пользователя: защита от fork-бомб.
            resource.setrlimit(resource.RLIMIT_NPROC, (CHILD_NPROC_LIMIT, CHILD_NPROC_LIMIT))
        except Exception:
            pass

    return apply


def child_env():
    """Минимальное окружение для дочернего процесса (без утечки секретов сервера)."""
    return {
        "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
        "HOME": "/tmp",
        "PYTHONDONTWRITEBYTECODE": "1",
        "LANG": os.environ.get("LANG", "C.UTF-8"),
    }


def _cap_output(raw):
    """Декодирует вывод, обрезая до 64 КиБ с маркерной строкой при превышении."""
    if len(raw) > MAX_OUTPUT_BYTES:
        text = raw[:MAX_OUTPUT_BYTES].decode("utf-8", errors="replace")
        return text + TRUNCATION_MARKER
    return raw.decode("utf-8", errors="replace")


def run_python(code, timeout_seconds):
    """Исполняет код: python3 -c <code>, CWD=workspace, группа процессов под контролем.

    Возвращает dict ответа /run. ok=True означает, что исполнение состоялось
    (даже с ненулевым exitCode); ok=False — ошибка уровня песочницы
    (таймаут, синтаксическая ошибка, невозможность запуска).
    """
    # Синтаксическую ошибку ловим ДО запуска: python3 -c вернул бы exit 1,
    # но по контракту это ошибка песочницы (ok=false), а не exitCode клиента.
    try:
        compile(code, "<sandbox>", "exec")
    except SyntaxError as exc:
        return {"ok": False, "error": "Синтаксическая ошибка: {0}".format(exc.msg)}

    started = time.monotonic()
    try:
        proc = subprocess.Popen(
            [sys.executable, "-c", code],
            cwd=str(WORKSPACE),
            env=child_env(),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            # Новая сессия -> дочерний процесс становится лидером группы:
            # при таймауте убиваем killpg() всю группу (включая внуков).
            start_new_session=True,
            preexec_fn=set_child_limits(timeout_seconds),
        )
    except Exception as exc:  # любой сбой запуска не должен ронять сервер
        return {"ok": False, "error": MSG_SPAWN_FAILED.format(detail=repr(exc))}

    timed_out = False
    try:
        stdout_raw, stderr_raw = proc.communicate(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        timed_out = True
        # Убиваем всю группу процессов (пид лидера = pgid из-за start_new_session).
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError, OSError):
            pass
        try:
            stdout_raw, stderr_raw = proc.communicate(timeout=5)
        except Exception:
            stdout_raw, stderr_raw = b"", b""

    duration_ms = int((time.monotonic() - started) * 1000)

    if timed_out:
        return {
            "ok": False,
            "error": MSG_TIMEOUT.format(seconds=int(timeout_seconds)),
            "durationMs": duration_ms,
        }

    return {
        "ok": True,
        "exitCode": proc.returncode,
        "stdout": _cap_output(stdout_raw or b""),
        "stderr": _cap_output(stderr_raw or b""),
        "durationMs": duration_ms,
    }


def read_workspace_file(raw_path):
    """Читает файл из workspace c защитой от выхода за её пределы.

    Запрещено: абсолютные пути, любые компоненты "..", симлинки,
    разрешающиеся наружу workspace. Содержимое декодируется как UTF-8
    с заменой некорректных байтов; кап 2 МиБ (truncated=true).
    """
    if not isinstance(raw_path, str) or not raw_path.strip():
        return {"ok": False, "error": MSG_PATH_ESCAPES}

    # 1) Абсолютный путь — сразу отказ.
    candidate = Path(raw_path)
    if candidate.is_absolute():
        return {"ok": False, "error": MSG_PATH_ESCAPES}

    # 2) Любой компонент ".." — отказ (даже если итог остался бы внутри).
    if any(part == ".." for part in candidate.parts):
        return {"ok": False, "error": MSG_PATH_ESCAPES}

    target = WORKSPACE / candidate

    # 3) Проверка каждого компонента на симлинк: если ссылка (в т.ч. на
    #    промежуточном уровне) уводит наружу workspace — отказ. Внутренние
    #    симлинки разрешены: финальная проверка resolve() их пропускает.
    current = WORKSPACE
    try:
        for part in candidate.parts:
            current = current / part
            if current.is_symlink():
                resolved_link = current.resolve()
                resolved_link.relative_to(WORKSPACE)  # бросит ValueError, если наружу
    except (ValueError, OSError):
        return {"ok": False, "error": MSG_PATH_ESCAPES}

    # 4) Финальная проверка: канонический путь обязан остаться внутри workspace
    #    (страховка от любых обходных путей, например внутренних симлинков-цепочек).
    try:
        resolved = target.resolve()
        resolved.relative_to(WORKSPACE)
    except (ValueError, OSError):
        return {"ok": False, "error": MSG_PATH_ESCAPES}

    if not resolved.is_file():
        return {"ok": False, "error": MSG_FILE_NOT_FOUND}

    try:
        with open(resolved, "rb") as fh:
            data = fh.read(MAX_FILE_BYTES + 1)
    except OSError:
        return {"ok": False, "error": MSG_FILE_NOT_FOUND}

    truncated = len(data) > MAX_FILE_BYTES
    if truncated:
        data = data[:MAX_FILE_BYTES]
    return {
        "ok": True,
        "content": data.decode("utf-8", errors="replace"),
        "truncated": truncated,
    }


class SandboxHandler(BaseHTTPRequestHandler):
    """Обработчик запросов: любой сбой внутри превращается в JSON-ответ,
    сервер никогда не падает из-за одного запроса."""

    server_version = "ToolSandbox/1.0"
    protocol_version = "HTTP/1.1"

    # --- служебное -------------------------------------------------------

    def log_message(self, fmt, *args):  # тихий лог: не мусорим в stdout контейнера
        sys.stderr.write("[sandbox] %s - %s\n" % (self.address_string(), fmt % args))

    def _send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _send_bad_request(self):
        self._send_json(400, {"ok": False, "error": MSG_BAD_REQUEST})
        # После 400 закрываем соединение: клиент мог не дочитать наш ответ,
        # пока ещё отправляет тело, которое мы отвергаем.
        self.close_connection = True

    @staticmethod
    def _drain(rfile, length):
        """Дочитывает и отбрасывает тело запроса (максимум 16 МиБ), чтобы
        клиент успел получить 400 вместо обрыва трубы (BrokenPipe)."""
        remaining = min(length, 16 * 1024 * 1024)
        while remaining > 0:
            chunk = rfile.read(min(65536, remaining))
            if not chunk:
                break
            remaining -= len(chunk)

    def _read_body(self):
        """Читает и парсит JSON-тело. Возвращает (dict, None) или (None, ответ-объект)."""
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except (TypeError, ValueError):
            return None, ("bad", None)
        if length <= 0 or length > MAX_BODY_BYTES:
            # В т.ч. тело больше 1 МиБ — по контракту это 400 «Некорректный запрос».
            # Сначала дренируем тело, иначе клиент ловит BrokenPipe до ответа.
            if length > 0:
                self._drain(self.rfile, length)
            return None, ("bad", None)
        raw = self.rfile.read(length)
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None, ("bad", None)
        if not isinstance(parsed, dict):
            return None, ("bad", None)
        return parsed, None

    # --- HTTP-методы ------------------------------------------------------

    def do_POST(self):
        try:
            if self.path == "/run":
                self._handle_run()
            elif self.path == "/read":
                self._handle_read()
            else:
                self._send_json(404, {"ok": False, "error": MSG_NOT_FOUND})
        except Exception as exc:  # последняя линия обороны: сервер жив
            sys.stderr.write("[sandbox] внутренняя ошибка: {0!r}\n".format(exc))
            self._send_json(200, {"ok": False, "error": "Внутренняя ошибка песочницы"})

    def do_GET(self):
        # Только POST по контракту; на GET по неизвестному пути — 404.
        self._send_json(404, {"ok": False, "error": MSG_NOT_FOUND})

    # --- ручки -------------------------------------------------------------

    def _handle_run(self):
        body, err = self._read_body()
        if err:
            self._send_bad_request()
            return

        language = body.get("language")
        code = body.get("code")
        if language is None or code is None:
            self._send_bad_request()
            return
        if not isinstance(language, str) or not isinstance(code, str):
            self._send_bad_request()
            return
        if language != "python":
            self._send_json(200, {"ok": False, "error": MSG_ONLY_PYTHON})
            return

        # timeoutSeconds: серверный потолок 60 с; клиентский кламп 1..60 дублируем здесь.
        raw_timeout = body.get("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
        if not isinstance(raw_timeout, (int, float)) or isinstance(raw_timeout, bool):
            self._send_bad_request()
            return
        timeout_seconds = min(float(raw_timeout), float(MAX_TIMEOUT_SECONDS))
        if timeout_seconds <= 0:
            self._send_bad_request()
            return

        result = run_python(code, timeout_seconds)
        self._send_json(200, result)

    def _handle_read(self):
        body, err = self._read_body()
        if err:
            self._send_bad_request()
            return

        if "path" not in body:
            self._send_bad_request()
            return

        result = read_workspace_file(body.get("path"))
        self._send_json(200, result)


def make_server(host=HOST, port=PORT):
    """Создаёт HTTP-сервер (отдельно, чтобы переиспользовать в тестах)."""
    return ThreadingHTTPServer((host, port), SandboxHandler)


def main():
    ensure_workspace()
    server = make_server()
    sys.stderr.write(
        "[sandbox] слушаю {0}:{1}, workspace={2}\n".format(HOST, PORT, WORKSPACE)
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
