# Задачи: add-github-pr-notifications

## Волна 1 — уведомления о создании PR

- [x] Модуль `github_watcher.py`: конфиг (секция `github` + env-override), чистые функции (parse/pick/format/context_block), цикл опроса с бэкоффом.
- [x] Таблица `github_prs` в `storage.py` (`known_prs` / `record_pr` / `recent_prs`), дедуп по `(repo, number)`.
- [x] Интеграция в `bot_responder.py`: задача `github-watcher` в `_main`, инжект `context_block()` в `_handle_plain`, справка `/help`.
- [x] Секция `github` в `config.json` (PAT перенесён программно из ai-challenge-9, не печатался) + шаблон в `config.example.json`.
- [x] Dockerfile: COPY `github_watcher.py`; пересборка образа.
- [x] Документация: README (env-таблица), ARCHITECTURE (компоненты), AGENTS.md (структура).
- [x] Верификация: smoke чистых функций + storage round-trip; живой запрос к api.github.com; запуск — baseline-лог наблюдателя, отсутствие 409 после рестарта.

## Волна 2 — AI-ревью PR (реализовано)

- [x] Модуль `pr_review.py`: уведомление админам в личку (ссылка), пофайловые диффы с подсветкой (`<s>` удалённые / `<i>` добавленные), AGENTS.md/SKILL.md из ветки PR документами, сопроводительный текст с именем модели, вердикт LLM (Резюме / Проблемы / Соответствие инструкциям / Вердикт).
- [x] `TgStore.known_users_map()` — резолв админов (Telegram не позволяет писать тем, кто не начинал диалог с ботом).
- [x] Капы промпта `PR_PATCH_CAP`/`PR_DOC_CAP` с явными пометками об обрезке.
- [x] Верификация: selftest полного потока (реальные Telegram-доставка и LLM, фейковые диффы) + прямой прогон промпта — модель нашла намеренный баг, указала файл:строку, сверилась с AGENTS.md.
- [ ] Полный пайплайн ревью (self-hosted runner, GitHub Action, публикация ревью на GitHub) — остаётся в change `add-pr-review-pipeline`.
