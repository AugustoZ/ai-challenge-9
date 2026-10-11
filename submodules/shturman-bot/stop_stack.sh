#!/usr/bin/env bash
#
# Остановка стека «Штурман» по PID-файлам:
#   bot_responder + RAG sidecar (gwclub serve) + цикл снапшота TG-БД.
# LM Studio НЕ трогаем — это внешний сервис.
#
# Usage:
#   bash stop_stack.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GW_PID="$SCRIPT_DIR/.gwclub-serve.pid"
BOT_PID="$SCRIPT_DIR/.bot_responder.pid"
SYNC_PID="$SCRIPT_DIR/.tg-db-sync.pid"

stop_by_pid() { # stop_by_pid PIDFILE NAME
  local f="$1" name="$2"
  if [ -f "$f" ]; then
    local pid
    pid="$(cat "$f" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      echo "Останавливаю $name (PID $pid)..."
      kill "$pid" 2>/dev/null || true
      # даём процессу время на graceful shutdown
      for _ in 1 2 3 4 5; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
    else
      echo "$name: PID-файл есть, но процесс не жив — пропускаю."
    fi
    rm -f "$f"
  else
    echo "$name: PID-файл не найден — пропускаю."
  fi
}

stop_by_pid "$BOT_PID" "bot_responder"
stop_by_pid "$GW_PID"  "gwclub serve (RAG sidecar)"
stop_by_pid "$SYNC_PID" "цикл снапшота TG-БД"

echo "Готово. LM Studio не останавливался."
