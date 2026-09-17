# Межсервисное взаимодействие end-to-end: асинхронная репликация + синхронная сага + асинхронное уведомление

> Источник фактов: `OTUS - Microservices - FP Solution.md` §6, §9 и `OTUS - Microservices - FP Solution.md` §6, §9 («Полный путь данных пользователя»). Статичная схема (не из newman-отчётов), редактируется вручную вместе с `.mmd`/`.png`/`.svg`.

Один сквозной сценарий из трёх блоков: (1) регистрация — событие `user.created` из `auth_outbox` реплицирует идентичность в USER и BILLING, `account.created` активирует профиль, `user.profile.sync` разносит контакты/адреса в NOTIFICATION и DELIVERY; (2) заказ — синхронная сага по `/internal/**` (k8s DNS мимо ingress, `X-Internal-API-Key`, JWT не пробрасывается); (3) итоги заказа — `NotificationEvent` из `notification_outbox` уходит в NOTIFICATION. Детали саги — в `saga-success-sequence` / `saga-failed-billing-sequence` / `saga-flow`.

```mermaid
sequenceDiagram
    autonumber
    actor C as Клиент
    participant AUTH as AUTHService
    participant USER as USERService
    participant BILL as BILLINGService
    participant ORDER as ORDERService
    participant WH as WAREHOUSEService
    participant DEL as DELIVERYService
    participant NOTIF as NOTIFICATIONService
    participant MQ as RabbitMQ

    rect rgb(235, 244, 255)
    note over C,MQ: Блок 1 · АСИНХРОННО: регистрация и репликация идентичности/профиля (4 событийных канала)
    C->>AUTH: POST /api/v1/auth/register
    AUTH->>AUTH: INSERT auth_outbox: user.created<br/>(та же транзакция, что и auth_users)
    AUTH-->>C: 201 Created
    AUTH--)MQ: OutboxPublisher: users.events / user.created (fan-out, publisher-confirms)
    MQ--)USER: user.user-created.queue → проекция users/profile/addresses,<br/>AccountStatus PENDING (id из события)
    MQ--)BILL: billing.account-create.queue → createAccount<br/>идемпотентно по userId (natural key)
    BILL--)MQ: accounts.events / account.created (напрямую)
    BILL--)MQ: notifications.events / notification.event<br/>ACCOUNT_CREATED (best-effort, без outbox)
    MQ--)USER: user.account-activation.queue → AccountActivationConsumer:<br/>PENDING/BLOCKED → ACTIVE (повтор — no-op)
    USER--)MQ: notifications.events / notification.event<br/>ACCOUNT_ACTIVATED (best-effort)
    USER--)MQ: OutboxPublisher: user.sync.events / user.profile.sync<br/>(снэпшот контактов и адресов)
    MQ--)NOTIF: notification.profile-sync.queue → read-модель контактов
    MQ--)DEL: delivery.profile-sync.queue → read-модель адресов<br/>(сбой после ретраев → user.profile.sync.dlq)
    MQ--)NOTIF: notification.queue → ACCOUNT_CREATED / ACCOUNT_ACTIVATED<br/>(дедупликация по eventId)
    end

    rect rgb(235, 255, 238)
    note over C,DEL: Блок 2 · СИНХРОННО: сага заказа /internal/** — k8s DNS мимо ingress,<br/>X-Internal-API-Key, JWT не пробрасывается (детали: saga-success-sequence / saga-flow)
    C->>ORDER: POST /api/v1/order (JWT + Idempotency-Key)
    ORDER->>BILL: POST /internal/order/withdraw → 200 (pivot) / 409 INSUFFICIENT_FUNDS
    ORDER->>WH: POST /internal/products/reservations (all-or-nothing)
    ORDER->>DEL: POST /internal/delivery/reservations (userId владельца)
    ORDER->>WH: POST .../reservations/{orderId}/confirm
    ORDER->>DEL: POST .../reservations/{orderId}/confirm
    note over ORDER,DEL: отказ шага → компенсации: refund (BILLING), cancel (WAREHOUSE и DELIVERY).<br/>read-пробы GET-статусов — для recovery и разрешения неопределённости confirm
    ORDER-->>C: 200 PLACED / 502 FAILED
    end

    rect rgb(255, 248, 230)
    note over ORDER,NOTIF: Блок 3 · АСИНХРОННО: уведомление об итогах заказа
    ORDER->>ORDER: INSERT notification_outbox: NotificationEvent (PLACED/FAILED)<br/>в транзакции статуса заказа
    ORDER--)MQ: OutboxPublisher: notifications.events / notification.event
    MQ--)NOTIF: notification.queue → отправка уведомления<br/>(сбой после ретраев → notification.queue.dlq)
    end
```

## Легенда

- `->>` сплошная — синхронный HTTP-вызов (запрос/ответ), `-->>` — синхронный ответ, `--)` — асинхронное сообщение через RabbitMQ (fire-and-forget).
- Цветные блоки: синий — асинхронная репликация идентичности/профиля, зелёный — синхронная сага, жёлтый — асинхронное уведомление.
- Transactional outbox (AUTH `auth_outbox`, USER `user_outbox`, ORDER `notification_outbox`) — событие пишется в той же транзакции, что и бизнес-изменение, и публикуется `OutboxPublisher` с publisher-confirms; BILLING/USER в `notifications.events` — best-effort напрямую через `RabbitTemplate` (потеря допустима, ERROR-лог).
- Все консьюмеры идемпотентны (дедупликация по `eventId`/natural key); после исчерпания ретраев — DLQ (`user.profile.sync.dlq`, `notification.queue.dlq`).
- Все шаги и компенсации саги идемпотентны; сага принципиально синхронная — оркестратору нужен немедленный вердикт шага.
