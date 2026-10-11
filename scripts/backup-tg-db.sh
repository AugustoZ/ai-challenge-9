#!/usr/bin/env bash
#
# Резервная копия БД Telegram-бота «Штурман».
# Бот переехал в standalone-проект ../shturman-bot: его живая SQLite-база
# (WAL) снимается консистентным .backup без остановки бота.
#
# Usage:
#   bash scripts/backup-tg-db.sh                  # -> ./backups
#   bash scripts/backup-tg-db.sh /tmp/bk          # -> /tmp/bk
#
# env: TG_DB_BACKUP_SOURCE — путь к БД (по умолчанию ../shturman-bot/.tg_messages.db)
#
# Зависимости: системный sqlite3.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_DB="$SCRIPT_DIR/../../shturman-bot/.tg-db-share/tg_messages.db"
DB="${TG_DB_BACKUP_SOURCE:-$DEFAULT_DB}"
OUT_DIR="${1:-$PWD/backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="${OUT_DIR}/tg-messages-${STAMP}.db"

mkdir -p "$OUT_DIR"

if [ ! -f "$DB" ]; then
  echo "ОШИБКА: БД не найдена: $DB" >&2
  exit 1
fi

# sqlite3 .backup снимает консистентную копию (включая данные из WAL),
# не блокируя живую запись бота и не разрушая снапшот.
sqlite3 "$DB" ".backup '$OUT_FILE'"

echo "OK: ${OUT_FILE}"
