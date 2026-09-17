# Два контура межсервисного взаимодействия: синхронный REST /internal/** и асинхронный RabbitMQ

> Источник фактов: `OTUS - Microservices - FP Solution.md` §6, §9 и `OTUS - Microservices - FP Solution.md` §6, §9. Статичная схема (не из newman-отчётов), редактируется вручную вместе с `.mmd`/`.png`/`.svg`.

Синхронный контур — только REST `/internal/...` по k8s DNS (`http://fp-billing-service:8001/...`), мимо ingress, защита — `X-Internal-API-Key` (Security-цепочка №0 `@Order(0)`, fail-closed). Асинхронный контур — 4 exchange RabbitMQ: репликация идентичности (`user.created`), синхронизация профиля (`user.profile.sync`), активация аккаунта (`account.created`), уведомления (`notification.event`). Сага принципиально идёт через синхронный REST, не через брокер.

```mermaid
flowchart TB
    C[Клиент<br/>JWT access/refresh]

    subgraph ING["ingress-nginx · arch.finalproject"]
        PUB["/api/v1/** (JWT)"]
        IEX["/internal/products|delivery|account|order<br/>— только для postman k8s-тестов, с internal-ключом<br/>(в PROD закрыть)"]
    end

    SEC[("k8s Secret → env:<br/>INTERNAL_API_KEY<br/>(общий для всех сервисов)")]

    subgraph SYNC["Сервисы — синхронный контур /internal/**<br/>(Security-цепочка №0 @Order(0): X-Internal-API-Key,<br/>нет/неверен → 401, не сконфигурирован → fail-closed 500)"]
        direction TB
        AUTH["AUTHService :8006<br/>auth_db + auth_outbox"]
        USER["USERService<br/>users/profile/addresses,<br/>AccountStatus + user_outbox"]
        BILL["BILLINGService<br/>accounts/transactions<br/>(владелец денег)"]
        ORDER["ORDERService — оркестратор саги<br/>orders + notification_outbox"]
        WH["WAREHOUSEService<br/>стоки/резервы<br/>(БЕЗ AMQP — только sync)"]
        DEL["DELIVERYService<br/>слоты/брони + проекция адресов"]
        NOTIF["NOTIFICATIONService<br/>отправка + проекция контактов"]
    end

    C --> ING
    PUB --> AUTH & USER & BILL & ORDER & WH & DEL & NOTIF

    ORDER -- "withdraw / refund" --> BILL
    ORDER -- "reservations + confirm/cancel/GET" --> WH
    ORDER -- "reservations + confirm/cancel/GET" --> DEL
    USER -- "POST /internal/account (запасной путь)<br/>GET /internal/account/user/{id} (read-проба)" --> BILL

    subgraph ASYNC["RabbitMQ — асинхронный контур (fire-and-forget;<br/>сага принципиально НЕ через брокер)"]
        direction LR
        X1{{"users.events<br/>user.created"}}
        X2{{"user.sync.events<br/>user.profile.sync"}}
        X3{{"accounts.events<br/>account.created"}}
        X4{{"notifications.events<br/>notification.event"}}
        DLQ[("DLQ:<br/>user.profile.sync.dlq<br/>notification.queue.dlq")]
    end

    AUTH -. "auth_outbox → OutboxPublisher<br/>(publisher-confirms)" .-> X1
    X1 -. "fan-out: проекция (PENDING)" .-> USER
    X1 -. "fan-out: createAccount идемпотентно" .-> BILL
    BILL -. "AccountEventPublisher (напрямую)" .-> X3
    X3 -. "активация PENDING/BLOCKED → ACTIVE" .-> USER
    USER -. "user_outbox → OutboxPublisher<br/>(снэпшот профиля)" .-> X2
    X2 -. "read-модель контактов" .-> NOTIF
    X2 -. "read-модель адресов" .-> DEL
    ORDER -. "notification_outbox → OutboxPublisher<br/>(PLACED/FAILED)" .-> X4
    BILL -. "ACCOUNT_CREATED (best-effort, без outbox)" .-> X4
    USER -. "ACCOUNT_ACTIVATED (best-effort, без outbox)" .-> X4
    X4 -. "notification.queue<br/>(дедупликация по eventId)" .-> NOTIF
    X2 & X4 -. "сбой после ретраев" .-> DLQ
    SEC -. "env" .-> SYNC

    style SYNC fill:#e9fbe9,stroke:#3a8f3a
    style ASYNC fill:#fff3d6,stroke:#b58a00
    style ING fill:#f2e9ff,stroke:#7a4fb6
    style SEC fill:#ffe9e9,stroke:#b64f4f
```

## Легенда

- Сплошные стрелки — синхронный REST (клиент через ingress по JWT; сервис→сервис по `/internal/**` через k8s DNS с `X-Internal-API-Key`); пунктирные — асинхронные сообщения RabbitMQ.
- Зеленый контур — синхронный (сага заказа: ORDER вызывает BILLING/WAREHOUSE/DELIVERY; идемпотентные шаги и компенсации), жёлтый — асинхронный (4 exchange + DLQ), фиолетовый — ingress, красный — общий секрет internal-ключа.
- Transactional outbox у AUTH/USER/ORDER (критичные события); BILLING/USER публикуют `notification.event` best-effort напрямую (потеря допустима).
- WAREHOUSE — единственный сервис без AMQP: склад участвует только в синхронном контуре саги.
- Вывод `/internal/**` через ingress наружу — осознанно для postman k8s-тестов (доступ только с internal-ключом; в PROD закрыть).
