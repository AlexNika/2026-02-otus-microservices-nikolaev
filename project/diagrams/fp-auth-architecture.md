# Архитектура аутентификации: единый Issuer + локальная валидация

> Источник фактов: `OTUS - Microservices - FP Solution.md` §6.1 и `OTUS - Microservices - FP Solution.md` §6.1. Статичная схема (не из newman-отчётов), редактируется вручную вместе с `.mmd`/`.png`/`.svg`.

AUTHService — единственный центр генерации токенов и владелец secrets; все остальные сервисы валидируют JWT локально по HMAC-подписи общими компонентами COMMONDomain, без обращения к AUTH или его БД (нет единой точки отказа для чтения запросов).

```mermaid
flowchart TB
    C[Клиент<br/>access-JWT 15 мин + refresh 30 дней]

    subgraph ING[ingress-nginx · arch.finalproject]
        direction LR
        R1["/api/v1/auth|user|roles"]
        R2["/api/v1/profile|account|order|<br/>products|delivery|notification"]
    end

    subgraph AUTHZ0[AUTHService :8006 — единственный Issuer и владелец secrets]
        EP["register / login / refresh — permitAll<br/>logout — по JWT<br/>/api/v1/user, /roles — только ADMIN"]
        ADB[("auth_db:<br/>auth_users (BCrypt-12),<br/>refresh_tokens (SHA-256, ротация,<br/>reuse detection → purge),<br/>auth_outbox")]
        ISS["JwtTokenProvider (COMMONDomain)<br/>ГЕНЕРАЦИЯ токенов — только здесь<br/>HMAC-ключ подписи"]
        EP --> ADB
        EP --> ISS
    end

    SEC[("k8s Secret<br/>JWT_SECRET_KEY (HMAC)<br/>INTERNAL_API_KEY")]
    SEC -. "env: ключ проверки подписи" .-> SVC1
    SEC -. "env: ключ проверки подписи" .-> SVC2
    SEC -. "env: ключ проверки подписи" .-> AUTHZ0

    subgraph SVCS[Остальные сервисы — валидация JWT ЛОКАЛЬНО по подписи, без сети и БД]
        direction LR
        SVC1["USER · BILLING · ORDER ·<br/>WAREHOUSE · DELIVERY · NOTIFICATION"]
        SVC2["SecurityConfig — 3 цепочки:<br/>№0 /internal/** → X-Internal-API-Key (fail-closed)<br/>№1 /actuator/** permitAll (chaosmonkey — ADMIN)<br/>№2 /api/v1/** → JWT: JwtAuthenticationFilter →<br/>AuthPrincipal → RBAC (USER/ADMIN) →<br/>ownership @authz.ownerOrAdmin(userId)"]
        SVC1 --- SVC2
    end

    INT["внутренние вызовы /internal/**<br/>(сага): по k8s DNS,<br/>JWT НЕ пробрасывается —<br/>второй контур: X-Internal-API-Key"]
    SVC1 -. "защита /internal/**<br/>(не JWT)" .-> INT

    MQ[("RabbitMQ<br/>users.events / user.created")]
    ISS -->|"user.created через auth_outbox<br/>(репликация идентичности)"| MQ
    MQ --> SVC1

    C --> ING
    R1 --> AUTHZ0
    R2 --> SVC1

    style AUTHZ0 fill:#e8f1ff,stroke:#3b6fb6
    style SVCS fill:#e9fbe9,stroke:#3a8f3a
    style SEC fill:#fff3d6,stroke:#b58a00
    style INT fill:#f5f5f5,stroke:#888,color:#333
```

## Легенда

- Сплошные стрелки — синхронный HTTP-трафик; пунктирные (`-.-`) — предоставление конфигурации (env-переменные из k8s Secret) и примечание о внутреннем контуре.
- `AUTHZ0` — единственный сервис с правами генерации JWT (`JwtTokenProvider`); `SVCS` — все остальные сервисы, используют тот же класс COMMONDomain только для **валидации** подписи локально.
- `SEC` (`JWT_SECRET_KEY`, `INTERNAL_API_KEY`) — общий k8s Secret, пробрасывается как env во все поды (AUTH — для подписи, остальные — только для проверки подписи).
- `INT` — второй контур защиты: вызовы `/internal/**` (сага ORDER → BILLING/WAREHOUSE/DELIVERY) идут по k8s DNS и проверяются по `X-Internal-API-Key`, JWT туда **не** пробрасывается.
- `MQ` — асинхронная репликация идентичности: `user.created` через `auth_outbox` достигает USER (проекция профиля) и BILLING (создание счёта) без прямого обращения к AUTH.
