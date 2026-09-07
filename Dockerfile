# ---------- Стадия сборки ----------
FROM gradle:8.9-jdk22 AS build

WORKDIR /app

# Копируем файлы сборки и кэшируем зависимости
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
COPY gradlew gradlew

# Устанавливаем права и собираем зависимости (кэш слоёв)
RUN chmod +x gradlew

# Копируем исходники
COPY src src

# Собираем дистрибутив приложения
RUN ./gradlew installDist --no-daemon

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
