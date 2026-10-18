# Задачи: memory-search-ports

## 1. Порты и реализации

- [x] 1.1 `interface MemoryRepository` (list/counts/get/add/update/delete);
      `LongTermMemoryStore` реализует интерфейс
- [x] 1.2 `ScoredMemoryEntry(entry, score)` + `interface MemorySearchEngine`
- [x] 1.3 `KeywordMemorySearch` — точный вынос скоринга из `MemoryRetriever`
      (токены ≥3 рус+лат; title×3, tags×2, content×1; score>0; ties → updatedAt)
- [x] 1.4 `interface EmbeddingProvider` (+ тестовый дубль; прод-реализации нет)
- [x] 1.5 `MemoryRetriever` — оркестратор (repository + search + бюджеты),
      поведение прежнее; `MemoryRetrieverTest` зелёный с точечными правками сборки

## 2. Конфиг-швы и проводка

- [x] 2.1 `MemoryConfig(search?, embedding?)` + `EmbeddingConfig` в Models.kt;
      `AppConfig.memory: MemoryConfig? = null` (nullable с дефолтами)
- [x] 2.2 `MemorySearchEngine.forConfig(memory, repository)`: null/"keyword" →
      KeywordMemorySearch; иное → IllegalArgumentException с русским текстом
- [x] 2.3 Routes.kt: сборка движка из конфига, прокидка в AgentMemory
- [x] 2.4 Тесты: дефолт → keyword; неизвестное значение → ошибка старта

## 3. Контрактные тесты

- [x] 3.1 Абстрактный `MemoryRepositoryContractTest` (CRUD, counts, фильтры
      kind/q, капы, персист roundtrip)
- [x] 3.2 `LongTermMemoryStoreTest` наследует контрактный набор; старые
      специализированные тесты сохранены

## 4. Верификация и документация

- [x] 4.1 Смоук E2E на мок-стенде: сценарий «запомни» + фильтр /api/memory?q=
      — поведение не изменилось
- [x] 4.2 Доки: README.md (подраздел «Развитие памяти» — стадии 1–3), AGENTS.md
- [x] 4.3 Чекбоксы tasks.md проставлены
