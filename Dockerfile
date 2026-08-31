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

# Порт веб-сервера
EXPOSE 8080

# Директория конфигурации (можно смонтировать свой config.json)
ENV CONFIG_FILE=/app/config.json

# Запускаем приложение
CMD ["/app/aiweb/bin/aiweb"]
