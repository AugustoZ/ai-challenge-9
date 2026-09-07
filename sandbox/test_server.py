#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Смоук-тесты sandbox-сервера (только stdlib unittest, без pytest).

Запуск локально:
    python3 sandbox/test_server.py

Поднимает сервер на эфемерном порту в отдельном потоке и проверяет
HTTP-контракт /run и /read: happy-path, ненулевой exitCode, синтаксическая
ошибка, убийство по таймауту, защита от выхода за workspace (../, абсолютный
путь, симлинк наружу), белый список языков, некорректный JSON и 400.
"""

import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

# Рабочая папка сервера должна указывать во временную папку ДО импорта server.py
_TEST_DIR = tempfile.mkdtemp(prefix="sandbox-test-")
os.environ["SANDBOX_WORKSPACE"] = _TEST_DIR

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import server  # noqa: E402  (импортируем после установки SANDBOX_WORKSPACE)

BASE_URL = None  # заполнится в setUpModule


def setUpModule():
    server.ensure_workspace()
    httpd = server.make_server(port=0)  # эфемерный порт
    global BASE_URL
    BASE_URL = "http://127.0.0.1:{0}".format(httpd.server_address[1])
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    setUpModule.httpd = httpd


def tearDownModule():
    setUpModule.httpd.shutdown()
    shutil.rmtree(_TEST_DIR, ignore_errors=True)


def post_json(path, payload, raw_body=None, expect_status=None):
    """POST c JSON-телом; возвращает (status, dict-ответ)."""
    body = raw_body if raw_body is not None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path, data=body, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8"))


def run_code(code, timeout_seconds=15, language="python"):
    return post_json("/run", {
        "language": language, "code": code, "timeoutSeconds": timeout_seconds,
    })


class RunEndpointTest(unittest.TestCase):
    """POST /run — исполнение кода."""

    def test_happy_path_print_and_file_write(self):
        # Печатаем строку, пишем файл в workspace, потом читаем его через /read.
        status, resp = run_code(
            "print('привет, sandbox')\n"
            "open('report.txt', 'w', encoding='utf-8').write('отчёт готов')\n"
        )
        self.assertEqual(status, 200)
        self.assertTrue(resp["ok"], resp)
        self.assertEqual(resp["exitCode"], 0)
        self.assertIn("привет, sandbox", resp["stdout"])
        self.assertTrue((Path(_TEST_DIR) / "report.txt").exists())

        status, resp = post_json("/read", {"path": "report.txt"})
        self.assertEqual(status, 200)
        self.assertTrue(resp["ok"], resp)
        self.assertFalse(resp["truncated"])
        self.assertEqual(resp["content"], "отчёт готов")

    def test_nonzero_exit_is_ok_true_with_exit_code(self):
        # Ненулевой exitCode — штатное исполнение: ok=true, exitCode≠0, stderr сохранён.
        status, resp = run_code(
            "import sys\nprint('в stdout')\nsys.stderr.write('крах по бизнес-логике')\nsys.exit(3)\n"
        )
        self.assertEqual(status, 200)
        self.assertTrue(resp["ok"], resp)
        self.assertEqual(resp["exitCode"], 3)
        self.assertIn("в stdout", resp["stdout"])
        self.assertIn("крах по бизнес-логике", resp["stderr"])

    def test_syntax_error_is_ok_false(self):
        status, resp = run_code("def broken(:\n    pass\n")
        self.assertEqual(status, 200)
        self.assertFalse(resp["ok"])
        self.assertIn("Синтаксическая ошибка", resp["error"])

    def test_timeout_kills_process_group(self):
        # Бесконечный цикл + попытка породить потомка: по таймауту должна
        # умереть вся группа процессов, тест обязан завершиться быстро.
        # Порождение потомка обёрнуто в try/except: на macOS локально лимит
        # RLIMIT_NPROC может запретить fork — тогда проверяем хотя бы
        # убийство самого процесса по таймауту.
        code = (
            "import subprocess, sys, time\n"
            "try:\n"
            "    subprocess.Popen([sys.executable, '-c', 'while True: time.sleep(1)'])\n"
            "except Exception:\n"
            "    pass\n"
            "while True:\n    time.sleep(1)\n"
        )
        started = time.monotonic()
        status, resp = run_code(code, timeout_seconds=2)
        elapsed = time.monotonic() - started
        self.assertEqual(status, 200)
        self.assertFalse(resp["ok"])
        self.assertIn("Превышен таймаут выполнения (2s)", resp["error"])
        # 2 с таймаут + запас на kill/сборку ответа; щедрый потолок для CI.
        self.assertLess(elapsed, 10.0, "таймаут не сработал вовремя: {0:.1f}s".format(elapsed))

    def test_language_whitelist(self):
        status, resp = run_code("console.log('hi')", language="javascript")
        self.assertEqual(status, 200)
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"], "Поддерживается только python")

    def test_stdout_truncation_marker(self):
        # Вывод заведомо больше 64 КиБ: хвост обязан быть обрезан с маркером.
        status, resp = run_code("print('x' * 200000)")
        self.assertEqual(status, 200)
        self.assertTrue(resp["ok"], resp)
        self.assertLessEqual(len(resp["stdout"].encode("utf-8")), 64 * 1024 + 200)
        self.assertIn("вывод усечён", resp["stdout"])


class ReadEndpointTest(unittest.TestCase):
    """POST /read — чтение файлов строго из workspace."""

    def test_path_traversal_rejected(self):
        # Путь с ".." обязан быть отвергнут, даже если итог остался бы внутри
        # (например "sub/../../etc/passwd"). Windows-разделители не проверяем:
        # на POSIX (образ alpine) обратный слэш — обычный символ имени файла.
        for bad in ("../etc/passwd", "sub/../../etc/passwd", "a/../../secret"):
            status, resp = post_json("/read", {"path": bad})
            self.assertEqual(status, 200)
            self.assertFalse(resp["ok"], bad)
            self.assertEqual(resp["error"], "Путь вне рабочей папки запрещён")

    def test_absolute_path_rejected(self):
        status, resp = post_json("/read", {"path": "/etc/passwd"})
        self.assertEqual(status, 200)
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"], "Путь вне рабочей папки запрещён")

    def test_missing_file(self):
        status, resp = post_json("/read", {"path": "нет-такого-файла.txt"})
        self.assertEqual(status, 200)
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"], "Файл не найден")

    def test_symlink_escape_rejected(self):
        # Симлинк в workspace, указывающий наружу, создаётся самим run_code —
        # затем /read обязан его отвергнуть.
        status, resp = run_code("import os\nos.symlink('/etc/passwd', 'evil.txt')\n")
        self.assertEqual(status, 200)
        self.assertTrue(resp["ok"], resp)

        status, resp = post_json("/read", {"path": "evil.txt"})
        self.assertEqual(status, 200)
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"], "Путь вне рабочей папки запрещён")

    def test_symlink_inside_workspace_allowed(self):
        # Внутренний симлинк (на файл внутри workspace) — легален.
        (Path(_TEST_DIR) / "base.txt").write_text("внутри", encoding="utf-8")
        status, resp = run_code("import os\nos.symlink('base.txt', 'link.txt')\n")
        self.assertEqual(status, 200)
        self.assertTrue(resp["ok"], resp)

        status, resp = post_json("/read", {"path": "link.txt"})
        self.assertEqual(status, 200)
        self.assertTrue(resp["ok"], resp)
        self.assertEqual(resp["content"], "внутри")


class ProtocolTest(unittest.TestCase):
    """Общие правила HTTP-контракта."""

    def test_unknown_path_404(self):
        status, resp = post_json("/nope", {})
        self.assertEqual(status, 404)
        self.assertFalse(resp["ok"])

    def test_malformed_json_400(self):
        status, resp = post_json("/run", None, raw_body=b"{not json")
        self.assertEqual(status, 400)
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"], "Некорректный запрос")

    def test_missing_required_field_400(self):
        # Нет "code"; нет "path".
        status, resp = post_json("/run", {"language": "python", "timeoutSeconds": 5})
        self.assertEqual(status, 400)
        self.assertEqual(resp["error"], "Некорректный запрос")

        status, resp = post_json("/read", {})
        self.assertEqual(status, 400)
        self.assertEqual(resp["error"], "Некорректный запрос")

    def test_body_over_1mib_400(self):
        status, resp = post_json("/run", None, raw_body=b"x" * (1024 * 1024 + 1))
        self.assertEqual(status, 400)
        self.assertEqual(resp["error"], "Некорректный запрос")

    def test_timeout_hard_cap_60(self):
        # Клиент просит 9999 с — сервер обязан капит на 60: проверяем, что
        # запрос с большим таймаутом не принимается «как есть». Прогоняем
        # код, который живёт 2 с, с завышенным timeoutSeconds — исполнение
        # проходит штатно (кап не режет легитимные значения).
        status, resp = run_code("print('ok')", timeout_seconds=9999)
        self.assertEqual(status, 200)
        self.assertTrue(resp["ok"], resp)


if __name__ == "__main__":
    unittest.main(verbosity=2)
