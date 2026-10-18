# План: дифф присланных файлов против GitHub main

## Волна 1 — модуль

- [x] `file_diff.py`: `match_paths`, `unified_diff_text`, `looks_binary`, `build_prompt`, `_tree_paths` (кеш), `fetch_main_file`
- [x] `handle_documents(...)`: сборка items → отправка диффов HTML → handoff → вердикт LLM
- [x] Лимит `MAX_FILES=5`, текст отказа `TOO_MANY_MSG`, капы диффов

## Волна 2 — проводка

- [x] `Job.documents`, режим `filediff` до rate-limit
- [x] Поллер: детект `document`, батч-окно 2 с на чат, отказ при >5
- [x] `_download_document` (getFile + file endpoint)
- [x] `_handle_filediff`: settings из `github_watcher.load_settings()`, реф из `FILEDIFF_REF`
- [x] `/help` — упоминание возможности

## Волна 3 — верификация и деплой

- [x] `py_compile` всех затронутых файлов
- [x] Inline-смок: матч путей, дифф, промпт, отказ >5, идентичный файл
- [x] Живой прогон `handle_documents` с фейковыми senders против GitHub (read-only): идентичный/изменённый/отсутствующий файлы
- [x] Рестарт прод-поллера со снятым env, проверка лога (стартовые INFO, 0×409 после баннера)
