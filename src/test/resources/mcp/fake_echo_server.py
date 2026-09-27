"""
Fake stdio echo-сервер для интеграционных тестов MCP-клиента «Штурмана»
(задача 5 плана mcp-integration-shturman).

Назначение: живой, детерминированный MCP-пир для проверки Java SDK 2.0.1
по-настоящему (не на моках). Два тула:
    echo {text}  ->  text = "ECHO:<text>"
    fail {}      ->  поднимает исключение (проверка readable-ошибки у клиента)

Правило (общее с mcp-servers/common.py): stdout = ТОЛЬКО JSON-RPC.
Весь лог — в stderr, иначе stdio-транспорт ломается.
"""

import logging
import sys

from mcp.server.fastmcp import FastMCP

# Все логи — в stderr: stdout зарезервирован за JSON-RPC (stdio-транспорт).
logging.basicConfig(stream=sys.stderr, level=logging.INFO)
logger = logging.getLogger("fake_echo_server")

mcp = FastMCP("fake-echo")


@mcp.tool()
def echo(text: str) -> str:
    """Возвращает строку 'ECHO:<text>'. text — произвольный ввод."""
    logger.info("echo called, text=%r", text)
    result = f"ECHO:{text}"
    logger.info("echo returning %r", result)
    return result


@mcp.tool()
def fail() -> str:
    """Всегда выбрасывает исключение — проверка, что клиент видит читаемую ошибку."""
    logger.info("fail called")
    raise RuntimeError("fake_echo_server: fail tool as requested")


if __name__ == "__main__":
    logger.info("fake_echo_server starting on stdio")
    mcp.run()  # stdio по умолчанию
