# ---------- Стадия сборки фронтенда ----------
# dist фронтенда (React+Vite, чейндж simplify-frontend, волна 7) собирается
# НА ХОСТЕ gradle-цепочкой (npmCi → buildFrontend → copyFrontend кладёт его в
# build/frontend-static/static). В образе gradle:8.9-jdk22 нет npm, а прокси
# Docker нестабилен (см. комментарий ниже) — поэтому Node-экосистему в образ
# не тащим: дистриб копируется из контекста. Перед docker build выполните
# ./gradlew build (или cd frontend && npm run build).
FROM gradle:8.9-jdk22 AS build

WORKDIR /app

# Копируем файлы сборки и кэшируем зависимости
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
COPY gradlew gradlew

# Кэш gradle с хоста (доп. контекст gradle-host из docker-compose.yml — ~/.gradle):
# дистрибутив wrapper'а с маркером .ok и maven-зависимости уже на машине, поэтому
# сборка не зависит от доступности services.gradle.org и Maven Central через
# прокси Docker (кейс: у http.docker.internal:3128 таймаут на services.gradle.org).
COPY --from=gradle-host wrapper/dists/gradle-8.9-bin /root/.gradle/wrapper/dists/gradle-8.9-bin
COPY --from=gradle-host caches/modules-2 /root/.gradle/caches/modules-2

# Устанавливаем права и собираем зависимости (кэш слоёв)
RUN chmod +x gradlew

# Копируем исходники
COPY src src

# Дистриб фронтенда — прямо в каталог ресурсов classpath (см. build.gradle.kts,
# srcDir build/frontend-static/static). -PskipFrontend отключает npm-цепочку
# Gradle: dist уже собран на хосте и лежит в контексте.
COPY frontend/dist build/frontend-static/static

# Собираем дистрибутив приложения (фронтенд пропускаем — уже внутри)
RUN ./gradlew installDist --no-daemon -PskipFrontend

# ---------- Стадия Python-окружения для MCP-серверов ----------
# config.json запускает MCP-серверы как mcp-servers/.venv/bin/python (относительно
# cwd /app). venv создаём и наполняем здесь, а не в финальной стадии: дистрибуция
# скриптов + pip-деп войны в юзер-слое не нужен. Симлинки venv указывают на
# /usr/local/bin/python3.12 именно этой стадии — ниже он копируется в финал.
FROM python:3.12-slim-bookworm AS mcp-python

RUN python -m venv /app/mcp-servers/.venv
# Только mcp + httpx (питон-серверы их единственные зависимости; см.
# mcp-servers/requirements.txt). Версии фиксируем под v1-API, который используют
# серверы (from mcp.server.fastmcp import FastMCP): mcp 2.x переименовал FastMCP
# в MCPServer и ломает import — поэтому mcp<2 / именно 1.30.0 (как в .venv хоста).
# Telethon больше не нужен (Bot API).
RUN /app/mcp-servers/.venv/bin/pip install --no-cache-dir "mcp==1.30.0" "httpx==0.28.1"

# ---------- Стадия запуска ----------
FROM eclipse-temurin:22-jre

WORKDIR /app

# Копируем собранный дистрибутив
COPY --from=build /app/build/install/aiweb /app/aiweb

# Непривилегированный системный пользователь (без shell) — контейнер не работает от root.
# Каталог данных готовим заранее и отдаём ему владельца (P1-персистентность сессий).
RUN useradd --system --no-create-home --shell /usr/sbin/nologin aiweb \
    && mkdir -p /app/data \
    && chown -R aiweb:aiweb /app/data

# Python 3.12-рантайм из mcp-python (обе стадии — Debian bookworm, glibc-совместимы).
# Это делает валидным путь интерпретатора, на который смотрят симлинки venv.
COPY --from=mcp-python /usr/local /usr/local

# Уже собранный venv (/app/mcp-servers/.venv) — владелец aiweb, чтобы непривилегированный
# пользователь мог исполнять питон и писать свои файлы.
COPY --from=mcp-python --chown=aiweb:aiweb /app/mcp-servers/.venv /app/mcp-servers/.venv

# Исходники MCP-серверов (config.json запускает их по этому пути).
# tg_server и хостовый bot_responder переехали в standalone-проект
# shturman-bot (смежный каталог Projects/shturman-bot) — в контейнере
# остаётся только wiki_server + common.
COPY --chown=aiweb:aiweb mcp-servers/wiki_server.py mcp-servers/common.py /app/mcp-servers/

# Дальше всё выполняется от непривилегированного пользователя
USER aiweb

# Порт веб-сервера
EXPOSE 8080

# Директория конфигурации (можно смонтировать свой config.json)
ENV CONFIG_FILE=/app/config.json

# Каталог данных (P1-персистентность сессий). В P0 каталог смонтирован, но пуст —
# сессии остаются в памяти (InMemorySessionStore)
ENV AI_DATA_DIR=/app/data

# Запускаем приложение
CMD ["/app/aiweb/bin/aiweb"]
