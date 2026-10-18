# Спецификация: Авторизация пользователей (PIN)

## ADDED Requirements

### Requirement: Вход по ПИН

Система MUST принимать `POST /api/auth/login` с телом `{userId, pin}`. При
верном ПИН ответ 200 `{token, user: {id, name}}`: токен генерируется
SecureRandom (≥ 128 бит, Base64URL) и хранится только в памяти сервера.
При неверном ПИН или неизвестном пользователе ответ 401 «Неверный ПИН или
пользователь». ПИН хранится как `SHA-256(salt + ПИН)`; plaintext ПИН,
pinHash и salt MUST NOT возвращаться ни в одном ответе.

#### Scenario: Успешный вход
- **WHEN** `POST /api/auth/login` с `{"userId":"<id>","pin":"0407"}` при верном ПИН
- **THEN** 200 с токеном и `user: {id, name}`; токен в заголовке
  `X-Auth-Token` открывает защищённые роуты

#### Scenario: Неверный ПИН
- **WHEN** `POST /api/auth/login` с неверным ПИН или неизвестным userId
- **THEN** 401 «Неверный ПИН или пользователь», токен не выдаётся

### Requirement: Защита от подбора (кулдаун)

Система MUST вести счётчик неудачных попыток входа per-userId: после 3
неудач попытки входа заблокированы на 30 секунд. Попытка входа (даже с
верным ПИН) внутри кулдауна → 429 с русским текстом. Успешный вход
сбрасывает счётчик. Счётчик живёт в памяти сервера.

#### Scenario: Четвёртая попытка в кулдауне
- **WHEN** после 3 неудачных попыток выполняется 4-й `POST /api/auth/login`
  с ВЕРНЫМ ПИН
- **THEN** 429 с текстом о кулдауне, токен не выдаётся

#### Scenario: Успех сбрасывает счётчик
- **WHEN** 2 неудачные попытки, затем вход с верным ПИН
- **THEN** 200 с токеном; следующие 2 неудачи кулдаун НЕ вызывают

### Requirement: Создание и список пользователей

`POST /api/auth/users` с `{name, pin}` MUST создавать пользователя: имя
1..25 символов после trim (кириллица/латиница/цифры/спецсимволы,
контрольные символы запрещены), ПИН ровно 4 цифры. Дубликаты имён
разрешены (различают ПИНЫ). Нарушение валидации → 400 с русским текстом
и названием поля. Ответ 200 `{token, user}` (автовход).
`GET /api/auth/users` MUST возвращать список `{id, name}` без хешей и
ПИНов.

#### Scenario: Создание с валидными полями
- **WHEN** `POST /api/auth/users` `{"name":"Гонщик","pin":"0407"}`
- **THEN** 200 `{token, user}`; пользователь виден в `GET /api/auth/users`

#### Scenario: Дубликат имени разрешён
- **WHEN** создание второго пользователя с именем «Гонщик» и другим ПИН
- **THEN** 200; оба пользователя в списке, различаются id

#### Scenario: Невалидные поля
- **WHEN** `POST /api/auth/users` с именем 26 символов, именем с переносом
  строки, ПИНом «12a5»/«123»/«12345»/пустым
- **THEN** 400, текст называет конкретное поле и допустимый формат

### Requirement: Удаление пользователя с каскадом

`DELETE /api/auth/users/{id}` MUST удалять: файл пользователя,
`dataDir/preferences/{userId}.json`, каталог `dataDir/memory/{userId}/`,
все сессии пользователя (включая их файлы рабочей памяти) и отзывать все
его токены. Удаление единственного оставшегося пользователя MUST
отклоняться: 400 «Нельзя удалить единственного пользователя».

#### Scenario: Каскад удаление
- **WHEN** пользователь с сессиями, записями долговременной памяти и
  сохранёнными предпочтениями удаляется
- **THEN** все перечисленные файлы отсутствуют в `dataDir`, пользователь не
  в `GET /api/auth/users`, его прежний токен → 401

#### Scenario: Последний пользователь не удаляется
- **WHEN** `DELETE /api/auth/users/{id}` при единственном пользователе
- **THEN** 400 «Нельзя удалить единственного пользователя», пользователь жив

### Requirement: Централизованная защита роутов

Все запросы, кроме whitelist {`POST /api/auth/login`, `GET /api/auth/users`,
`POST /api/auth/users`, статика `/`}, MUST нести валидный `X-Auth-Token`,
иначе ответ 401 `{"error": "Требуется вход"}`. Проверка MUST выполняться в
едином intercept (choke point) в начале `aiRoutes()`, а не в отдельных
route-блоках. Успешная проверка кладёт userId в атрибуты вызова.

#### Scenario: Отсутствие токена
- **WHEN** `GET /api/sessions` без заголовка `X-Auth-Token`
- **THEN** 401 `{"error": "Требуется вход"}`

#### Scenario: Мусорный токен
- **WHEN** `GET /api/memory` с `X-Auth-Token: garbage`
- **THEN** 401, тело данных не возвращается

#### Scenario: Open-роуты живут без токена
- **WHEN** `GET /`, `GET /api/auth/users`, `POST /api/auth/users`,
  `POST /api/auth/login` вызываются без токена
- **THEN** роуты отвечают по собственной логике, а не 401 intercept'а

### Requirement: Таблица disposition существующих роутов

Все 22 существующих роута `Routes.kt` MUST быть классифицированы.
«user-scoped» = защищён и работает только с данными пользователя токена;
чужой ресурс → 404.

| # | Роут | Метод | Диспозиция |
|---|------|-------|------------|
| 1 | /api/log | GET | user-scoped (только свои записи журнала) |
| 2 | /api/models | GET | protected (общий справочник моделей) |
| 3 | /api/chat | POST | user-scoped |
| 4 | /api/chat/stream | POST | user-scoped (SSE; доигрывает в исходную сессию) |
| 5 | /api/cancel | POST | protected |
| 6 | /api/sessions | GET | user-scoped (только свои сессии) |
| 7 | /api/sessions | DELETE | user-scoped (чистит только свои) |
| 8 | /api/session/{id} | GET | user-scoped (чужая сессия → 404) |
| 9 | /api/session/{id} | DELETE | user-scoped (чужая сессия → 404) |
| 10 | /api/session/{id}/branches | GET | user-scoped |
| 11 | /api/session/{id}/branches | POST | user-scoped |
| 12 | /api/session/{id}/branches/activate | POST | user-scoped |
| 13 | /api/session/{id}/working | GET | user-scoped |
| 14 | /api/session/{id}/working | PUT | user-scoped |
| 15 | /api/session/{id}/working | DELETE | user-scoped |
| 16 | /api/session/{id}/working/{entryId} | DELETE | user-scoped |
| 17 | /api/memory | GET | user-scoped (только своя долговременная память) |
| 18 | /api/memory | POST | user-scoped |
| 19 | /api/memory/{id} | PUT | user-scoped (чужая запись → 404) |
| 20 | /api/memory/{id} | DELETE | user-scoped (чужая запись → 404) |
| 21 | /api/memory/overview | GET | user-scoped (счётчики своих слоёв) |
| 22 | /api/files/{name} | GET | protected, ОБЩАЯ песочница (задокументировано) |

Открытые роуты (без токена): `POST /api/auth/login`, `GET /api/auth/users`,
`POST /api/auth/users`, статика `/`. Новый `DELETE /api/auth/users/{id}`
защищён (нужен валидный токен).

#### Scenario: Матрица 401
- **WHEN** каждый защищённый роут таблицы вызывается без токена и с
  мусорным токеном
- **THEN** оба случая → 401; ни один защищённый роут не отдаёт 200

### Requirement: Токены не переживают рестарт сервера

Токены MUST храниться только в памяти процесса. После рестарта сервера все
выданные токены недействительны; клиент обязан повторно пройти вход.

#### Scenario: Рестарт сервера
- **WHEN** сервер перезапущен, клиент шлёт сохранённый в sessionStorage токен
- **THEN** 401; клиент разлогинивается, показывается экран входа
