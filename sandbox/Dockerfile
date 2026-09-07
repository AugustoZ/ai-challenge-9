# Sandbox-образ для инструментов с эффектами (run_code / read_file).
# Минимальный рантайм python:3.12-alpine, только stdlib — pip не нужен.
FROM python:3.12-alpine

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# Рабочая папка sandbox: единственное место, доступное коду на запись/чтение.
# Владелец — uid/gid 65534 (nobody): запуск от non-root; named volume
# sandbox-work при первом монтировании наследует эту владельческую запись.
RUN mkdir -p /sandbox/workspace && chown -R 65534:65534 /sandbox

COPY server.py /sandbox/server.py
RUN chmod 644 /sandbox/server.py

USER 65534

EXPOSE 8080

CMD ["python3", "/sandbox/server.py"]
