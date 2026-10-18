FROM python:3.12-slim

# libsqlite3 уже в базе slim-образа — stdlib sqlite3 (sync_loop) работает из коробки.
# Единственная внешняя зависимость рантайма бота — httpx (mcp/telethon нужны только
# tg_server на хосте, см. requirements.txt). Версия = как в requirements/Dockerfile aiweb.
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir "httpx==0.28.1"
ENV PATH="/opt/venv/bin:$PATH"

WORKDIR /app

COPY bot_responder.py storage.py github_watcher.py pr_review.py wiki_urls.py sync_loop.py ./

# config.json (токен) монтируется :ro рядом с кодом — фолбэк bot_responder его подхватит.
ENV TG_DB_FILE=/app/data/.tg_messages.db \
    RAG_DOCS_ROOT=/app/docs \
    DOCS_ROOT=/app/docs

CMD ["python", "-u", "bot_responder.py"]
