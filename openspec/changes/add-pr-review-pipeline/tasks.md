# Задачи: add-pr-review-pipeline

Все пункты TDD; агент не коммитит — пользователь коммитит вручную.
Решения и поток данных — `proposal.md`.

## Волна 1 — ручное ревью (без Action)

- [ ] 1.1 Тул `rag_search` (`pipelineExtraTools`): аргументы `{query, k?}`,
      обёртка над `RagService`, fail-open (сайдкар недоступен → ok=true,
      текст «RAG недоступен, ревью без базы знаний»), кап вывода 20 000
      симв.; тесты: успех, fail-open, кап
- [ ] 1.2 Параметры запуска в `POST /api/pipeline/run`: `{repo, pr}`
      подставляются в плейсхолдеры аргументов сценария (не только
      `${steps.N.result}`); тесты на подстановку и валидацию (pr — число)
- [ ] 1.3 Сценарий `config/pipelines/pr-review.json`: s1 diff → s2 files →
      s3 rag_search → s4 summarize (формат «Баги / Архитектурные проблемы /
      Рекомендации», русский, пометка об усечении diff) → s5
      create_and_submit_pull_request_review {body, event: COMMENT}
- [ ] 1.4 Дедупликация: `<dataDir>/pipeline/pr-review/{repo}#{pr}.json`
      (repo, pr, headSha, reviewUrl); повторный запуск с тем же headSha →
      409 `skipped`; тесты на запись/чтение/конфликт
- [ ] 1.5 Smoke на реальном PR (ручной): открыть ревью-пайплайн на тестовом
      PR репозитория проекта, проверить появление review в GitHub и статус
      `GET /api/pipeline/status`

## Волна 2 — мост GitHub Action

- [ ] 2.1 Self-hosted runner на Mac (labels: `self-hosted,macos`), запуск
      как сервис; инструкция в README раздела пайплайнов
- [ ] 2.2 `.github/workflows/pr-review.yml`: `on: pull_request` (types:
      opened, synchronize), `runs-on: self-hosted`, шаги: curl
      `POST /api/pipeline/run` (202 + id запуска) → poll
      `GET /api/pipeline/status` с ретраями (409/«не terminal» → backoff
      до 3 попыток) → `::error::` с текстом failed-шага; connection
      refused → внятное «Штурман не запущен»
- [ ] 2.3 Проверка сквозная: PR в тестовом репо → ревью появляется,
      повторный push → skipped, остановленный бот → красный Action
      с диагнозом

## Волна 3 — качество (опционально)

- [ ] 3.1 Пофайловая стратегия для больших diff: `get_pull_request_files`
      → выбор файлов по размеру патча → конкатенация с бюджетом
- [ ] 3.2 Триггер по лейблу `ai-review` (только помеченные PR ревьюятся)
- [ ] 3.3 `reports/pr-review-samples.md`: 3–5 реальных ревью, оценка
      качества, правки инструкции summarize
