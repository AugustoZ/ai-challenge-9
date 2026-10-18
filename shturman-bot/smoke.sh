#!/usr/bin/env bash
#
# MCP smoke-тест tg_server «Штурмана» (адаптация задачи 14 плана
# mcp-integration-shturman для standalone-проекта shturman-bot:
# wiki_server остался в ai-challenge и здесь не тестируется).
#
# Usage:
#   bash smoke.sh                  # из каталога shturman-bot
#   bash "<absolute-path>/smoke.sh"  # из любого cwd
#
# Что проверяет (PASS/FAIL):
#   tg : tg_server.py — boot БЕЗ TG-кредов, initialize
#        (serverInfo.name=shturman-tg), tools/list (read_recent, search,
#        summary), read_recent без TG_BOT_TOKEN -> ok:false с читаемой
#        ошибкой (не crash, не traceback в stdout).
#   + stdout-дисциплина: каждая непустая строка stdout — валидный JSON-RPC,
#     traceback-слов нет (логи сервера идут в stderr — это норма).
#
# Ошибки БЕЗ кредов — ОЖИДАЕМОЕ поведение (ok:false): smoke считает это PASS
# («сервер жив, отвечает читаемо»). Креды принудительно снимаются ниже.
#
# Зависимости: только .venv (Python 3.12, mcp, telethon, httpx).
# smoke_client.py — чистый stdlib, без пакета mcp.
#
# Итог: последняя строка "SMOKE: ALL PASS" либо "SMOKE: FAILED (N)";
# exit code 0/1 соответственно.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PY="$SCRIPT_DIR/.venv/bin/python"
CLIENT="$SCRIPT_DIR/smoke_client.py"
TG_SERVER="$SCRIPT_DIR/tg_server.py"

for f in "$PY" "$CLIENT" "$TG_SERVER"; do
  if [ ! -f "$f" ]; then
    echo "FAIL: не найден файл: $f" >&2
    exit 1
  fi
done

# Гарантированно без кредов: сценарий «нет кредов -> ok:false» детерминирован.
unset TG_BOT_TOKEN TG_CHAT TG_BUFFER_FILE TG_DB_FILE TG_RETENTION_DAYS \
      WIKI_API_KEY WIKI_BASE_URL 2>/dev/null || true
# Пустые (несуществующие) хранилище и legacy-буфер => read_recent без токена
# даст читаемую ошибку, и в проекте не появится файл артефактов.
export TG_DB_FILE="$(mktemp -u)"
export TG_BUFFER_FILE="$(mktemp -u)"

export PYTHONUNBUFFERED=1

cd "$SCRIPT_DIR"

fails=0

echo
echo "=============================================================="
echo "== tg_server.py (shturman-tg): boot без кредов + протокол"
echo "=============================================================="
if "$PY" "$CLIENT" tg "$TG_SERVER"; then
  :
else
  fails=$((fails + 1))
fi

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
