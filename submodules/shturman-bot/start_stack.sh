#!/usr/bin/env bash
#
# Старт всего стека «Штурман» одной командой:
#   локальная LLM (LM Studio, :1235) + RAG sidecar (gwclub serve, :18700)
#   + bot_responder (единственный getUpdates-поллер).
#
# Идемпотентно: если процесс/порт уже поднят — не дублируем.
#
# Usage:
#   bash start_stack.sh            # из каталога shturman-bot
#
# Требования:
#   - LM Studio запущен на http://localhost:1235/v1 (в противном случае exit 1)
#   - доступен `uv` (для gwclub serve) и .venv (shturman-bot)
#
# env: TG_BOT_TOKEN / TG_CHAT читаются ботом; если не заданы — из
#      config.json рядом с кодом (фолбэк в bot_responder).

set -euo pipefail

ORIG_DIR="$(pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# gw-club-indexer лежит на два уровня выше проекта: shturman-bot ->
# Projects -> «AI Advent Challenge 9»
GW_CLUB_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)/gw-club-indexer"
MCP_DIR="$SCRIPT_DIR"

LM_URL="http://localhost:1235/v1/models"
RAG_URL="http://127.0.0.1:18700/search"
RAG_CHECK_JSON='{"query":"warmup","k":1}'

GW_LOG="$MCP_DIR/.gwclub-serve.log"
GW_PID="$MCP_DIR/.gwclub-serve.pid"
BOT_LOG="$MCP_DIR/.bot_responder.log"
BOT_PID="$MCP_DIR/.bot_responder.pid"

# Снапшот TG-БД для web-приложения (aiweb монтирует его :ro). Живую WAL-базу
# контейнеру отдавать нельзя (SQLite на сетевых ФС Docker Desktop ломается) —
# хост сам снимает консистентную копию. Два инварианта:
#   1) снапшот перезаписывается IN PLACE: у tg_server в контейнере персистентный
#      коннект, rename дал бы ему устаревший inode; перезапись того же файла
#      подхватывается через change-counter SQLite;
#   2) заголовок снапшота переводится в journal_mode=DELETE: read-only коннект
#      не может открыть WAL-базу (нет прав создать -shm) — падает с
#      "unable to open database file"; DELETE-режим читается без сайдкаров.
SRC_DB="$MCP_DIR/.tg_messages.db"
SYNC_DB_DIR="$MCP_DIR/.tg-db-share"
DST_DB="$SYNC_DB_DIR/tg_messages.db"
SYNC_PID="$MCP_DIR/.tg-db-sync.pid"
SYNC_LOG="$MCP_DIR/.tg-db-sync.log"
TG_DB_SYNC_INTERVAL="${TG_DB_SYNC_INTERVAL:-30}"

python_bin="$MCP_DIR/.venv/bin/python"

# --- helpers ---------------------------------------------------------------

is_up() { # is_up URL [JSON] -> 0 если порт отвечает 2xx
  local url="$1" data="${2:-}"
  local code
  if [ -n "$data" ]; then
    code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$url" \
                -H 'Content-Type: application/json' -d "$data" || true)"
  else
    code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$url" || true)"
  fi
  [ "$code" = "200" ] || [ "$code" = "201" ]
}

pid_alive() { # pid_alive PIDFILE -> 0 если процесс жив
  local f="$1"
  [ -f "$f" ] || return 1
  local pid
  pid="$(cat "$f" 2>/dev/null || true)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# --- 1. LM Studio ----------------------------------------------------------

echo "== Шаг 1/4: проверка LM Studio ($LM_URL)"
if ! curl -sf -m 5 "$LM_URL" >/dev/null 2>&1; then
  echo "ОШИБКА: LM Studio недоступен на http://localhost:1235/v1." >&2
  echo "Запустите LM Studio и загрузите модель, затем повторите." >&2
  exit 1
fi
echo "   LM Studio в порядке."

# --- 2. RAG sidecar (gwclub serve) ----------------------------------------

echo "== Шаг 2/4: RAG sidecar (gwclub serve, :18700)"
if is_up "$RAG_URL" "$RAG_CHECK_JSON"; then
  if pid_alive "$GW_PID"; then
    echo "   sidecar уже запущен (PID $(cat "$GW_PID"))."
  else
    # Порт отвечает, но PID-файла нет (или он устарел): найдём владельца порта.
    real_pid="$(lsof -ti tcp:18700 -sTCP:LISTEN 2>/dev/null | head -n1 || true)"
    if [ -n "$real_pid" ]; then
      echo "   sidecar отвечает (PID $real_pid) — записываю PID-файл."
      echo "$real_pid" > "$GW_PID"
    else
      echo "   sidecar отвечает, но владелец порта не найден — считаем запущенным."
    fi
  fi
else
  if pid_alive "$GW_PID"; then
    echo "   PID-файл есть, но порт не отвечает — перезапускаю."
    kill "$(cat "$GW_PID")" 2>/dev/null || true
    rm -f "$GW_PID"
    # Порт освобождается не мгновенно: умирающий процесс держит сокет, а новый
    # биндит его только после ~15 c загрузки моделей (Errno 48 при гонке).
    for i in $(seq 1 20); do
      is_up "$RAG_URL" "$RAG_CHECK_JSON" || break
      sleep 1
    done
  fi
  if [ ! -d "$GW_CLUB_DIR" ]; then
    echo "ОШИБКА: не найден каталог $GW_CLUB_DIR" >&2
    exit 1
  fi
  echo "   запускаю: uv run gwclub serve --port 18700"
  cd "$GW_CLUB_DIR"
  nohup uv run gwclub serve --port 18700 \
      --rerank-model BAAI/bge-reranker-v2-m3 \
      > "$GW_LOG" 2>&1 < /dev/null &
  echo $! > "$GW_PID"
  disown
  cd "$ORIG_DIR"
  sleep 2
  echo "   запущено (PID $(cat "$GW_PID")), лог: $GW_LOG"
  echo "   ожидаю прогрева модели..."
  # дождаться, пока sidecar начнёт отвечать (до ~120 c на прогрев модели)
  for i in $(seq 1 60); do
    if is_up "$RAG_URL" "$RAG_CHECK_JSON"; then
      echo "   sidecar готов."
      break
    fi
    if ! pid_alive "$GW_PID"; then
      echo "ОШИБКА: sidecar упал, см. $GW_LOG" >&2
      exit 1
    fi
    sleep 2
  done
  if ! is_up "$RAG_URL" "$RAG_CHECK_JSON"; then
    echo "ОШИБКА: sidecar не поднялся за 120 c, см. $GW_LOG" >&2
    exit 1
  fi
fi

# --- 3. bot_responder ------------------------------------------------------

echo "== Шаг 3/4: bot_responder (единственный getUpdates-поллер)"
if pid_alive "$BOT_PID" && curl -sf -m 5 "$LM_URL" >/dev/null 2>&1; then
  echo "   bot_responder уже запущен (PID $(cat "$BOT_PID"))."
else
  if pid_alive "$BOT_PID"; then
    echo "   PID-файл есть, но процесс не жив — перезапускаю."
    rm -f "$BOT_PID"
  fi
  echo "   запускаю: python bot_responder.py"
  cd "$MCP_DIR"
  nohup "$python_bin" bot_responder.py \
      > "$BOT_LOG" 2>&1 < /dev/null &
  echo $! > "$BOT_PID"
  disown
  cd "$ORIG_DIR"
  sleep 2
  if ! pid_alive "$BOT_PID"; then
    echo "ОШИБКА: bot_responder не запустился, см. $BOT_LOG" >&2
    exit 1
  fi
  echo "   запущено (PID $(cat "$BOT_PID")), лог: $BOT_LOG"
fi

# --- 4. Снапшот TG-БД для aiweb --------------------------------------------

echo "== Шаг 4/4: снапшот TG-БД -> .tg-db-share/ (интервал ${TG_DB_SYNC_INTERVAL}s)"
mkdir -p "$SYNC_DB_DIR"
if pid_alive "$SYNC_PID"; then
  echo "   цикл синка уже запущен (PID $(cat "$SYNC_PID"))."
else
  if [ -f "$SRC_DB" ]; then
    # первичный снимок (если цель ещё не создана; дальше цикл перепишет in place)
    sqlite3 "$SRC_DB" ".backup '$DST_DB'" 2>>"$SYNC_LOG" \
      && sqlite3 "$DST_DB" "PRAGMA journal_mode=DELETE;" >/dev/null 2>>"$SYNC_LOG" \
      || echo "$(date '+%F %T') стартовый снапшот не удался" >> "$SYNC_LOG"
  fi
  (
    while true; do
      if [ -f "$SRC_DB" ]; then
        sqlite3 "$SRC_DB" ".backup '$DST_DB'" 2>>"$SYNC_LOG" \
          && sqlite3 "$DST_DB" "PRAGMA journal_mode=DELETE;" >/dev/null 2>>"$SYNC_LOG" \
          || echo "$(date '+%F %T') снапшот не удался" >> "$SYNC_LOG"
      fi
      sleep "$TG_DB_SYNC_INTERVAL"
    done
  ) > /dev/null 2>&1 < /dev/null &
  echo $! > "$SYNC_PID"
  disown
  echo "   запущено (PID $(cat "$SYNC_PID")), лог: $SYNC_LOG"
fi

echo
echo "Стек поднят: LM Studio + gwclub serve (:18700) + bot_responder + снапшот TG-БД."
echo "Логи: $GW_LOG, $BOT_LOG, $SYNC_LOG. Остановка: bash stop_stack.sh"
