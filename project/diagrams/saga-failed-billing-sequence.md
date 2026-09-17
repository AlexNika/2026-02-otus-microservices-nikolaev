# Saga FAILED (billing) — sequence-диаграмма прогона otus-fp-failed-billing-k8s

> Сгенерировано 2026-09-10 15:58 UTC скриптом `scripts/08-gen-saga-diagrams.ps1` (генератор `scripts/gen-saga-diagrams.mjs`). Не редактировать вручную — перегенерировать.
- Источник: `reports/k8s/newman-json-otus-fp-failed-billing-k8s-20260910-173713.json` — 19 запросов, 36 assertions (0 failed), длительность 15.1s, старт 2026-09-10T14:37:36.862Z

```mermaid
sequenceDiagram
    participant C as Newman (Client)
    participant O as ORDER (orchestrator)
    participant B as BILLING
    participant W as WAREHOUSE
    participant D as DELIVERY
    participant N as NOTIFICATION

    note over C,N: Setup 01–10 (свёрнуто): 13 запросов · все ✅<br/>health ×6 · login admin/user · courier capacity · product #14 (10.00 ₽)<br/>user #11 · account 0.00 ₽ · БЕЗ депозита — сценарий отказа платежа

    rect rgb(255, 235, 238)
    note over C,N: Отказ saga внутри POST /api/v1/order: сбой на первом шаге BILLING_WITHDRAW
    C->>O: 11 · POST /api/v1/order (product #14, qty=1, 10.00 ₽)
    activate O
    O->>B: 1. BILLING_WITHDRAW · POST /internal/order/withdraw (10.00 ₽)
    B--xO: 409 · BILLING_INSUFFICIENT_FUNDS (balance 0.00 ₽, требуется 10.00 ₽)
    note over O: runSaga catch → compensate() (OrderServiceImpl:466)<br/>сбой на ПЕРВОМ шаге: billingReserved=false · warehouseReserved=false · deliveryReserved=false
    opt компенсация — обратный порядок, только реально выполненные шаги
        O--)D: compensation: POST /internal/delivery/reservations/10/cancel — ПРОПУЩЕН (брони нет)
        O--)W: compensation: POST /internal/products/reservations/10/cancel — ПРОПУЩЕН (брони нет)
        O--)B: compensation: POST /internal/order/refund — ПРОПУЩЕН (деньги не списаны)
    end
    O->>O: saga COMPENSATED · order #10 → FAILED + событие в transactional outbox
    O--)N: async · OutboxPublisher → RabbitMQ → notification.queue: "Order processing failed: ..."
    O--xC: 502 Bad Gateway · BillingServiceException → 502 · assert: ожидался 502 ✅ (139ms)
    deactivate O
    end

    note over C,N: Проверки компенсаций — коллекция фиксирует отсутствие побочных эффектов
    C->>O: 12 · GET /api/v1/order/user/11
    O-->>C: 200 · order #10 · FAILED (единственный заказ пользователя) · ✅ (17ms)
    C->>W: 13 · GET /internal/products/reservations/10
    W-->>C: 404 · брони склада НЕТ — до WAREHOUSE_RESERVE дело не дошло · ✅ (36ms)
    C->>D: 14 · GET /internal/delivery/reservations/10
    D-->>C: 404 · брони доставки НЕТ — до DELIVERY_RESERVE дело не дошло · ✅ (23ms)
    C->>B: 15 · GET /api/v1/account/user/11
    B-->>C: 200 · balance 0.00 ₽ — списания не было, refund не требуется · ✅ (13ms)
    C->>N: 16 · GET /api/v1/notification?userId=11
    N-->>C: 200 · FAILED уведомление #30 (BILLING_INSUFFICIENT_FUNDS) · ✅ (14ms)
```

## Легенда

- `->> / -->>` — синхронный REST-вызов (сплошная/пунктирная стрелка с наконечником).
- `--) / --x` — асинхронная доставка RabbitMQ (`--)`), сбой/ошибка (`--x`, `-x`).
- Внутренние вызовы ORDER → BILLING/WAREHOUSE/DELIVERY (`/internal/**`) производные из кода
  `OrderServiceImpl.runSaga/compensate` — newman их не видит; коллекции проверяют результат.
- Уведомления: transactional outbox → scheduled `OutboxPublisher` → RabbitMQ → `NOTIFICATIONService.NotificationConsumer`.
- ✅ / ❌ — статус assertions newman по факту прогона, `(Nms)` — фактическое время ответа.

Прогон: **newman-json-otus-fp-failed-billing-k8s-20260910-173713.json**. Отказ на первом шаге saga (BILLING_WITHDRAW → BILLING_INSUFFICIENT_FUNDS), компенсации пропущены — откатывать нечего; отсутствие побочных эффектов подтверждают проверки (404/404, balance 0, FAILED-уведомление).

