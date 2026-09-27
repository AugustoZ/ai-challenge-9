#!/usr/bin/env bash
#
# Резервная копия SQLite-БД Telegram (tg_server.py) из named volume aiweb-data.
# БЕЗ остановки приложения: VACUUM INTO делает консистентный снимок на лету,
# даже при активном WAL — запись в живую БД не блокируем и не разрушаем.
#
# Usage:
#   bash scripts/backup-tg-db.sh                    # volume aiweb-data -> ./backups
#   bash scripts/backup-tg-db.sh /tmp/bk            # volume aiweb-data -> /tmp/bk
#   bash scripts/backup-tg-db.sh /tmp/bk my-volume  # произвольный том
#
# Зависимости: docker; образ python:3.12-slim подтянется при первом запуске.

set -euo pipefail

VOLUME="${2:-aiweb-data}"
OUT_DIR="${1:-$PWD/backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="${OUT_DIR}/tg-messages-${STAMP}.db"

mkdir -p "$OUT_DIR"

# -v "${VOLUME}:/data:ro" — том монтируем только на чтение: снимок не трогает
#   живой файл БД;
# -v "${OUT_DIR}:/backups" — каталог бэкапа монтируем внутрь контейнера;
# VACUUM INTO — снимает консистентную копию БД (включая данные из WAL) в новый
#   файл и безопасна при активной записи (без stop/restart сервиса).
docker run --rm \
  -v "${VOLUME}:/data:ro" \
  -v "${OUT_DIR}:/backups" \
  python:3.12-slim \
  python -c "import sqlite3; sqlite3.connect('/data/tg_messages.db').execute(\"VACUUM INTO '/backups/tg-messages-${STAMP}.db'\")"

echo "OK: ${OUT_FILE}"
