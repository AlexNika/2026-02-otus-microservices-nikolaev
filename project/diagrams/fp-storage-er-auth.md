# ER: auth_db (AUTHService) — идентичность, роли, refresh-токены, outbox

> Источник: Flyway-миграции `AUTHService/src/main/resources/db/migration/V1–V6`. Компактная схема: только ключевые таблицы и поля. Аудит-колонки (`version`/`created`/`updated`/`created_by`/`last_modified_by`) опущены, кроме упомянутых явно.

- `auth_users` — единственные credentials в проекте (email UNIQUE + `password_hash`); ACCESS-токен валидируется локально без БД.
- `refresh_tokens` — токен не хранится: только `token_hash` (SHA-256, UNIQUE) + флаг `rotated` (ротация при каждом refresh) + `expires_at`.
- `auth_outbox` — Transactional Outbox события `user.created` (регистрация): event_id UUID UNIQUE, статусы NEW/SENT/FAILED, trace-context с V6.

```mermaid
erDiagram
    AUTH_USERS ||--o{ USER_ROLES : "user_id"
    ROLES ||--o{ USER_ROLES : "role_id"
    AUTH_USERS ||--o{ REFRESH_TOKENS : "user_id"
    AUTH_USERS {
        BIGINT id PK "BIGSERIAL"
        BIGINT version "optimistic lock"
        VARCHAR email UK "uq_auth_users_email"
        VARCHAR password_hash
    }
    ROLES {
        BIGINT id PK
        VARCHAR name UK "50 символов"
        VARCHAR description
    }
    USER_ROLES {
        BIGINT user_id PK "FK -> auth_users, CASCADE"
        BIGINT role_id PK "FK -> roles, CASCADE"
    }
    REFRESH_TOKENS {
        BIGINT id PK
        BIGINT user_id FK "CASCADE"
        VARCHAR token_hash UK "SHA-256, 64 hex — токен не хранится"
        BOOLEAN rotated "ротация при каждом refresh"
        TIMESTAMP expires_at
    }
    AUTH_OUTBOX {
        BIGINT id PK
        VARCHAR event_id UK "UUID события"
        TEXT payload "UserCreatedEvent"
        VARCHAR status "NEW/SENT/FAILED"
        INTEGER attempts
        TIMESTAMP sent_at
        VARCHAR traceparent "W3C trace-context"
        TEXT tracestate
    }
```
