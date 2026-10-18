# Tasks: Доступ бота к файлам проекта

## Волна 1 — модуль project_files.py

- [x] `project_root()`: env `PROJECT_FILES_ROOT` → дефолт — корень ai-challenge-9.
- [x] `find_files(query)`: обход дерева (скрип служебных каталогов), матч токенов по пути, ранжирование.
- [x] `read_text(relpath)`: containment по normpath без resolve, скрип бинарников и NUL-байтов, потолок размера.
- [x] `resolve_request(request)`: прямой путь → срез префикса корпуса `ru/<repo>/` → поиск по имени файла.

## Волна 2 — проводка в bot_responder.py

- [x] Импорт модуля, `_FILE_INTENT_RE` + `_send_file_intent` до RAG в `_handle_ask`.
- [x] `_WANTS_FILE_RE` + `_FILE_FETCH_RULE` в `_build_rag_block` (только для «файловых» вопросов).
- [x] `_FILE_RE` + `_take_file_requests`, отправка документов после генерации, приписка при неудаче.
- [x] `_send_document`: параметр `reply_to`.
- [x] Справка `/help` и приветствие: «покажи файл <имя>» и «покажи, как устроено X».

## Волна 3 — верификация и деплой

- [x] `py_compile` всех затронутых файлов.
- [x] Inline-смок: find_files/read_text/resolve_request на живом дереве, containment-отказы, `_take_file_requests`.
- [x] Рестарт прод-поллера (pid 61223, env живого процесса), проверка лога старта (нет 409 после баннера).
