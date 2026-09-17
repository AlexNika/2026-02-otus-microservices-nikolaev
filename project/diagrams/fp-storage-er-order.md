# ER: order_db (ORDERService) — заказы, состояние саги, идемпотентность, outbox

> Источник: Flyway-миграции `ORDERService/src/main/resources/db/migration/V1–V6`. Компактная схема: только ключевые таблицы и поля (PK/FK/UK, `order_status`, `saga_status`, `version`, `idempotency_key`, outbox-статусы). Аудит-колонки (`created`/`updated`/`created_by`/`last_modified_by`) намеренно опущены.

- `orders` — бизнес-заказ; `order_saga_state` — состояние оркестрируемой саги 1:1 с заказом (UNIQUE `order_id`),оптимистичная блокировка колонкой `version`.
- `idempotency_keys` — UNIQUE `idempotency_key` + `request_hash` (SHA-256 payload): повтор POST /api/v1/order не создаёт второй заказ, возвращает сохранённый `response_status`/`response_body`.
- `notification_outbox` — Transactional Outbox канала ORDER → NOTIFICATION (event_id UUID UNIQUE, статусы NEW/SENT/FAILED, trace-context с V6).

```mermaid
erDiagram
    ORDERS ||--o| ORDER_SAGA_STATE : "order_id UNIQUE (1:1)"
    ORDERS ||--o| IDEMPOTENCY_KEYS : "order_id"
    ORDERS {
        BIGINT id PK
        BIGINT version "optimistic lock"
        BIGINT user_id "индекс (user_id, order_status)"
        DECIMAL price
        VARCHAR order_status "PENDING/PROCESSING/PLACED/CANCELED/FAILED"
        BIGINT product_id
        INTEGER quantity
        DATE delivery_date
        TIME slot_start
        TIME slot_end
    }
    ORDER_SAGA_STATE {
        BIGINT id PK
        BIGINT version "optimistic lock (recovery)"
        BIGINT order_id FK "UNIQUE"
        VARCHAR saga_status "STARTED...CONFIRMED/COMPENSATED/COMPENSATION_FAILED"
        VARCHAR failure_step
        VARCHAR failure_reason "до 2048 символов"
    }
    IDEMPOTENCY_KEYS {
        BIGINT id PK
        VARCHAR idempotency_key UK "заголовок Idempotency-Key"
        BIGINT user_id
        VARCHAR request_hash "SHA-256 payload"
        BIGINT order_id FK
        INTEGER response_status "201 при успехе"
        TEXT response_body "JSON заказа"
        VARCHAR saga_status
        TIMESTAMP expires_at "TTL-очистка"
    }
    NOTIFICATION_OUTBOX {
        BIGINT id PK
        VARCHAR event_id UK "UUID события"
        TEXT payload
        VARCHAR status "NEW/SENT/FAILED"
        INTEGER attempts
        TIMESTAMP sent_at
        VARCHAR traceparent "W3C trace-context"
        TEXT tracestate
    }
```
