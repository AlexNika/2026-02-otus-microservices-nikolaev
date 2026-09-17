# Observability-топология: 4 namespace, метрики/логи/трейсы → Grafana

> Источник фактов: `OTUS - Microservices - FP Solution.md` §14–16. Статичная схема (не из newman-отчётов), редактируется вручную вместе с `.mmd`/`.png`/`.svg`.

Приложение в `otus-msa-fp` отдаёт три сигнала: метрики (`/metrics`, scrape Prometheus из `otus-msa-monitoring` по аннотациям `prometheus.io/*` с cross-ns discovery + RBAC), логи (stdout подов → Alloy с k8s metadata → Loki в `otus-msa-logging`) и трейсы (OTLP → Tempo в `otus-msa-tracing`, sampling 1.0). Grafana — единая точка просмотра поверх всех трёх источников.

```mermaid
flowchart LR
  subgraph FP["ns otus-msa-fp"]
    ING["ingress-nginx<br/>arch.finalproject"] --> SVC["7 микросервисов Spring Boot<br/>Micrometer · OTel · Logback(trace_id/span_id)"]
  end

  subgraph MON["ns otus-msa-monitoring"]
    PROM["Prometheus<br/>scrape /metrics (prometheus.io/*)"]
    AM["Alertmanager<br/>включён субчартом monitoring;<br/>alert-правила не провижинятся — roadmap"]
    GRAF["Grafana<br/>FP Business & Patterns<br/>+ spring-boot / rabbitmq / node-exporter"]
  end
  subgraph LOG["ns otus-msa-logging"]
    ALLOY["Alloy<br/>+k8s metadata"] --> LOKI["Loki"]
  end
  subgraph TRC["ns otus-msa-tracing"]
    TEMPO["Tempo (OTLP, sampling 1.0)"]
  end

  SVC -- "METRICS: scrape по аннотациям<br/>(cross-ns discovery + RBAC)" --> PROM
  SVC -- "LOGS: stdout подов" --> ALLOY
  SVC -- "TRACES: OTLP" --> TEMPO
  PROM --> GRAF
  LOKI --> GRAF
  TEMPO --> GRAF
  PROM -. "alerts" .-> AM

  style FP fill:#e6ffe9,stroke:#4fb65f
  style MON fill:#fff6e0,stroke:#b6934f
  style LOG fill:#e9f0ff,stroke:#4f6db6
  style TRC fill:#f2e9ff,stroke:#7a4fb6
  style AM stroke-dasharray: 5 5
```

## Легенда

- Сплошные стрелки — потоки телеметрии (метрики/логи/трейсы и их подача в Grafana); пунктир в Alertmanager — компонент развёрнут субчартом, но alert-правила не провижинятся (осознанное ограничение, roadmap).
- `TZ=UTC` на всех подах — единая шкала времени для корреляции сигналов.
- `resilience4j_*` и бизнес-метрики саг/outbox/DLQ выходят через Micrometer тем же путём (scrape по аннотациям).
