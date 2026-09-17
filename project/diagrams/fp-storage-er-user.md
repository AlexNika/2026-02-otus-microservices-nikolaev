# ER: user_db (USERService) — проекция идентичности, профиль, адреса, outbox

> Источник: Flyway-миграции `USERService/src/main/resources/db/migration/V1–V8`. Компактная схема: только ключевые таблицы и поля. Аудит-колонки опущены. Таблицы `roles`/`user_roles` и credential-колонки физически удалены миграцией V7 (аутентификация переехала в AUTHService).

- `users` — проекция идентичности "для людей": `id` присваивается из события AUTH (`user.created`), credentials не хранятся; `account_status` (PENDING/ACTIVE/BLOCKED) — статус биллинг-аккаунта по событийной хореографии USER ↔ BILLING.
- `user_profile` — профиль; `phone` UNIQUE, строго один (nullable). USERService — source of truth контактов.
- `user_addresses` — адреса доставки 1:N (source of truth; снимок адресов публикуется в `UserSyncEvent`, DELIVERY ведёт свою read-модель по natural key `(user_id, source_address_id)`).
- `user_outbox` — Transactional Outbox двух типов событий: `USER_CREATED` (USER → BILLING) и `USER_SYNC` (USER → NOTIFICATION/DELIVERY), event_id UUID UNIQUE, статусы NEW/SENT/FAILED, trace-context с V8.

```mermaid
erDiagram
    USERS ||--o| USER_PROFILE : "profile_id FK (SET NULL)"
    USERS ||--o{ USER_ADDRESSES : "user_id"
    USERS {
        BIGINT id PK "присваивается из события AUTH"
        BIGINT version
        VARCHAR email UK
        BIGINT profile_id FK
        VARCHAR account_status "PENDING/ACTIVE/BLOCKED (биллинг)"
    }
    USER_PROFILE {
        BIGINT id PK
        VARCHAR username
        VARCHAR firstname
        VARCHAR lastname
        TIMESTAMP birthdate
        VARCHAR phone UK "строго один, nullable"
    }
    USER_ADDRESSES {
        BIGINT id PK
        BIGINT user_id FK "CASCADE"
        TEXT full_address
        VARCHAR city
        VARCHAR postal_code
        BOOLEAN is_default
        TEXT delivery_preferences
    }
    USER_OUTBOX {
        BIGINT id PK
        VARCHAR event_id UK "UUID события"
        VARCHAR event_type "USER_CREATED/USER_SYNC"
        TEXT payload
        VARCHAR status "NEW/SENT/FAILED"
        INTEGER attempts
        TIMESTAMP sent_at
        VARCHAR traceparent "W3C trace-context"
        TEXT tracestate
    }
```
