# Задачи: add-user-preferences

## 1. Auth-домен (бэкенд)

- [ ] 1.1 `UserAccount` + валидация: ПИН ровно 4 цифры, имя 1..25 после trim
      без контрольных символов; SHA-256(salt+ПИН), сравнение constant-time
- [ ] 1.2 `UserStore`: `dataDir/users/{userId}.json`, атомарный персист
      (tmp+rename), isSafeId; create/findById/list/delete/existsWithName
- [ ] 1.3 `TokenManager`: SecureRandom 32 байта Base64URL; issue/resolve/
      revoke/revokeAllFor; только в памяти
- [ ] 1.4 `LoginGuard`: 3 неудачи → кулдаун 30 с; успех сбрасывает счётчик
- [ ] 1.5 DTO в `Models.kt`: AuthUserView, LoginRequest, CreateUserRequest,
      AuthTokenResponse, UserCreatedResponse, AuthErrorResponse
      (pinHash/salt ни в одном DTO)
- [ ] 1.6 Тесты: персист стора, токены, кулдаун, валидация (русские ошибки)

## 2. Сессии: userId и скоупинг

- [ ] 2.1 `Session.userId: String?` (обратная совместимость чтения legacy)
- [ ] 2.2 `FileSessionStore`/`InMemorySessionStore`: create(userId),
      listForUser, deleteAllForUser
- [ ] 2.3 KDoc: эвикция MAX_SESSIONS=50 глобальная (per-user квоты вне рамок)
- [ ] 2.4 Тесты: фильтрация per-user, удаление только своего, legacy-чтение

## 3. Preferences-домен

- [ ] 3.1 `UserPreferences` (7 полей) + `PreferenceLimits` (enum-словари,
      255/1000/1000, запрет контрольных символов, русские ошибки)
- [ ] 3.2 `PreferencesStore`: `dataDir/preferences/{userId}.json`, атомарный
      персист, дефолты (ты/дружелюбный/Русский/текст/средне/пусто/пусто)
- [ ] 3.3 `renderPromptBlock(prefs)`: заголовок «Предпочтения пользователя»,
      7 строк, бюджет ≤ 2500 символов, обрезка free-полей
- [ ] 3.4 Тесты: enum-валидация, лимиты, дефолты, рендер блока

## 4. Фронтенд: API-слой и auth-стор

- [ ] 4.1 `api/types.ts`: зеркала DTO (AuthUserView, LoginRequest,
      CreateUserRequest, AuthTokenResponse, UserCreatedResponse,
      UserPreferences)
- [ ] 4.2 `api/client.ts`: login/listUsers/createUser/deleteUser/
      fetchPreferences/savePreferences; централизованный перехват 401
- [ ] 4.3 `api/sse.ts`: `X-Auth-Token` в streamChat
- [ ] 4.4 `stores/auth.ts` (zustand, без persist): token в sessionStorage
      (`shturman.auth.v1`), login/logout/restore/handle401
- [ ] 4.5 `lib/validators.ts` (pure): isValidPin/isValidName/
      validatePreferences + тесты (Vitest)

## 5. Auth-роуты, intercept и журнал

- [ ] 5.1 `POST /api/auth/login` (кулдаун → 429), `GET/POST /api/auth/users`,
      `DELETE /api/auth/users/{id}` (каскад: user/prefs/memory/сессии/токены;
      запрет удаления последнего)
- [ ] 5.2 Центральный intercept в начале `aiRoutes()`: whitelist
      {login, GET/POST users, статика}, иначе 401; userId в attributes;
      комментарий-таблица disposition (зеркало спеки)
- [ ] 5.3 `ExchangeLog`: поле `userId`, тег в chat-хендлерах,
      `GET /api/log` фильтрует по userId
- [ ] 5.4 Тесты: матрица 401 (все защищённые × нет/мусорный токен),
      кулдаун, каскад, фильтр журнала

## 6. API предпочтений

- [ ] 6.1 `GET /api/preferences` (нет файла → дефолты), `PUT /api/preferences`
      (серверная валидация, 400 с русским текстом)
- [ ] 6.2 Тесты: round-trip, дефолты, невалидный enum/лимиты, 401 без токена

## 7. Per-user долговременная память

- [ ] 7.1 Раскладка `dataDir/memory/{userId}/longterm.json`: per-user
      обёртка `MemoryRepository`, userId в сигнатурах
- [ ] 7.2 Роуты `/api/memory*` резолвят userId из токена (вкл. overview)
- [ ] 7.3 Контракт-тесты проходят для обёртки; изоляция двух пользователей;
      чужая запись по id → 404

## 8. AuthGate (UI входа)

- [ ] 8.1 `features/auth/AuthGate.tsx`: список пользователей, поле ПИН
      («ВВЕДИТЕ ПИН пользователя», inputmode numeric, маска), создание
      пользователя с автовходом
- [ ] 8.2 `App.tsx`: гейт вместо контента при status ≠ authed; restore()
      при монтировании; любой 401 → logout → гейт
- [ ] 8.3 Тесты (Vitest) + Playwright: вход, неверный ПИН, кулдаун,
      создание

## 9. Миграция «Шумахер» (идемпотентная)

- [ ] 9.1 Bootstrap в `aiRoutes`: нет «Шумахера» + legacy-артефакты
      (сессии userId=null и/или `memory/longterm.json`) → создать и
      передать ему всё
- [ ] 9.2 PIN миграции: SecureRandom, печатается в stdout ОДИН раз
- [ ] 9.3 Тесты: двойной бут без дубликатов; пустой dataDir → no-op;
      legacy-фикстура в `src/test/resources/`

## 10. Тест-харнесс с авторизацией

- [ ] 10.1 `withAuthApp` (фикстура тест-пользователя + токен + authed-хелпер)
- [ ] 10.2 Ретрофит всех HTTP-интеграционных тестов под X-Auth-Token;
      полный `./gradlew test -PskipFrontend` зелёный

## 11. Инъекция предпочтений

- [ ] 11.1 System-сообщение «Предпочтения пользователя» хвостом семени после
      trim: `handleAgent` И `handleSingle`, порядок: сначала предпочтения,
      затем блок памяти
- [ ] 11.2 Саб-вызовы (факты/сводка/судья) без блока
- [ ] 11.3 Тесты: все режимы, переживание trim, новые значения после PUT,
      саб-вызовы чистые

## 12. Онбординг-визард

- [ ] 12.1 `features/onboarding/OnboardingWizard.tsx`: 7 шагов-вопросов
      (обращение/тон/язык/структура/длина/избегаемые темы/интересы),
      прогресс, «Назад»/«Далее»/«Сохранить» → PUT
- [ ] 12.2 `wizard.ts` (pure): шаги, валидация, маппинг ответов → payload
      + тесты
- [ ] 12.3 Прерывание: ничего не сохраняется, работают дефолты

## 13. Секция настроек, чип, переключение

- [ ] 13.1 SettingsPanel: секция «Предпочтения» (7 значений, «Изменить в
      опроснике»)
- [ ] 13.2 Чип пользователя в topStrip: «Сменить пользователя» → logout →
      AuthGate
- [ ] 13.3 `resetState.ts` (pure) + тесты: sessionId, twinRegistry,
      memory-стор, черновик ввода
- [ ] 13.4 Playwright: смена пользователя без утечек состояния

## 14. Документация

- [ ] 14.1 README.md (раздел «Пользователи и персонализация», API 22 → 26
      роутов, миграция Шумахера), AGENTS.md (auth/, prefs/, smoke-чеклист),
      config.example.json (подкаталоги users/, preferences/, memory/{userId}/)
- [ ] 14.2 PRODUCT.md (многопользовательская модель), frontend/AGENTS.md
      (smoke auth-флоу, sessionStorage, рестарт = re-login, длина =
      инструкция)
- [ ] 14.3 Чекбоксы задач проставлены; проверка change (валидация OpenSpec
      если доступно)
