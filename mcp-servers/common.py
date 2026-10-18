"""
Единая точка сборки MCP-серверов «Штурмана» (задача 2 плана
mcp-integration-shturman). Задачи 7–8 импортируют только это.

Правила, зашитые здесь (нарушать нельзя):
    1. stdout серверов = ТОЛЬКО JSON-RPC (stdio-транспорт). Любая лишняя
       строка в stdout ломает протокол: все логи — в stderr.
    2. Секреты (TG_API_ID, TG_API_HASH, TG_SESSION, WIKI_API_KEY) — только
       из окружения / config.json, никогда не хардкодом.
    3. .venv не коммитится (см. .gitignore репо).

Использование (задачи 7–8):

    from common import build_server

    mcp = build_server("shturman-tg")

    @mcp.tool()
    def tg_read_last_messages(n: int = 10) -> str:
        'Читает последние n сообщений чата.'
        ...

    if __name__ == "__main__":
        mcp.run()  # stdio по умолчанию
"""

import logging
import sys

from mcp.server.fastmcp import FastMCP

# Все логи — в stderr: stdout зарезервирован за JSON-RPC (stdio-транспорт).
logging.basicConfig(stream=sys.stderr, level=logging.INFO)


def build_server(name: str) -> FastMCP:
    """Создаёт FastMCP-сервер с заданным именем (stdio-транспорт по умолчанию).

    name — логическое имя сервера (попадёт в handshake и в логи).
    Транспорт не указывается явно: FastMCP без явного транспорта работает
    по stdio — именно он нужен, чтобы stdout остался чистым JSON-RPC.
    """
    return FastMCP(name)
