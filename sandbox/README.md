# Sandbox-исполнение инструментов (P2, волна 1)

Изолированный контейнер для инструментов с эффектами: `run_code` (исполнение
Python-кода) и `read_file` (чтение файлов из рабочей папки). Никогда не
выполняется in-process в aiweb — см. PLAN.md, «Sandbox-требования к инструментам».

## Состав

- `server.py` — HTTP-сервер на stdlib Python (ThreadingHTTPServer, порт 8080
  контейнерной сети), без pip-зависимостей.
- `Dockerfile` — образ на `python:3.12-alpine`, запуск от uid 65534 (nobody).
- `test_server.py` — smoke-тесты (stdlib unittest, поднимают сервер на эфемерном порту).

## API

### `POST /run` — исполнение кода

```json
{"language": "python", "code": "print('привет')", "timeoutSeconds": 15}
```

- успех: `200 {"ok": true, "exitCode": 0, "stdout": "…", "stderr": "…", "durationMs": 123}`
  (ненулевой `exitCode` — тоже `ok: true`, это штатное завершение);
- ошибка песочницы: `200 {"ok": false, "error": "текст"}` — таймаут
  («Превышен таймаут выполнения (Ns)»), синтаксическая ошибка,
  язык кроме `python` («Поддерживается только python»);
- исполнение: `python3 -c <code>`, CWD = `/sandbox/workspace`;
- серверный жёсткий потолок таймаута — 60 с (берётся `min(клиентский, 60)`);
- stdout/stderr капятся на 64 КиБ каждый, далее — маркерная строка усечения.

### `POST /read` — чтение файла из рабочей папки

```json
{"path": "report.txt"}
```

- успех: `200 {"ok": true, "content": "…", "truncated": false}` (кап 2 МиБ,
  далее `truncated: true`; UTF-8 с заменой некорректных байтов);
- отказ: абсолютные пути, любые компоненты `..`, симлинки, разрешающиеся
  наружу workspace («Путь вне рабочей папки запрещён»); нет файла —
  «Файл не найден».

Прочее: некорректный JSON / нет обязательного поля → `400 {"ok": false,
"error": "Некорректный запрос"}`; неизвестный путь → `404`; тело запроса
больше 1 МиБ → `400`. Сервер слушает `0.0.0.0:8080`, порты наружу не
публикуются — только внутренняя docker-сеть.

## Модель безопасности

| Слой | Механизм |
|---|---|
| Процесс | non-root (uid 65534), `no-new-privileges` |
| ФС контейнера | `read_only: true` (корневая ФС), записываемое — только `/tmp` (tmpfs) и volume `sandbox-work:/sandbox/workspace` |
| Возможности | `cap_drop: ALL`, docker socket не монтируется |
| Сеть | сеть `sandbox` с `internal: true` — полный запрет egress (строжайший allowlist = пустой); aiweb ходит в LLM API через обычную сеть `default` (двухсетевая схема) |
| Дочерний процесс | `resource`-лимиты: RLIMIT_AS 512 МиБ, RLIMIT_CPU = таймаут+2 с, RLIMIT_FSIZE 10 МиБ, RLIMIT_NPROC 64; минимальное окружение (PATH, HOME=/tmp, LANG); `start_new_session` + `killpg` — по таймауту убивается вся группа процессов |
| Таймауты | основной — на стороне оркестратора (aiweb, HTTP-таймаут); сервер дублирует: кап 60 с и kill группы |
| Пути | `/read` только внутри `/sandbox/workspace`: resolve + проверка «внутри», отказ `..` и симлинков наружу |

Нюанс: контейнерный `mem_limit` (768 МиБ) стоит выше RLIMIT_AS (512 МиБ),
чтобы код получал управляемый `MemoryError`, а не внезапный OOM-kill.

## Проверка

Локально (без Docker, тесты поднимают сервер сами):

```bash
python3 sandbox/test_server.py
```

В Docker:

```bash
docker compose --profile sandbox up -d
# curl изнутри сети (например, из контейнера aiweb):
docker compose exec aiweb curl -s http://tool-sandbox:8080/run \
  -H 'Content-Type: application/json' \
  -d '{"language":"python","code":"print(2+2)","timeoutSeconds":10}'
```

Ожидается: `{"ok": true, "exitCode": 0, "stdout": "4\n", …}`.
