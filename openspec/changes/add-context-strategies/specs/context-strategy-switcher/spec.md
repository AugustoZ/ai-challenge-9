# Спецификация: context-strategy-switcher

> Дельта change `add-context-strategies`. Новая capability.

## ADDED Requirements

### Requirement: Явная стратегия в запросе

Поле `contextStrategy` запроса принимает одно из пяти значений: `off`,
`window`, `summary`, `facts`, `branching`. Поле опционально: null не
ошибка, а переход к следующему уровню приоритета. Валидное значение
выигрывает над legacy-флагом и конфигом и определяет, как формируется
контекст запроса.

#### Scenario: Стратегия указана в запросе

- КОГДА запрос несёт `contextStrategy=window`
- ТОГДА контекст формируется стратегией «Окно»
- И ответ в JSON содержит `contextStrategy` = "window" (echo)

### Requirement: Приоритет разрешения стратегии

Стратегия решается на каждый запрос в порядке убывания приоритета:
явное поле `contextStrategy` запроса > legacy-флаг `compressionEnabled`
> значение конфига `contextStrategy`. Явный валидный id всегда
выигрывает. При отсутствии `contextStrategy` флаг
`compressionEnabled=true` даёт стратегию summary с поведением
байт-в-байт идентичным поведению до этого change (consume-and-store,
`summaryTokens`/`summaryApplied`). При отсутствии и `contextStrategy`,
и `compressionEnabled` действует значение из конфига.

#### Scenario: Явная стратегия перекрывает legacy-флаг

- КОГДА запрос несёт `contextStrategy=window` и `compressionEnabled=true`
- ТОГДА применяется стратегия «Окно»
- И legacy-флаг игнорируется: merge сводки на этом запросе не выполняется

#### Scenario: Legacy-флаг без стратегии

- КОГДА запрос не несёт `contextStrategy`, а `compressionEnabled=true`
- ТОГДА применяется стратегия «Сводка»
- И поведение запроса байт-в-байт идентично поведению до этого change
  (сводка, consume-and-store, метрики summary)

### Requirement: Неизвестный идентификатор стратегии — ошибка

Значение `contextStrategy` вне набора off|window|summary|facts|branching
принимается как ошибка запроса: сервер отвечает HTTP 400 с текстом,
называющим неизвестный идентификатор и список допустимых значений.
Запрос не обрабатывается, сессия не изменяется.

#### Scenario: Неизвестное значение

- КОГДА запрос несёт `contextStrategy=sliding`
- ТОГДА сервер отвечает 400 с текстом про неизвестный id и допустимый набор
- И запрос не обрабатывается, сообщения сессии не добавляются

### Requirement: Echo разрешённой стратегии в ответе

Поле `ChatResponse.contextStrategy` содержит стратегию, разрешённую для
запроса (с учётом приоритета). Ключ присутствует в JSON-ответе всегда —
в том числе при стратегии off, без упущения по умолчанию.

#### Scenario: Стратегия в ответе

- КОГДА запрос обработан с любой разрешённой стратегией
- ТОГДА JSON-ответ содержит ключ `contextStrategy`
- И его значение совпадает со стратегией, на которой был сформирован контекст

### Requirement: No-op в режимах Team и Prompt-to-Prompt

В режимах Team и Prompt-to-Prompt стратегия не действует: эти пути
stateless и не читают `contextStrategy`; поведение этих режимов не
меняется этим change (аналог компрессии в прошлой итерации).

### Requirement: Конфиг-ключи стратегии

Конфиг получает ключи `contextStrategy` (по умолчанию `off`) и
`branchCheckpointEvery` (по умолчанию 5) в `config.json` и
`config.example.json` синхронно; env-переменных для них не
добавляется. Значение конфига `contextStrategy` — нижний уровень
приоритета: оно действует, когда запрос не несёт ни явной стратегии, ни
legacy-флага.

#### Scenario: Стратегия из конфига

- КОГДА запрос не несёт `contextStrategy` и `compressionEnabled`
- И конфиг содержит `contextStrategy=window`
- ТОГДА применяется стратегия «Окно» из конфига
- И `branchCheckpointEvery` берётся из конфига (по умолчанию 5)
