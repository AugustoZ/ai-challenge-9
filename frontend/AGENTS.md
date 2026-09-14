# ФРОНТЕНД «ШТУРМАНА»

React 18 + Vite + TypeScript. Сборка подключена к Gradle без
node-плагина: `npmCi → buildFrontend (npm run verify) → copyFrontend`.
Заполняется по волнам миграции (openspec/changes/simplify-frontend).

## СТРУКТУРА
```
src/
  api/        типы API и SSE-клиент (зеркало Models.kt — синхронизировать вручную)
  lib/        чистые утилиты (format, markdown, storage)
  stores/     zustand-сторы (settings — persist, session, branches)
  components/ переиспользуемые приборы (Dial, Panel, Lamp, Clock…)
  features/   фичевые модули (chat, params, mirror, sessions…)
  styles/     tokens.css (единственный :root) + base.css (body, шрифты, скроллбары)
```

## КОМАНДЫ
```bash
npm run dev                      # dev-сервер Vite; /api проксируется на Ktor (localhost:8080)
npm run verify                   # tsc --noEmit + eslint + vitest + vite build
./gradlew run -PskipFrontend     # бэкенд Ktor без пересборки фронта
./gradlew check                  # полная проверка (включает npm run verify)
```

## ПРАВИЛА
- Цвета — только из `tokens.css`; новые цвета — только через токены (DESIGN.md).
- Без Tailwind, компонентных библиотек и CSS-in-JS; зависимости — только по веской причине.
- Сетевые стримы — только из обработчиков событий; в mount-эффектах — идемпотентные GET с abort в cleanup (StrictMode удваивает эффекты).
- Vitest — только чистые функции (парсер, format, сторы); без jsdom/RTL.

## СМОУК-ЧЕКЛИСТ (перед коммитом)
- [ ] Отправка сообщения — стрим идёт, ответ появляется в журнале
- [ ] Зеркальный обмен — обе колонки отвечают, стоп останавливает одну
- [ ] Панель сессий — список и восстановление сессии
- [ ] Ветки — чипы веток, «+ Развилка», переключение
- [ ] Топливомер — расход топлива за сессию
- [ ] Судья — заключение под ответом
- [ ] Режим ТО/самописец — сырой JSON обмена с моделью
