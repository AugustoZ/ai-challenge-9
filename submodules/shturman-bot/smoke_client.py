#!/usr/bin/env python3
"""Минимальный stdio JSON-RPC smoke-клиент для MCP-серверов «Штурмана»
(задача 14 плана mcp-integration-shturman). Только stdlib, БЕЗ пакета mcp.

Сессия (на каждый сервер): initialize -> notifications/initialized ->
tools/list -> tools/call. Ответы читаются до получения ожидаемого числа
или таймаута; при зависании — stdin.close(), затем SIGTERM, затем SIGKILL.

Паттерн против macOS-дедлока (урок task 7): НЕ select() + buffered readline
на pipe. Здесь: отдельный поток-читатель с блокирующим readline на stdout
(в отдельном потоке это безопасно), stderr — сразу в temp-файл (pipe не
переполняется, читать его можно после завершения).

stdout-дисциплина: каждая непустая строка stdout сервера обязана
парситься как JSON, traceback-слова запрещены. Логи сервера живут в
stderr — это норма и в smoke не считается ошибкой.

Usage:
    smoke_client.py tg   <path/to/tg_server.py>
    smoke_client.py wiki <path/to/wiki_server.py>
    smoke_client.py echo <path/to/fake_echo_server.py>

Exit code: 0 — все проверки PASS, 1 — есть FAIL.
"""

import json
import subprocess
import sys
import tempfile
import threading
import time

TIMEOUT_S = 30.0          # таймаут на всю сессию с одним сервером
PROTOCOL_VERSION = "2025-06-18"
CLIENT_INFO = {"name": "shturman-smoke", "version": "0.1.0"}
TRACE_WORDS = ("traceback", "error executing")

failures = []


# --------------------------------------------------------------------------
# Проверки
# --------------------------------------------------------------------------

def check(label, ok, detail=""):
    status = "PASS" if ok else "FAIL"
    line = f"  {status}: {label}" + (f" — {detail}" if detail else "")
    print(line)
    if not ok:
        failures.append(label)
    return ok


def finish(mode):
    if failures:
        print(f"== smoke[{mode}]: FAILED ({len(failures)}) ==")
        return 1
    print(f"== smoke[{mode}]: ALL PASS ==")
    return 0


# --------------------------------------------------------------------------
# JSON-RPC
# --------------------------------------------------------------------------

def jsonrpc_request(id_, method, params=None):
    msg = {"jsonrpc": "2.0", "id": id_, "method": method}
    if params is not None:
        msg["params"] = params
    return msg


def jsonrpc_notification(method, params=None):
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    return msg


def base_session_requests():
    return [
        jsonrpc_request(1, "initialize", {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": CLIENT_INFO,
        }),
        jsonrpc_notification("notifications/initialized"),
        jsonrpc_request(2, "tools/list"),
    ]


# --------------------------------------------------------------------------
# Сессия с сервером
# --------------------------------------------------------------------------

def run_session(server_path, requests, expected_responses, timeout=TIMEOUT_S):
    """Стартует сервер, пишет ВСЕ запросы, читает stdout в отдельном потоке
    до получения expected_responses непустых строк или таймаута.

    Возвращает (lines, stderr_text, returncode, hung).
    """
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8", errors="replace") as errf:
        proc = subprocess.Popen(
            [sys.executable, server_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=errf,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        lines = []

        def _reader():
            # Блокирующий readline в ОТДЕЛЬНОМ потоке — безопасно на macOS
            # (дедлок select()+buffered readline из task 7 сюда не применим).
            try:
                for raw in proc.stdout:
                    lines.append(raw.rstrip("\r\n"))
            except (ValueError, OSError):
                pass

        reader = threading.Thread(target=_reader, daemon=True)
        reader.start()

        deadline = time.monotonic() + timeout
        hung = False
        try:
            for req in requests:
                proc.stdin.write(json.dumps(req, ensure_ascii=False) + "\n")
            proc.stdin.flush()
            while len([l for l in lines if l.strip()]) < expected_responses:
                if time.monotonic() > deadline:
                    hung = True
                    break
                time.sleep(0.05)
        finally:
            # Мягкая остановка: EOF в stdin -> сервер завершается сам.
            try:
                proc.stdin.close()
            except (OSError, ValueError):
                pass
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.terminate()  # SIGTERM при зависании
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    try:
                        proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        pass
            reader.join(timeout=5)
        errf.seek(0)
        stderr_text = errf.read()
    return lines, stderr_text, proc.returncode, hung


# --------------------------------------------------------------------------
# Разбор ответов
# --------------------------------------------------------------------------

def parse_stdout(lines):
    """(parsed, bad_lines, trace_hits): parsed — JSON-объекты; bad — строки,
    не парсящиеся как JSON; trace_hits — строки с traceback-словами."""
    parsed, bad, trace_hits = [], [], []
    for i, ln in enumerate(lines):
        s = ln.strip()
        if not s:
            continue
        low = s.lower()
        if any(w in low for w in TRACE_WORDS):
            trace_hits.append((i, ln[:200]))
        try:
            parsed.append(json.loads(s))
        except ValueError:
            bad.append((i, ln[:200]))
    return parsed, bad, trace_hits


def by_id(parsed):
    out = {}
    for r in parsed:
        if isinstance(r, dict) and "id" in r:
            out[r["id"]] = r
    return out


def tool_text(resp):
    """Сырой текст тула из result.content[0].text (БЕЗ json.loads —
    тул может вернуть и не-JSON строку, как fake_echo_server.echo).
    Возвращает (text|None, описание_ошибки)."""
    if not isinstance(resp, dict):
        return None, "нет ответа на tools/call"
    result = resp.get("result")
    if not isinstance(result, dict):
        return None, f"в ответе нет result: {json.dumps(resp, ensure_ascii=False)[:200]}"
    content = result.get("content") or []
    if not content or not isinstance(content[0], dict) or content[0].get("type") != "text":
        return None, "нет text-content в result"
    return content[0]["text"], None


def tool_payload(resp):
    """JSON-пayload тула из result.content[0].text (FastMCP оборачивает
    строку тула в TextContent). Возвращает (payload|None, описание_ошибки)."""
    text, terr = tool_text(resp)
    if terr:
        return None, terr
    try:
        return json.loads(text), None
    except ValueError as exc:
        return None, f"content[0].text не JSON: {exc}: {text[:200]}"


def stderr_tail(stderr_text, n=3):
    ls = [l for l in stderr_text.strip().splitlines() if l.strip()]
    return " | ".join(l.strip()[:160] for l in ls[-n:])


# --------------------------------------------------------------------------
# Сценарии
# --------------------------------------------------------------------------

def tool_names(list_resp):
    if not isinstance(list_resp, dict):
        return []
    tools = (list_resp.get("result") or {}).get("tools") or []
    return sorted(t.get("name") for t in tools if isinstance(t, dict))


def check_discipline(lines, bad, trace_hits):
    n = len([l for l in lines if l.strip()])
    detail = f"непустых строк stdout: {n}"
    if bad:
        detail += f"; не-JSON строки: {bad}"
    if trace_hits:
        detail += f"; traceback-слова: {trace_hits}"
    check("stdout-дисциплина: каждая строка — валидный JSON, traceback-слов нет",
          not bad and not trace_hits, detail)


def check_init(resp, expected_name):
    result = (resp or {}).get("result")
    name = (result or {}).get("serverInfo", {}).get("name") if isinstance(result, dict) else None
    proto = (result or {}).get("protocolVersion") if isinstance(result, dict) else None
    check(f"initialize → serverInfo.name == '{expected_name}' (без кредов, boot OK)",
          name == expected_name,
          f"serverInfo.name={name!r}, protocolVersion={proto!r}")


def check_tool_error(call_resp, err_substring, label):
    payload, perr = tool_payload(call_resp)
    if perr:
        check(label, False, perr)
        return
    ok_false = isinstance(payload, dict) and payload.get("ok") is False
    err_text = payload.get("error") if isinstance(payload, dict) else None
    is_err_flag = bool(((call_resp.get("result") or {}).get("isError", False)))
    readable = isinstance(err_text, str) and err_substring in err_text
    check(label, ok_false and readable and not is_err_flag,
          f"ok={payload.get('ok')!r}, isError={is_err_flag}, error={err_text!r}")


def mode_tg(server_path):
    print(f"== smoke[tg]: {server_path} ==")
    requests = base_session_requests() + [
        jsonrpc_request(3, "tools/call", {"name": "read_recent", "arguments": {}}),
    ]
    lines, stderr_text, rc, hung = run_session(server_path, requests, expected_responses=3)
    parsed, bad, trace_hits = parse_stdout(lines)
    ids = by_id(parsed)

    check("сервер ответил на все 3 запроса (initialize/tools/list/tools/call) без зависания",
          not hung and all(i in ids for i in (1, 2, 3)),
          f"получено ответов: {len([l for l in lines if l.strip()])}, hung={hung}, exit={rc}")
    check_init(ids.get(1), "shturman-tg")
    names = tool_names(ids.get(2))
    check("tools/list → ровно 3 тула: read_recent, search, summary",
          names == ["read_recent", "search", "summary"], f"tools={names}")
    check_tool_error(
        ids.get(3), "Токен бота не задан",
        "read_recent без TG_BOT_TOKEN → ok:false, читаемая ошибка (не crash)")
    check_discipline(lines, bad, trace_hits)
    tail = stderr_tail(stderr_text)
    if tail:
        print(f"  INFO: stderr (логи сервера — норма): {tail}")
    return finish("tg")


def mode_wiki(server_path):
    print(f"== smoke[wiki]: {server_path} ==")
    requests = base_session_requests() + [
        jsonrpc_request(3, "tools/call", {"name": "create_faq_page", "arguments": {}}),
    ]
    lines, stderr_text, rc, hung = run_session(server_path, requests, expected_responses=3)
    parsed, bad, trace_hits = parse_stdout(lines)
    ids = by_id(parsed)

    check("сервер ответил на все 3 запроса (initialize/tools/list/tools/call) без зависания",
          not hung and all(i in ids for i in (1, 2, 3)),
          f"получено ответов: {len([l for l in lines if l.strip()])}, hung={hung}, exit={rc}")
    check_init(ids.get(1), "shturman-wiki")
    names = tool_names(ids.get(2))
    check("tools/list → ровно 3 тула: create_faq_page, read_faq_page, append_faq_page",
          names == ["append_faq_page", "create_faq_page", "read_faq_page"], f"tools={names}")
    check_tool_error(
        ids.get(3), "API-ключ не задан",
        "create_faq_page без WIKI_API_KEY → ok:false «API-ключ не задан…» (не crash)")
    check_discipline(lines, bad, trace_hits)
    tail = stderr_tail(stderr_text)
    if tail:
        print(f"  INFO: stderr (логи сервера — норма): {tail}")
    return finish("wiki")


def mode_echo(server_path):
    print(f"== smoke[echo]: {server_path} ==")
    text = "smoke-«проверка» 123"
    requests = base_session_requests() + [
        jsonrpc_request(3, "tools/call", {"name": "echo", "arguments": {"text": text}}),
    ]
    lines, stderr_text, rc, hung = run_session(server_path, requests, expected_responses=3)
    parsed, bad, trace_hits = parse_stdout(lines)
    ids = by_id(parsed)

    check("сервер ответил на все 3 запроса (initialize/tools/list/tools/call) без зависания",
          not hung and all(i in ids for i in (1, 2, 3)),
          f"получено ответов: {len([l for l in lines if l.strip()])}, hung={hung}, exit={rc}")
    check_init(ids.get(1), "fake-echo")
    names = tool_names(ids.get(2))
    check("tools/list → есть тула echo и fail", "echo" in names and "fail" in names,
          f"tools={names}")
    got, terr = tool_text(ids.get(3))
    check(f"echo round-trip: tools/call(echo, {text!r}) → 'ECHO:{text}'",
          got == f"ECHO:{text}", f"got={got!r}" + (f"; err={terr}" if terr else ""))
    check_discipline(lines, bad, trace_hits)
    return finish("echo")


# --------------------------------------------------------------------------

def main(argv):
    if len(argv) != 3 or argv[1] not in ("tg", "wiki", "echo"):
        print(__doc__)
        return 2
    mode, server_path = argv[1], argv[2]
    if mode == "tg":
        return mode_tg(server_path)
    if mode == "wiki":
        return mode_wiki(server_path)
    return mode_echo(server_path)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
