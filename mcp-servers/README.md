# mcp-servers — MCP-интеграция aiweb

Каталог содержит MCP-серверы, которые запускает web-приложение aiweb
(`config.json` → блок `mcp`, транспорт stdio):

- `wiki_server.py` — интеграция с Wiki.js (создание/чтение/дополнение FAQ-страниц).
  Зависит от `common.py` — обёртка FastMCP: stdout = только JSON-RPC, логи в stderr.
- `requirements.txt` — зависимости venv: `mcp==1.30.0`, `httpx==0.28.1`.
  Версии зафиксированы: в mcp 2.x `FastMCP` переименован в `MCPServer` и код
  серверов ломается.
- `.venv/` — окружение; его же используют Kotlin-тесты aiweb
  (`src/test/kotlin/com/example/aiweb/mcp/*` запускают `mcp-servers/.venv/bin/python`).

## Telegram-бот «Штурман» переехал

Бот (`bot_responder.py`), слой БД (`storage.py`), `tg_server.py`, скрипты стека
(`start_stack.sh` / `stop_stack.sh` / `smoke.sh`) и документация вынесены в
standalone-проект `../shturman-bot` (`~/AI Advent Challenge 9/Projects/shturman-bot`):

- `README.md` — развёртывание на новом сервере;
- `ARCHITECTURE.md` — схема работы бота.

Последствия для aiweb:

- в Docker-образе остаётся только `wiki_server.py` + `common.py`
  (см. `Dockerfile`); `config.docker.json` больше не содержит сервера `tg`;
- снапшот TG-БД контейнеру не нужен — `docker-compose.yml` не монтирует
  `.tg-db-share`;
- на хосте `config.json` запускает `tg_server.py` из каталога shturman-bot
  (его `tg_server` по умолчанию читает БД `shturman-bot/.tg_messages.db`).
