# Saga SUCCESS — sequence-диаграмма прогона otus-fp-success-k8s

> Сгенерировано 2026-09-10 15:58 UTC скриптом `scripts/08-gen-saga-diagrams.ps1` (генератор `scripts/gen-saga-diagrams.mjs`). Не редактировать вручную — перегенерировать.
- Источник: `reports/k8s/newman-json-otus-fp-success-k8s-20260910-173713.json` — 21 запрос, 41 assertions (0 failed), длительность 17.9s, старт 2026-09-10T14:37:16.091Z

```mermaid
sequenceDiagram
    participant C as Newman (Client)
    participant O as ORDER (orchestrator)
    participant U as USER/AUTH
    participant B as BILLING
    participant W as WAREHOUSE
    participant D as DELIVERY
    participant N as NOTIFICATION

    note over C,N: Setup 01–11 (свёрнуто): 14 запросов · все ✅<br/>health ×6 · login admin/user · courier capacity 2026-09-19<br/>product #13 (10.00 ₽, qty 20) · user #10 · account 0.00 ₽ · deposit → 100.00 ₽

    rect rgb(232, 245, 233)
    note over C,N: Saga внутри POST /api/v1/order (синхронный REST /internal/**, порядок из OrderServiceImpl.runSaga)
    C->>O: 12 · POST /api/v1/order (product #13, qty=1, 10.00 ₽)
    activate O
    O->>B: 1. BILLING_WITHDRAW · POST /internal/order/withdraw (10.00 ₽)
    B-->>O: 200 OK · списано: 100.00 → 90.00 ₽
    O->>W: 2. WAREHOUSE_RESERVE · POST /internal/products/reservations (product #13, qty=1, idem-key order-9-p13)
    W-->>O: 200 OK · RESERVED
    O->>D: 3. DELIVERY_RESERVE · POST /internal/delivery/reservations (2026-09-19 12:00–14:00)
    D-->>O: 200 OK · RESERVED · courier #1
    O->>W: 4. WAREHOUSE_CONFIRM · POST /internal/products/reservations/9/confirm
    W-->>O: 200 OK · CONFIRMED
    O->>D: 5. DELIVERY_CONFIRM · POST /internal/delivery/reservations/9/confirm
    D-->>O: 200 OK · CONFIRMED
    O->>O: saga CONFIRMED · order → PLACED + событие в transactional outbox (одной транзакцией)
    O--)N: async · OutboxPublisher → RabbitMQ → notification.queue: "Order placed successfully. Payment confirmed."
    O-->>C: 201 Created · order #9 · PLACED · ✅ (509ms)
    deactivate O
    end

    note over C,N: Проверки результата (шаги коллекции, фактические ответы прогона)
    C->>O: 13 · GET /api/v1/order/9
    O-->>C: 200 · order #9 · PLACED · ✅ (36ms)
    C->>W: 14 · GET /internal/products/reservations/9
    W-->>C: 200 · 1 бронь · все CONFIRMED · ✅ (18ms)
    C->>D: 15 · GET /internal/delivery/reservations/9
    D-->>C: 200 · CONFIRMED · courier #1 · ✅ (23ms)
    C->>B: 16 · GET /api/v1/account/user/10
    B-->>C: 200 · balance 90.00 ₽ (100.00 − 10.00) · ✅ (36ms)
    C->>W: 17 · GET /api/v1/products/13/stocks
    W-->>C: 200 · available=19 · reserved=0 · ✅ (17ms)
    C->>N: 18 · GET /api/v1/notification?userId=10
    N-->>C: 200 · SUCCESS уведомление #27 · ✅ (29ms)
```

## Легенда

- `->> / -->>` — синхронный REST-вызов (сплошная/пунктирная стрелка с наконечником).
- `--) / --x` — асинхронная доставка RabbitMQ (`--)`), сбой/ошибка (`--x`, `-x`).
- Внутренние вызовы ORDER → BILLING/WAREHOUSE/DELIVERY (`/internal/**`) производные из кода
  `OrderServiceImpl.runSaga/compensate` — newman их не видит; коллекции проверяют результат.
- Уведомления: transactional outbox → scheduled `OutboxPublisher` → RabbitMQ → `NOTIFICATIONService.NotificationConsumer`.
- ✅ / ❌ — статус assertions newman по факту прогона, `(Nms)` — фактическое время ответа.

Прогон: **newman-json-otus-fp-success-k8s-20260910-173713.json**. Saga-часть — шаги 12, 13, 14, 15, 16, 17, 18; setup (14 запросов) свёрнут в note.

