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
