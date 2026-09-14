# Спецификация: context-strategy-switcher

> Дельта change `simplify-frontend`. REMOVED: режимы Team и
> Prompt-to-Prompt удалены из приложения — stateless-путей больше нет.

## REMOVED Requirements

### Requirement: No-op в режимах Team и Prompt-to-Prompt

**Причина**: capability `reasoning-modes` этого change оставляет
единственный способ рассуждения — «Агент»; режимы Team и
Prompt-to-Prompt вместе со своим stateless-путём удалены. Стратегия
контекста действует на каждом запросе (см. требование «Сессия всегда
привязана к запросу» в `specs/reasoning-modes/spec.md`).
