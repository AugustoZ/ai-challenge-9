# Tasks: Диагностика «почему не работает X»

## Волна 1. Модуль diagnose.py

- [x] 1.1 Чистые функции: `extract_topic`, `topic_tokens`, `pick_issues`,
      `format_issues`, `files_block` (без сети).
- [x] 1.2 Сеть: `search_issues` (GitHub Search API, read-only, токен снаружи).
- [x] 1.3 Промпт `DIAGNOSE_SYSTEM` (формат «Вердикт / Проверить», `/no_think`).

## Волна 2. Проводка в bot_responder.py

- [x] 2.1 `_DIAGNOSE_INTENT_RE` и вызов `_handle_diagnose` в `_handle_ask`
      до RAG-пайплайна.
- [x] 2.2 Параметр `system` в `_build_rag_block` (дефолт `RAG_SYSTEM` —
      поведение остальных путей не меняется).
- [x] 2.3 `_handle_diagnose`: issue найдены → список+ссылки, запрос закрыт;
      иначе RAG → вердикт → двухфазный добор файлов через `project_files`.
- [x] 2.4 Строка в `/help`.

## Волна 3. Верификация и деплой

- [x] 3.1 `py_compile` всех затронутых файлов.
- [x] 3.2 Inline-смок чистых функций (стабы отправки, без реального Telegram).
- [x] 3.3 Живой read-only прогон `search_issues` против реального репо.
- [x] 3.4 Определить прод-поллер (хост/контейнер), выкатить в текущий режим,
      проверить лог старта и отсутствие 409 после банера.
