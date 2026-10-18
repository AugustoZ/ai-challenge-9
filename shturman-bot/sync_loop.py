"""Снапшот TG-БД для потребителей вне процесса бота (tg-sqlweb, aiweb).

Контракт снапшота:
  1) снапшот перезаписывается IN PLACE — читатели с персистентным коннектом
     подхватывают свежие данные по change-counter, rename сломал бы их inode;
  2) заголовок снапшота переводится в journal_mode=DELETE — read-only коннект
     не может открыть WAL-базу (некуда писать -shm).
"""
import os
import sqlite3
import sys
import time

SRC = os.environ.get("TG_DB_FILE", "/app/data/.tg_messages.db")
DST = os.environ.get("TG_DB_SHARE_FILE", "/app/share/tg_messages.db")
INTERVAL = int(os.environ.get("TG_DB_SYNC_INTERVAL", "30"))


def snapshot() -> None:
    src = sqlite3.connect(f"file:{SRC}?mode=ro", uri=True, timeout=10)
    try:
        dst = sqlite3.connect(DST, timeout=10)
        try:
            src.backup(dst)
            dst.execute("PRAGMA journal_mode=DELETE;")
        finally:
            dst.close()
    finally:
        src.close()


def main() -> None:
    os.makedirs(os.path.dirname(DST), exist_ok=True)
    while True:
        try:
            if os.path.isfile(SRC):
                snapshot()
        except (sqlite3.Error, OSError) as exc:
            print(f"tg-db-sync: snapshot failed: {exc}", file=sys.stderr, flush=True)
        time.sleep(INTERVAL)


if __name__ == "__main__":
    main()
