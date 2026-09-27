#!/usr/bin/env bash
#
# MCP smoke-тест серверов «Штурмана» (задача 14 плана mcp-integration-shturman).
#
# Usage:
#   bash mcp-servers/smoke.sh                       # из repo root (рекомендуется)
#   bash "<absolute-path>/mcp-servers/smoke.sh"     # из любого cwd — repo root
#                                                   # определяется от пути скрипта
#
# Что проверяет (каждая проверка печатает PASS/FAIL):
#   1. tg   : mcp-servers/tg_server.py   — boot БЕЗ TG-кредов, initialize
#             (serverInfo.name=shturman-tg), tools/list (read_recent, search,
#             summary), read_recent без TG_BOT_TOKEN -> ok:false с читаемой
#             ошибкой (не crash, не traceback в stdout).
#   2. wiki : mcp-servers/wiki_server.py — boot БЕЗ WIKI_API_KEY, initialize
#             (shturman-wiki), tools/list (create_faq_page, read_faq_page,
#             append_faq_page), create_faq_page -> ok:false «API-ключ не
#             задан…».
#   3. echo : src/test/resources/mcp/fake_echo_server.py — round-trip
#             tools/call echo -> "ECHO:<text>".
#   + stdout-дисциплина каждого сервера: каждая непустая строка stdout —
#     валидный JSON-RPC, traceback-слов нет (логи серверов идут в stderr —
#     это норма и не считается ошибкой).
#
# Ошибки БЕЗ кредов — ОЖИДАЕМОЕ поведение (ok:false): smoke считает это PASS
# («сервер жив, отвечает читаемо»). Креды принудительно снимаются ниже.
#
# Зависимости: только mcp-servers/.venv (Python 3.12, mcp 1.30.0, telethon,
# httpx). Smoke-клиент (smoke_client.py) — чистый stdlib, без пакета mcp.
#
# Итог: последняя строка "SMOKE: ALL PASS" либо "SMOKE: FAILED (N)";
# exit code 0/1 соответственно.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

PY="$SCRIPT_DIR/.venv/bin/python"
CLIENT="$SCRIPT_DIR/smoke_client.py"
TG_SERVER="$SCRIPT_DIR/tg_server.py"
WIKI_SERVER="$SCRIPT_DIR/wiki_server.py"
ECHO_SERVER="$REPO_ROOT/src/test/resources/mcp/fake_echo_server.py"

for f in "$PY" "$CLIENT" "$TG_SERVER" "$WIKI_SERVER" "$ECHO_SERVER"; do
  if [ ! -f "$f" ]; then
    echo "FAIL: не найден файл: $f" >&2
    exit 1
  fi
done

# Гарантированно без кредов: сценарии «нет кредов -> ok:false» детерминированы.
unset TG_BOT_TOKEN TG_CHAT TG_BUFFER_FILE TG_DB_FILE TG_RETENTION_DAYS \
      WIKI_API_KEY WIKI_BASE_URL 2>/dev/null || true
# Пустые (несуществующие) хранилище и legacy-буфер => read_recent без токена
# даст читаемую ошибку, и в repo не появится файл артефактов.
export TG_DB_FILE="$(mktemp -u)"
export TG_BUFFER_FILE="$(mktemp -u)"

export PYTHONUNBUFFERED=1

cd "$REPO_ROOT"

fails=0

run_case() {
  local name="$1"; shift
  echo
  echo "=============================================================="
  echo "== $name"
  echo "=============================================================="
  # set -e не срабатывает внутри if: неуспех клиента просто инкрементит счётчик.
  if "$PY" "$CLIENT" "$@"; then
    :
  else
    fails=$((fails + 1))
  fi
}

run_case "tg_server.py (shturman-tg): boot без кредов + протокол" \
  tg "$TG_SERVER"
run_case "wiki_server.py (shturman-wiki): boot без кредов + протокол" \
  wiki "$WIKI_SERVER"
run_case "fake_echo_server.py: echo round-trip (bonus)" \
  echo "$ECHO_SERVER"

echo
echo "=============================================================="
if [ "$fails" -eq 0 ]; then
  echo "SMOKE: ALL PASS"
  echo "=============================================================="
  exit 0
else
  echo "SMOKE: FAILED ($fails)"
  echo "=============================================================="
  exit 1
fi
