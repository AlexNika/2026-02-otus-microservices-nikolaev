# Три столпа observability и корреляция через trace_id

> Источник фактов: `OTUS - Microservices - FP Solution.md` §14–16. Статичная схема (не из newman-отчётов), редактируется вручную вместе с `.mmd`/`.png`/`.svg`.

Метрики («сколько и как быстро»), логи («что произошло») и трейсы («путь запроса») связаны воедино через `trace_id`: Logback кладёт `trace_id`/`span_id` в MDC каждой строки лога, W3C `traceparent` пробрасывается сквозь HTTP и AMQP. Derived field в Loki даёт клик по `trace_id` в логе → переход к трассе в Tempo; из проблемного span'а — обратно к логам по `trace_id`; с аномалии метрики на дашборде — поиск трассы.

```mermaid
flowchart TB
  APP["Сервисы otus-msa-fp<br/>Micrometer · Logback MDC(trace_id/span_id) · OTel"]

  subgraph M["МЕТРИКИ — 'сколько и как быстро'"]
    PROM["Prometheus<br/>scrape по аннотациям prometheus.io/*"]
  end
  subgraph L["ЛОГИ — 'что произошло'"]
    ALLOY["Alloy"] --> LOKI["Loki"]
  end
  subgraph T["ТРЕЙСЫ — 'путь запроса'"]
    TEMPO["Tempo<br/>OTLP, sampling 1.0, traceparent (W3C) сквозь HTTP и AMQP"]
  end
  GRAF["Grafana — single pane of glass"]

  APP --> PROM
  APP --> ALLOY
  APP --> TEMPO
  PROM --> GRAF
  LOKI --> GRAF
  TEMPO --> GRAF
  GRAF -- "аномалия метрики на дашборде → ищем трассу" --> TEMPO
  LOKI -- "derived field trace_id: клик из лога → трасса" --> TEMPO
  TEMPO -- "из проблемного span'а → логи по trace_id" --> LOKI
  AMNOTE["Alertmanager: развёрнут, правила не провижинятся — roadmap"]

  style M fill:#fff6e0,stroke:#b6934f
  style L fill:#e9f0ff,stroke:#4f6db6
  style T fill:#f2e9ff,stroke:#7a4fb6
  style GRAF fill:#e6ffe9,stroke:#4fb65f
  style AMNOTE stroke-dasharray: 5 5
```

## Легенда

- Три столпа: МЕТРИКИ (Prometheus), ЛОГИ (Alloy → Loki), ТРЕЙСЫ (Tempo); переходы между ними — по `trace_id`.
- Дашборды Grafana: «FP Business & Patterns» (саги, outbox, DLQ, revenue) + готовые spring-boot / rabbitmq / node-exporter.
- Alertmanager (пунктир) — развёрнут субчартом monitoring, alert-правила не провижинятся (roadmap).
