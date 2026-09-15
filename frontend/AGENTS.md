# ФРОНТЕНД «ШТУРМАНА»

React 18 + Vite + TypeScript. Сборка подключена к Gradle без
node-плагина: `npmCi → buildFrontend (npm run verify) → copyFrontend`.
После flip (волна 7) прод-сборка (`dist`) отдаётся Ktor с «/» —
index.html без кэша, хэшированные `/static/assets/*` — immutable;
vanilla-версия из `src/main/resources/static` удалена (история — в git).
Миграция завершена (openspec/changes/simplify-frontend); «порт из
static/» в таблицах ниже — ссылка на исходник в git-истории.

## СТРУКТУРА
```
src/
  api/        типы API (types.ts — зеркало Models.kt и prefs/UserPreferences.kt,
              синхронизировать вручную), SSE-клиент (sse.ts: parseFrame + streamChat),
              client.ts (postJson/getJson/putJson/deleteJson, X-Auth-Token и 401 — централизованно)
  lib/        чистые утилиты (format, markdown, storage, validators — зеркало серверной валидации)
  stores/     zustand-сторы (settings — persist, session, branches,
              auth — sessionStorage, БЕЗ persist-мидлвир)
  components/ приборы: Dial, AnnunciatorGroup, Lamp, Clock, Overlay, StatusLine,
              Drawer + dialMath.ts, overlayStack.ts
  features/   фичевые модули (chat, send, settings, strategy, mirror,
              sessions, branches, maintenance, judge, trace, fuel,
              auth, onboarding)
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
- Токен сессии — только `sessionStorage` (`shturman.auth.v1`), НЕ localStorage: вкладка закрылась — вход сброшен, перезапуск сервера — повторный вход (токен в памяти сервера).
- Заголовок `X-Auth-Token` подставляют только `api/client.ts` и `api/sse.ts`; правила валидации — только в `lib/validators.ts` (зеркало сервера; единый источник лимитов — `api/types.ts`).

## СМОУК-ЧЕКЛИСТ (перед коммитом)
- [ ] Отправка сообщения — стрим идёт, ответ появляется в журнале
- [ ] Зеркальный обмен — обе колонки отвечают, стоп останавливает одну
- [ ] Панель сессий — список и восстановление сессии
- [ ] Ветки — чипы веток, «+ Развилка», переключение
- [ ] Топливомер — расход топлива за сессию
- [ ] Судья — заключение под ответом
- [ ] Режим ТО/самописец — сырой JSON обмена с моделью
- [ ] Пустой sessionStorage — гейт входа «ВВЕДИТЕ ПИН пользователя»
- [ ] Неверный ПИН — русская ошибка, поле очищается; 3-я неудача — кулдаун 429 с текстом сервера
- [ ] Создание пользователя (имя ≤25 + ПИН) — авто-вход → визард 7 вопросов
- [ ] Визард: Esc — ничего не сохранено (серверные дефолты), «Сохранить» — PUT /api/preferences
- [ ] Reload с токеном — сразу консоль (restore); перезапуск бэкенда — снова гейт
- [ ] Чип «Сменить пользователя»/«Выйти» — гейт; у нового пользователя чистый журнал и чужих данных нет

## КОМПОНЕНТЫ (волна 3a — примитивы приборки)

Порт 1:1 из static/ (дизайн заморожен; источник — static/style.css,
static/script.js, static/index.html). Примитивы презентационные:
состояние через props, сторы не импортируют, CSS Modules, цвета —
только токены.

| Файл | Порт из static/ | Примечания |
|---|---|---|
| `components/dialMath.ts` | script.js L612–686 (GAUGE_START/SWEEP, fmtFuel, DIALS, zoneLevel, valueToT, tToValue, polar, arcBetween, arcPath) + clamp L614 | чистые функции; тесты — `dialMath.test.ts` (туда-обратно, границы зон) |
| `components/Dial.tsx` + `.module.css` | index.html L95–164; script.js L688–827 (initDial, setControlValue, pointerToValue, wheel, тултипы «?»); style.css L361–639 | скрытый `<input type="range">` — клавиатура/PageUp/PageDown/`aria-valuetext`; pointer-drag; колесо ±2% дуги; стрелка рендерится из props; GAUGE_START=225/SWEEP=270 не менять |
| `components/AnnunciatorGroup.tsx` + `.module.css` | index.html L58–77, L210–227; script.js L1047–1091, L1124–1167; style.css L274–359 | APG radio: roving tabindex, стрелки/Home/End (выбор + фокус), подсказка выбранной клавиши — в строке `.hint`; `variant="strategy"` — нечётный хвост на всю ширину |
| `components/Lamp.tsx` + `.module.css` | style.css L1217–1236, keyframes lamp L1179–1186 | цвет/мигание через props |
| `components/Clock.tsx` + `.module.css` | script.js L82–90; style.css L122–128 | тик 1000 мс, cleanup в useEffect; формат `ru-RU` |
| `components/Overlay.tsx` + `.module.css` | index.html L345–387; script.js L1382–1405, L1689–1700; style.css L1998–2136, keyframes entry-in L861–870, ≤600px L2686–2708 | ESC (верхний слой), клик по фону, фокус: клавиша закрытия ↔ открывший; без фокус-ловушек — как в ваниле |
| `components/StatusLine.tsx` + `.module.css` | index.html L318–321; script.js L93–97; style.css L1200–1246 | kind ready/busy/fault на `data-kind`; у отказа — title с текстом |
| `components/Drawer.tsx` + `.module.css` | script.js L1185–1228; style.css L190–256, L1595–1610, ≤900px L2740–2764 | backdrop, ESC, возврат фокуса открывшему; grid-area панели — за макетом App (волна 5) |
| `components/overlayStack.ts` | приоритет ESC script.js L1216–1227 | стек слоёв: ESC закрывает только верхний (оверлей → drawer) |

Контракт a11y шкал (static/AGENTS.md) сохранён полностью: скрытый range —
источник клавиатуры, `aria-valuetext` озвучивает показание, hit-область «?»
25px (WCAG 2.5.8), hoverable-тултип с мостом (WCAG 1.4.13).

## КОМПОНЕНТЫ (волна 3b — рендеры данных: журнал, судья, трасса, топливомер)

Порт 1:1 из static/ (дизайн заморожен). Презентационные фичевые модули:
состояние только через props, сторы не импортируются, CSS Modules
(compound-селекторы внутри одного модуля), цвета — только токены.
`dangerouslySetInnerHTML` — только на выходе markdown; user/system-записи
рендерятся текстом (экранирование React = textContent ванили).

| Файл | Порт из static/ | Примечания |
|---|---|---|
| `lib/markdown.ts` | script.js L101–117 (renderMarkdown, highlightCode) | `renderMarkdown(text)` — чистая (marked v18, `async:false`); вторая replace-ветка ванили — исторический no-op, сохранена; `highlightCodeBlocks(container)` — вызывать из useEffect |
| `styles/tokens.css` + `styles/base.css` (дополнение) | style.css L1090–1149 (hljs-тема) | два сырых hex ванили (#9ad98f, #e0c88f) вынесены в токены `--hljs-string`/`--hljs-attr`; тема глобальная — классы расставляет hljs |
| `features/chat/Entry.tsx` + `.module.css` | script.js L136–168 (buildEntryHead, buildSummaryBadge), L232–250, L252–317 (addMessage/addBotMessage), L334–352; style.css L847–1088, L2404–2422 | тип `JournalEntry` {id, kind: user\|bot\|system, text, meta:{modeLabel?, time, tokenData?}, events?, judge?}; шапка — tag+части с `fuel-digits`; порядок: шапка → бейдж «Сводка применена» → пузырь → трасса → судья |
| `features/chat/Journal.tsx` + `.module.css` | index.html L271–281; style.css L826–845 | список записей; пустой журнал = приветственная запись WELCOME_MARKUP (дословно из index.html, сырой HTML — не markdown) |
| `features/chat/expand.ts` + `ExpandToggle.tsx` + `.module.css` | script.js L170–230 (EXPAND_SLACK, expandOverflow, syncExpandToggle, buildExpandToggle); style.css L2424–2466 | `isOverflowing(client, scroll)` — чистая (тесты); ResizeObserver, `aria-controls`/`aria-expanded`, текст «Развернуть/Свернуть»; класс expanded ставится колодцу через classList (как в ваниле) |
| `features/judge/JudgeReport.tsx` + `.module.css` | script.js L356–428 (judgeScoreClass, buildJudgeReport); style.css L1720–1832 | классы оценок ok/mid/low (7..10/4..6/1..3); критерии Факты/Качество/Скорость/Ресурсоёмкость; verdict; raw-колодец с ExpandToggle — только если нет ни одного критерия |
| `features/trace/chip.ts` + `DownloadChip.tsx` + `AgentTrace.tsx` + `.module.css` | script.js L447–459 (buildDownloadChip), L470–582 (buildAgentEventRow, buildAgentTrace, createLiveTrace, appendLiveAgentEvent); style.css L1834–1965 | `downloadChipPath(json)` — чистая (тесты); чип — кнопка с `onDownload(path)` (в ваниле `<a href="/api/files/…">`); `live`-трасса: `open`, маячок на заголовке, «идёт работа» → счётчик событий; всё содержимое строк — textContent (ненадёжный ввод LLM) |
| `features/fuel/scale.ts` | script.js L1407–1456 (TOKEN_TARIFF, fmtMoney, niceScale) | чистые (тесты); тариф $0,00000036/токен |
| `features/fuel/chartMath.ts` | script.js L1459–1594 (buildFuelAxis, buildTokenChart, buildPriceChart) | чистые модели данных; геометрия холстов 560×210, L=44/56 — не менять |
| `features/fuel/FuelMeter.tsx` + `.module.css` | script.js L1596–1687 (buildFuelCell, buildFuelLegend, buildFuelChartBlock, renderFuelModal); style.css L2222–2232, L2234–2402 | рисуется внутри `components/Overlay`; окно уже самописца (760×660) — через `:has(.fuelBody)` на `[role="dialog"]`, т.к. класс модалки захэширован; пустое состояние — честное «Топливо ещё не расходовалось…» |

React-ключи: записи — `entry.id`, строки трассы — индекс (события только
дописываются). Тесты волны 3b: `markdown.test.ts`, `expand.test.ts`,
`chip.test.ts`, `fuel/scale.test.ts`, `fuel/chartMath.test.ts` (node, без
jsdom). В App.tsx — демо-секции «Журнал (демо)» и «Топливомер (демо)».

## ПАНЕЛЬ ПРИБОРОВ (волна 4 — настройки)

Порт 1:1 из static/ (дизайн заморожен). Все записи — только через
`settingsStore` (persist `shturman.settings.v1` выполняет persist-middleware);
прямого доступа к localStorage в features/ нет. Клавиши-тумблеры
(«Рассуждения», «Судья», «Сравнение») — те же классы банка табло из
`AnnunciatorGroup.module.css`, состояние — `aria-pressed` (как в ваниле).

| Файл | Порт из static/ | Примечания |
|---|---|---|
| `features/settings/models.ts` | script.js L925–980 | чистые: `splitModelValue` («providerId/modelId» → {provider, model} для ChatRequest), `buildModelSelect` (optgroup-группы, пустые группы и не-строки пропускаются; без групп — единственный вариант дефолта), `modelSelectValues`, `resolveModelValue` (сохранённая модель, если ещё в списке, иначе дефолт); тесты — `models.test.ts` |
| `features/settings/useModels.ts` | script.js L930–971 (`loadModels`) | mount-эффект — идемпотентный GET `/api/models` (`api/client.getJson`) с `AbortController.abort()` в cleanup; StrictMode: первый запрос абортируется тихо, второй довозит; `retry()` — повтор по клавише; ошибка/пустой список — состояние «Список моделей недоступен…» + «Повторить» |
| `features/settings/SettingsPanel.tsx` + `.module.css` | index.html L55–261 (sidebar-body + футер сброса), script.js L812–838 (тултипы «?» строк), L925–980 (селект), L982–1006 (тумблеры), L1169–1183 (сброс), style.css L258–272, L363–367, L537–714, L769–815 | секции «Параметры модели» (селект + 3 шкалы `DIALS` + стоп-фраза + «Рассуждения»), «Приёмка» («Судья»), «Память» (Стратегия `variant="strategy"` + «Сравнение»); стоп-фраза пишется в стор обрезанной по blur (ванильный change); устаревшая сохранённая модель заменяется дефолтом при загрузке списка; подписи/подсказки — дословно из index.html |

Не переносится сознательно: «Способ рассуждения» (режимы удалены в волне 1),
панель фактов (волна 5 — данные из ChatResponse/session), «Обслуживание»
(волна 6), grid-area панели в каркасе (волна 5, как и у `Drawer`).
Клавиша сброса вызывает `settingsStore.reset()` — модель возвращается в ''
(«по умолчанию»), а не к первому варианту селекта, как в ваниле: '' и есть
дефолт провайдера на проводе. В App.tsx — демо-секция «Панель приборов
(демо)»: `SettingsPanel` внутри `components/Drawer`.

## КОНВЕЙЕР ОТПРАВКИ (волна 5 — send pipeline + реальный каркас)

Порт static/script.js L2495–2792 и каркас index.html (демо-предпросмотр
волн 3b/4 в App.tsx заменён). Строгие правила (spec «SSE-конвейер»,
«Правила эффектов»; tasks.md «Подводные камни»):

- AbortController и requestId — в `useRef` (identity запроса, не UI-состояние;
  ванильный `activeRequest`); `inFlight` — UI-отражение для клавиш.
- Стримы открываются только из обработчика клавиши «Передать»: в
  `useChatStream` mount-эффектов нет вовсе.
- Все записи в сторы/директиву гвардятся requestId (`isCurrent()`):
  события устаревшего запроса (после «Стоп» или новой передачи) не пишут.
- «Стоп» = `POST /api/cancel {requestId}` (fire-and-forget) + `abort()`;
  AbortError оседает как «Передача остановлена», не как ошибка.
- Фолбэк: `streamChat` вернул `{ok:false}` (не 2xx / ноль кадров) или обрыв
  сети до первого события → тот же запрос блокирующим `postJson('chat', …)`
  без requestId (его в ваниле получает только стрим); обрыв после событий —
  честный отказ «Связь с сервером прервалась во время передачи: …».

| Файл | Порт из static/ | Примечания |
|---|---|---|
| `stores/session.ts` (расширение) | script.js L232–352 (точки записи журнала), L2512 (activeRequest-подход) | `entries: JournalEntry[]` + `appendEntry` (id — монотонный счётчик вне стора, clearEntries его не сбрасывает), `patchEntry`, `appendTraceEvent`, `removeEntry`, `clearEntries`; правки по id со structural sharing; тип `JournalEntry` импортируется из `features/chat/Entry` |
| `features/chat/Entry.tsx` (расширение) | script.js L586–610 (showTyping, hideTyping, setStreamStatus); style.css L1151–1198 | `JournalEntry.typing?: { statusText }` — запись-заглушка: три мигающие лампы (`@keyframes typing-lamp`, своё имя — CSS-модули хэшируют keyframes) вместо пузыря, строка состояния при непустом тексте, живая трасса `AgentTrace live` — лениво, с первого события агента |
| `features/send/request.ts` | script.js L852, L975–980, L2748–2781, L2750–2752, L93–97 | чистые: `buildChatRequest` (составная модель через `splitModelValue`, стоп-фраза обрезается при чтении и пустая → null, `branchId` только при branching+sessionId+activeBranchId), `busyDirective`, `statusKindFor`, `needsFallback`, `streamAbortDecision`; тесты — `request.test.ts` |
| `features/send/useChatStream.ts` | script.js L2495–2792 (makeRequestId, sendChatStream, sendChatFallback, stopChat, applyChatResponse+addBotMessage, submit) | хук возвращает `{ send, stop, inFlight, directive }`; dispatch кадров: status→строка состояния заглушки, agent→`appendTraceEvent`, final→applyChatResponse-port (hideTyping + свежая запись ответа, `applyUsage`, sessionId → `setSessionId`, branchId → `setActiveBranchId`, директива «Передача завершена»), cancelled/stop→«Остановлено пользователем»+«Передача остановлена», error→текст сервера; стрим без терминала — «Сервер завершил передачу без итогового ответа» |
| `features/send/SendBar.tsx` + `.module.css` | index.html L323–335; script.js L2735–2746, L2785–2791; style.css L1250–1357, ≤600px L2826–2828 | однострочный input (Enter отдаёт форму нативно; авто-grow нет — его нет в ваниле), `required`, очистка после отправки, блокировка поля и «Передать» на время полёта, красная клавиша «Стоп» только в полёте (`--lamp-rgb: var(--lamp-alarm)`), возврат фокуса в поле после передачи |
| `App.tsx` + `App.module.css` | index.html L17–342 (каркас); style.css L69–188, L818–824, ≤900px L2710–2738, ≤600px L2786–2828 | грид «strip/side/main»: шапка (бренд, Clock, клавиша топлива → Overlay с `FuelMeter` на живой `tokenHistory`, тумблер приборов с aria-expanded/controls), `Drawer` с `SettingsPanel` (автоплейсмент в колонку side), `main.logColumn` = Journal + StatusLine + SendBar; `body { overflow: hidden }` — в base.css (style.css L60–61) |

Не переносится сознательно (волна 6): зеркало (busy-текст «Идёт сравнение…»
и `runMirrorExchange`), панель сессий (`loadSessions` после ответа — кэш
`sessionsCache` не обновляется), чипы веток (`loadBranches`; сам
`activeBranchId` из ответа уже пишется), панель фактов
(`updateFactsPanel`), «Обслуживание»/самописец (сырые `exchanges` ответа
не рендерятся — `Entry` их не показывает, как решено в волне 3b).
Повтор режима в шапке не переносится: режимы удалены в волне 1.
Тесты волны 5: `features/send/request.test.ts` (сборка запроса, busy-директива,
kind-маппинг, решения о фолбэке) + записи журнала в `stores/session.test.ts`.

## ЗЕРКАЛО, СЕССИИ, ВЕТКИ, ОБСЛУЖИВАНИЕ (волна 6)

Порт 1:1 из static/ (дизайн заморожен). Ключевое правило (spec
«Состояние», tasks.md «Подводные камни»): внутреннее состояние
близнецов зеркала — локально в `TwinPane` (useState/useRef,
ключ — twinId стороны), стором не является; модульный реестр
`twinRegistry` — только провод от конвейера отправки к
смонтированным колонкам, никакого общего «latest». События
устаревшего twin-обмена гвардятся twinId+номером обмена.
Mount-эффекты — только идемпотентные GET (перечень сессий,
опрос веток) с abort в cleanup; стримы — только из обработчиков.

| Файл | Порт из static/ | Примечания |
|---|---|---|
| `features/mirror/twin.ts` + тест | script.js L54–59, L2198–2209, L2228–2345, L2384–2465, L2478–2484 | чистые: `sideStrategy` (селект колонки; неизвестное — «Выкл»), `buildTwinRequest` (свой sessionId/стратегия, судья выкл, branchId не переходит), `twinStreamDecision`/`twinErrorDecision` (фолбэк twin-потока), `mirrorTokensOrNd` («н/д»), итоги и вердикт |
| `features/mirror/twinRegistry.ts` | mirrorState L2232–2235 (page-lifetime) | реестр TwinHandle по twinId; состояние — в TwinPane |
| `features/mirror/TwinPane.tsx` + `.module.css` | index.html L286–313; script.js L2228–2465; style.css L2485–2657 | локальные cards/totals/sessionId/номер обмена; карточки «Ответ/Отказ» на классах Entry.module.css; пустое «Передач ещё не было»; селект стратегии колонки → settingsStore (персист) |
| `features/mirror/Mirror.tsx` + `.module.css` | index.html L283–316; script.js L2467–2493, L2307–2334; style.css L2474–2489, L2660–2667, ≤700px L2669–2678 | пульт сравнения под журналом; вердикт пересчитывается после оседания стороны; демонтируется при выключении (= finishMirror) |
| `features/sessions/sessionList.ts` + тест | script.js L1795–1847 | чистые: `sessionTimeLabel` («ЧЧ:ММ»/«вчера»/«ДД.ММ»), `sessionTitle` («Без сообщений»), `sessionMeta`, валидация перечня |
| `features/sessions/loadSessions.ts` | script.js L1849–1860 | GET /api/sessions → sessionsCache; неуспех — кэш как был |
| `features/sessions/SessionsPanel.tsx` + `.module.css` | index.html L338–341; script.js L1786–1847; style.css L1361–1493, ≤900px L2774–2778 | третья колонка каркаса; стартовый перечень — mount-GET с abort; «Пока нет сессий» |
| `features/sessions/restoreSession.ts` | script.js switchToSession L1862–1898 | клик → GET /api/session/{id} (abort нового клика отменяет старое); неуспех — откат sessionId; пусто → приветствие |
| `features/chat/history.ts` + тест | script.js renderHistory L1902–1937 | чистый маппинг истории в записи (без показаний, трасса из msg.events); applyHistory — перезаполнение стора |
| `features/branches/branchList.ts` + тест | script.js L2001, L2066–2073 | чистые: активная запись снимка, валидные чипы |
| `features/branches/branchesApi.ts` | script.js loadBranches L2043–2084 | GET …/branches; бар только под «Ветками» и вне зеркала; не-2xx → бар убран, сеть → чипы как были |
| `features/branches/Branches.tsx` + `.module.css` | index.html L264–269; script.js L1996–2175; style.css L1495–1593, ≤900px L2770–2772 | чипы (лампы активных, некликабельный корень), «+ Развилка» (409 → «Достигнут лимит веток (8)»), переключение → activate POST + история |
| `features/strategy/Facts.tsx` + `.module.css` | index.html L228–231; script.js L1012–1045; style.css L713–767 | панель фактов: видна только под «Факты», данные — ChatResponse.facts (session store); «Фактов пока нет» |
| `features/maintenance/Maintenance.tsx` + `.module.css` | index.html L244–255; script.js L1242–1248, L1721–1784; style.css L1614–1624, L350–355 | «Режим ТО» (не персистится: класс body + директива в покое — App), «Новый диалог», «Очистить все» с двойным нажатием (4 с, `.armed`); DELETE /api/sessions → «Все сессии стёрты (N)» |
| `features/maintenance/Recorder.tsx` + `.module.css` | index.html L344–365; script.js L1268–1405; style.css L1967–1997, L2116–2231 | самописец на общем Overlay (клавиша «Обновить»); GET /api/log, свежие сверху; guard «открыт ли»; prettyJson + hljs + ExpandToggle |
| `features/send/request.ts` (расширение) | script.js L2750–2752, L2490 | `mirrorBusyDirective` («Идёт сравнение · пара параллельных передач»), `mirrorResultDirective` («Сравнение завершено» / «Обе стороны зеркала отказали») |
| `features/send/useChatStream.ts` (расширение) | script.js runMirrorExchange L2467–2493, sendTwinStream L2384–2465, sendTwinBlocking L2363–2379, applyChatResponse L2541–2563 | ветка зеркала в send (без основного стрима и «Стопа» — stoppable); twin-обмены параллельно, каждый со своим requestId и фолбэком; после final — факты, loadBranches (branchId или «Ветки»), loadSessions; наружу отдаются { send, stop, inFlight, stoppable, directive, setDirective } |
| `App.tsx` + `App.module.css` | index.html L264–341; style.css L69–100, L2710–2784 | грид 3 колонок («side main sessions», 216px); Branches над журналом, Mirror под журналом, SessionsPanel справа, Recorder-оверлей, слот «Обслуживания» в Drawer; Режим ТО = класс body + директива |

Не переносится сознательно: сырой обмен в записях журнала
(`body.maint-on .raw-exchange`) — Entry их не показывает с волны 3b,
сырой JSON живёт в самописце; авто-фокус поля при «Новый диалог» /
переключении сессии (владелец фокуса — SendBar, возврат после
передачи сохранён). Панель сессий названа `SessionsPanel.tsx`
(не SessionsOverlay из эскиза spec-дерева) — в ваниле это третья
колонка каркаса, не оверлей. Тесты волны 6: `mirror/twin.test.ts`,
`sessions/sessionList.test.ts`, `branches/branchList.test.ts`,
`chat/history.test.ts` (чистая логика, без jsdom).

## ВХОД, ПРЕДПОЧТАНИЯ, СМЕНА ПОЛЬЗОВАТЕЛЯ (персонализация)

Персонализация поверх memory-layers
(openspec/changes/add-user-preferences): многопользовательский режим.
Пока `authStore.status` не `authed`, App рендерит гейт входа **вместо**
консоли (условный рендер, не Overlay); чип пользователя — в шапке,
онбординг-визард — Overlay поверх консоли, секция «Предпочтения» — в
панели приборов. Данные пользователей живут на сервере per-user; вкладка
держит только токен (`sessionStorage`, ключ `shturman.auth.v1`).

| Файл | Назначение | Примечания |
|---|---|---|
| `stores/auth.ts` + `auth.test.ts` | auth-стор, фабрика `createAuthStore(storage)` — хранилище первым аргументом (паттерн `createSettingsStore`), тесты — in-memory | состояние `checking`/`anon`/`authed` + `onboardingRequired`; persist-мидлвир НЕ используется — read/write/clear маленькими хелперами; `login` (сохраняет token+user → `authed`), `createUser` (автовход, поднимает `onboardingRequired`), `logout`, `completeOnboarding`, `getToken`; `restore()` — токен есть → проверочный GET /api/preferences → `authed`, при ЛЮБОЙ ошибке (401/сеть) — `logout` (план: «authed или logout»); `handle401()` — централизованный разлогин |
| `features/auth/AuthGate.tsx` + `.module.css` | полноэкранный гейт вместо консоли | список пользователей (клавиши-аннунциаторы, `aria-pressed`) → выбор → поле ПИН «ВВЕДИТЕ ПИН пользователя» (`inputmode=numeric`, маска 4 цифры, «Войти» заблокирован до выбора + 4 цифр, «Сброс»); «Создать пользователя» — имя ≤25 + ПИН с live-валидацией → авто-вход; пустой список — сразу форма создания; отказ GET — «Не удалось загрузить список пользователей» + «Повторить» (сброс — в обработчике, `reloadTick`); ошибки — по-русски: текст 401 от сервера приоритетен (фолбэк «Неверный ПИН или пользователь»), 429 — кулдаун-текст сервера, сеть — «Нет связи с сервером. Проверьте, что бэкенд запущен»; стиль — приборная доска, только токены |
| `features/auth/authGateLogic.ts` + `authGate.test.ts` | чистая логика гейта | `maskPin` (strip non-digits, cap 4), `isPinComplete`, `validateCreateForm`, `loginErrorText`, `createErrorText`; имя файла — НЕ `authGate.ts`: коллизия с `AuthGate.tsx` ломает tsc (см. грабли) |
| `features/auth/UserChip.tsx` + `.module.css` | чип текущего пользователя в шапке | имя из auth-стора, янтарная лампа; меню `role="menu"`: «Сменить пользователя» и «Выйти» — ОБА ведут в `logout()` → гейт (отдельного экрана «выхода» нет); Esc — через общий `overlayStack` (только верхний слой), клик мимо закрывает, фокус возвращается на чип |
| `features/auth/resetState.ts` + `resetState.test.ts` | очистка бортового состояния при выходе/смене | `clearUserState(stores)` — чистая, операции инъекцией (SessionResetOps/BranchResetOps/MemoryResetOps/TwinsResetOps); `resetUserState()` — на реальных сторах; reset-лист: sessionId=null, журнал, топливомер, перечень сессий, факты, ветки (hideBranches), память (resetMemory), twinRegistry; SettingsStore НЕ входит (настройки приборов = браузерный `shturman.settings.v1`, конфигурация прибора, а не данные пользователя); черновик SendBar — неконтролируемое поле, умирает с демонтированием консоли (условный рендер) — отдельной очистки нет; вызывает App при переходе `authed → anon` (эффект с `prevAuthStatusRef`: клавиши чипа, logout, любой 401, рестарт сервера) |
| `features/onboarding/OnboardingWizard.tsx` + `.module.css` | визард 7 вопросов на общем Overlay | «Настройка предпочтений» (подзаголовок «7 вопросов о том, как вам отвечать»): прогресс — 7 сегментов + «Шаг N из 7»; radio-шаги — `AnnunciatorGroup` (длина — `variant="strategy"`, подсказки-ориентиры ≈1/до 3/≈10 тыс. токенов); «иное» в структуре — текстовое поле активно только при выборе «иное» (≤255, счётчик); свободные поля ≤1000 со счётчиком «8/1000»; предзаполнение — GET /api/preferences на mount (отказ → дефолты, опросник не блокируем; StrictMode — abort в cleanup); «Назад»/«Далее» (заблокирован при ошибке шага), «Сохранить» — PUT → `onSaved()`; прерывание (Esc/крестик/фон) — `onClose()`, НИЧЕГО не сохраняется — работают серверные дефолты; обёртка без хуков (`if (!open) return null`) + внутренний `WizardDialog` — при каждом открытии свежий mount, шаги/ответы сбрасываются сами |
| `features/onboarding/wizard.ts` + `wizard.test.ts` | чистая логика визарда (30 тестов) | `WIZARD_STEP_COUNT=7`, `STEP_TITLES` (русские тексты вопросов); `createInitialAnswers` — серверные дефолты; `answersFromPreferences` — словарная структура → клавиша, всё прочее → «иное»+текст (кастом с сервера не теряется); `sanitizeFreeText` (контрольные символы → пробел, зеркало `Character.isISOControl`; ввод НЕ триммится — trim в `buildPayload` и на сервере), `capFreeText` (дубль maxLength), `counterText`; `buildPayload` («иное» → свой текст, trim); `stepError`/`canAdvance`/`payloadErrors` — через `validatePreferences` с фильтром по префиксу поля (валидация НЕ дублируется); `saveErrorText` (текст 400 от сервера приоритетен, сеть — «Нет связи с сервером…») |
| `features/settings/PreferencesSection.tsx` + `.module.css` | секция «Предпочтения» панели приборов | между «Памятью» и «Обслуживанием»: табло 7 значений (dt/dd, зелёный моно, ellipsis, пустое = гравировка «не задано») + клавиша-аннунциатор «Изменить в опроснике» — СОБСТВЕННЫЙ экземпляр `OnboardingWizard` (своё open-состояние, `onSaved` → закрыть + перечитать); чтение GET при монтаже и при первом открытии Drawer'а (флаг `hasOpened` — см. грабли), закрытие Drawer'а не перечитывает; отказ — строка «Не удалось загрузить предпочтения» + «Повторить»; подпись: блок «Предпочтения пользователя» прикладывается к каждому запросу, правка только в опроснике |
| `App.tsx` (расширение) | гейт + чип + визард + сброс | селекторы auth-стора — ДО остальных хуков; `restore()` в mount-эффекте (идемпотентно: StrictMode удваивает); `checking` → заглушка «Проверка сессии…», `anon` → `<AuthGate/>` вместо консоли; `UserChip` в `topStrip`; `<OnboardingWizard open={onboardingRequired} onClose/onSaved={completeOnboarding}/>` (поднимается после createUser); переход `authed → anon` → `resetUserState()`; перечни нового пользователя перечитываются сами (SessionsPanel — mount-GET, память — при открытии) |

Конвенции домена:
- Токен — **sessionStorage** (`shturman.auth.v1`), не localStorage:
  вкладки независимы, закрытие вкладки = сброс входа, перезапуск сервера =
  повторный вход (токены живут в памяти сервера).
- **Инверсия client↔store**: `api/client.ts` НЕ импортирует auth-стор;
  стор при создании сам регистрируется через `setAuthTokenProvider` /
  `setUnauthorizedHandler` (последний созданный стор побеждает — в
  приложении он один). `api/sse.ts` читает токен напрямую
  (`useAuthStore.getState().getToken()`) в момент открытия потока;
  401 на стриме → `{ok:false}` → блокирующий фолбэк `postJson('chat')`
  дёргает центральный 401-хендлер.
- **GET /api/preferences всегда отдаёт все 7 полей** — на сервере каждое
  поле `UserPreferences` помечено `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`
  (Json сервера `encodeDefaults=false`; без аннотации GET дефолтных
  предпочтений отвечал бы `{}`). Зеркалируя DTO в `api/types.ts` —
  учитывать.
- `PUT /api/preferences` = ЗАМЕЩЕНИЕ, не мердж: фронт шлёт полный
  7-полевой объект (визард так и делает).
- Правила валидации — только в `lib/validators.ts` (зеркало
  `auth/UserAccount.kt` и `prefs/PreferenceLimits.kt`: `isValidPin`,
  `isValidName`, `validatePreferences`); сервер — последняя линия обороны
  (400 с русским текстом поля).

Грабли (для будущих агентов):
- **Кейс-коллизия имён**: `authGate.ts` + `AuthGate.tsx` в одном
  каталоге ломают tsc на macOS (case-insensitive FS: «differs only in
  casing») — чистая логика названа `authGateLogic.ts`, тесты
  импортируют `./authGateLogic`.
- **StrictMode × open-затвор**: вариант «mountedRef + `if (!open &&
  !isMount) return`» ЛОМАЕТСЯ двойным монтированием — первый fetch
  абортируется cleanup'ом, второй видит `isMount=false` и уходит, не
  фетча, данные никогда не приходят (находилось Playwright-прогоном:
  пустая секция). Лечение: `hasOpened` выводится **во время рендера**
  (setState при рендере — задокументированный React-паттерн «adjusting
  state when a prop changes») + безусловный эффект на
  `[hasOpened, reloadTick]`. Не «упрощать» обратно.
- **setState в теле эффекта** — ошибка линта
  (`react-hooks/set-state-in-effect`): сброс состояния — в обработчике
  клавиши (`reloadTick`), «идёт загрузка» — выводимое состояние
  (показаний нет + отказа нет), не хранимый флаг.
- **StrictMode × GET в эффекте** (AuthGate, визард, PreferencesSection):
  abort в cleanup и гвард `controller.signal.aborted` и в catch, и в
  finally.
- **Playwright**: `innerText` отдаёт CSS-uppercase («ВВЕДИТЕ ПИН
  ПОЛЬЗОВАТЕЛЯ»), `aria-label` не попадает в `innerText` — ассертить
  через `querySelector`/role; заголовок «Штурман» есть и в гейте, и в
  консоли — ждать приборы консоли («Память агента»); локаторы визарда —
  в скоупе `getByRole('dialog')` («Назад» коллидирует с кнопкой памяти
  под оверлеем — strict violation); `fill()` обходит `maxLength` —
  кап режет в `onChange`, честный тест капа именно такой.
