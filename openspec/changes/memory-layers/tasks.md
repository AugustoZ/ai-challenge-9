# Задачи: memory-layers

## 1. Пакет памяти (бэкенд)

- [x] 1.1 Типы `WorkingEntry`, `MemoryEntry` (+ константы капов) в `com.example.aiweb.memory`
- [x] 1.2 `WorkingMemoryStore`: файлы `dataDir/working/{sessionId}.json`,
      атомарный персист (tmp+rename), synchronized, CRUD, pruneOrphans
- [x] 1.3 `LongTermMemoryStore`: файл `dataDir/memory/longterm.json`, CRUD,
      счётчики по видам, фильтр kind/q
- [x] 1.4 `MemoryRetriever`: keyword-score (токены ≥3, рус+лат; title×3, tags×2,
      content×1), top-6, profile кап 10, бюджеты 1800/2400 символов
- [x] 1.5 Тесты: CRUD, капы, персист roundtrip, сироты, ретривер (пусто→null,
      релевантность, обрезка бюджета)

## 2. Инструменты агента и инъекция

- [x] 2.1 `TaskNotesTool` (add/update/remove/list; привязка к сессии), `MemorySaveTool`, `MemoryRecallTool` — валидация аргументов, ошибки без исключений
- [x] 2.2 `ToolRegistry.augmentedWith(extra)`; per-request до в `handleAgent` при `memoryEnabled`
- [x] 2.3 `ChatRequest.memoryTools: Boolean?` (null → ВКЛ); прокидывание из роутов
- [x] 2.4 Инъекция: system-сообщение с блоком памяти ПОСЛЕ trim семени; пусто — нет сообщения
- [x] 2.5 Дисциплина в системном промте (AgentLoop): долговременная — только по явной просьбе
- [x] 2.6 Тесты: блок в seed при записях; пусто — без блока; memoryTools=false — без инструментов/блока; инструменты валидируют аргументы

## 3. HTTP API и cleanup

- [x] 3.1 Роуты рабочей памяти: GET/PUT `/api/session/{id}/working`, DELETE `.../working/{entryId}`, DELETE `.../working`
- [x] 3.2 Роуты долговременной: GET/POST `/api/memory`, PUT/DELETE `/api/memory/{id}` (валидация kind/капов → 400)
- [x] 3.3 `GET /api/memory/overview?sessionId=` — счётчики трёх слоёв
- [x] 3.4 Удаление сессии → удаление WM-файла; prune сирот при старте стора
- [x] 3.5 Тесты: статус-коды, валидация, cleanup, overview

## 4. Фронтенд-фундамент

- [x] 4.1 Типы `MemoryKind`, `WorkingEntry`, `MemoryEntry`, `MemoryOverview` + ответы в `api/types.ts`
- [x] 4.2 `putJson` в `api/client.ts`; функции fetchWorking/saveWorkingEntry/deleteWorkingEntry/clearWorking/fetchMemory/addMemoryEntry/updateMemoryEntry/deleteMemoryEntry/fetchMemoryOverview
- [x] 4.3 `stores/memory.ts` (zustand): состояние + действия загрузки/CRUD
- [x] 4.4 Тесты (Vitest): store-действия с моками клиента

## 5. UI памяти

- [x] 5.1 Кнопка «Память» в верхней полосе (`App.tsx`) → оверлей с вкладками «Краткосрочная / Рабочая / Долговременная»
- [x] 5.2 Вкладка «Рабочая»: список, добавление, правка, удаление, «Очистить» (подтверждение)
- [x] 5.3 Вкладка «Долговременная»: фильтр по виду, поиск, CRUD; бейдж источника (user/agent)
- [x] 5.4 Вкладка «Краткосрочная»: read-only статистика (ходы, сводка, стратегия)
- [x] 5.5 Настройка «Инструменты памяти агента» в SettingsPanel (default ВКЛ) → `ChatRequest.memoryTools`
- [x] 5.6 Refresh рабочей памяти и обзора после финала ответа агента (`useChatStream`)
- [x] 5.7 Тесты (Vitest): чистые функции (группировка/формат) компонентов

## 6. Интеграция и документация

- [x] 6.1 E2E на мок-стенде: сценарии из spec.md через UI + curl
- [x] 6.2 Доки: README.md (память: слои, настройки, API), openspec/project.md, AGENTS.md
- [x] 6.3 Чекбоксы задач проставлены; `openspec validate` (если доступно)
