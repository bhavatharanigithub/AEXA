# Component-level architecture — Phase 5 (integration in progress)

## 1. Design principles

- Prefer a **modular monolith** for orchestration (Spring Boot) over a fleet of tiny Java services.
- Independently deployable processes exist only where runtime or language requires it (UI, JVM API, Python models, data stores, telemetry backends).
- PostgreSQL owns **control-plane** data (catalog, users, incidents, model results, audits). High-volume telemetry stays in purpose-built stores (Prometheus, Loki, Jaeger) in later phases.
- The ML service is **queryable**, never an unrestricted actuator of infrastructure.
- Correlation does not imply causation; RCA returns evidence-ranked candidates, never confirmed causal claims.

## 2. Logical components

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         Operators / Developers                           │
│                     React dashboard (Aexa theme)                         │
└───────────────────────────────────┬──────────────────────────────────────┘
                                    │ HTTPS / JSON  (/api/v1)
┌───────────────────────────────────▼──────────────────────────────────────┐
│  Spring Boot observability orchestration backend                         │
│  Auth, catalog, incidents, telemetry evidence queries, analysis history   │
└──────┬───────────────┬──────────────────┬────────────────┬───────────────┘
       │ JDBC          │ PromQL / LogQL / Jaeger HTTP      │
┌──────▼──────┐ ┌──────▼──────┐   ┌───────▼──────┐  ┌──────▼───────┐
│ PostgreSQL  │ │ Python ML   │   │ Kafka        │  │ Prom/Loki/   │
│ / H2 local  │ │ FastAPI     │   │ telemetry    │  │ Jaeger       │
└─────────────┘ └─────────────┘   └──────────────┘  └──────────────┘
                                         ▲
                                         │ OTLP / exporters
                          ┌──────────────┴──────────────┐
                          │ OpenTelemetry Collector     │
                          └──────────────▲──────────────┘
                                         │
                          ┌──────────────┴──────────────┐
                          │ Demo microservices (later)  │
                          └─────────────────────────────┘
```

## 3. Component catalogue

| # | Component | Deployable? | Current status | Responsibility | Owns data | Talks to | Failure behaviour |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Demo microservices | Yes | **Implemented** | Generate demo traffic, controlled faults, and bounded runtime trace sampling | None (emit telemetry) | OTLP to Collector; HTTP dependency calls; backend sampler control | Restart resets sampling to 100%; control failures are explicit |
| 2 | OpenTelemetry Collector | Yes | **Implemented in Compose** | Receive OTLP; route metrics, logs, traces to their stores and Kafka | Config only | Prometheus exporter, Loki OTLP, Jaeger OTLP, Kafka | Export failures are collector errors; no fabricated backend result |
| 3 | Prometheus | Yes | **Implemented in Compose** | Scrape Collector's Prometheus exporter and retain metrics | Metrics | Spring Boot read-only PromQL adapter | Backend returns 503 `Telemetry unavailable` |
| 4 | Grafana Loki | Yes | **Implemented in Compose** | Store OTLP logs | Logs | Backend read-only evidence query | Collector retries; logs may be unavailable |
| 5 | Jaeger | Yes | **Implemented in Compose** | Store OTLP traces and expose trace search | Traces | Backend read-only evidence query | Collector retries; traces may be unavailable |
| 6 | Kafka | Yes | **Implemented in Compose** | Buffer telemetry topics for future consumers | Topics | Collector | Single-node demo only; no consumer is claimed |
| 7 | Spring Boot backend | Yes | **Phase 5 integration implemented; live verification pending** | Authenticated telemetry evidence queries, model run/history, catalog, incidents | PostgreSQL control plane | Prometheus, Loki, Jaeger, ML service, browser | Bounded timeouts; explicit 503 on unavailable dependencies |
| 8 | Python ML service | Yes | **Implemented** | Time-aware features, anomaly scoring, degradation forecasts, LIME and RCA ranking | Trained artifacts/evaluation on disk | Backend; caller-provided telemetry evidence | Missing model returns 503; input errors are explicit |
| 9 | Anomaly engine | Library inside ML | Statistical + Isolation Forest evaluated | Past-only robust rolling baseline and scaled Isolation Forest | Saved evaluation report | Timestamped metric windows | Scores are not probabilities; cold input rejected |
| 10 | Forecast engine | Library inside ML | Five-minute classifier evaluated | Predict p95 latency threshold breach within next five one-minute samples | Saved evaluation report | Timestamped metric windows | Probability threshold is 0.5; model output is not a guarantee |
| 11 | RCA engine | ML library | Evidence-ranked candidates | Rank metrics, observed trace dependency edges, and correlated error logs | Returned response (not persisted) | Evidence gathered by backend and submitted by dashboard | No causal confirmation; insufficient baselines return no candidates |
| 12 | Explainability service | ML library | LIME local surrogate | Feature contributions for detector score and forecast output | Returned response | Model inputs/outputs | Local approximation, no causal interpretation |
| 13 | Adaptive control | Backend | **Implemented deterministic baseline** | Evaluate fresh metrics/model risk, clamp sampling, apply hysteresis/cooldown/rate limits, rollback, audit | `adaptive_*` | Prometheus, ML forecast API, demo sampler endpoints | Stale telemetry, model failure, or invalid signals request full sampling; actuator failure is surfaced |
| 14 | React dashboard | Yes | **Phase 5 pages implemented; dedicated page tests and live verification pending** | Display telemetry, anomaly/forecast history, RCA evidence, incidents, and adaptive controls | None | Backend REST | Explicit loading, empty, error, and stale states; role-aware controls |
| 15 | PostgreSQL | Yes | Schema + local H2 stand-in | Control-plane store | Catalog, users, incidents, results | Backend | App unhealthy |

Redis is **optional** and not required in Phase 1. It may cache expensive PromQL/LogQL results later. It is not a source of truth.

Phase 5 provides evidence collection and read-only analysis views, not a full incident workflow. RCA output is a ranked hypothesis, not proof of causality. The dashboard does not claim model quality beyond the synthetic Phase 3 holdout.

## 4. Module boundaries (Spring Boot)

| Package | Allowed to do | Must not do |
| --- | --- | --- |
| `api` | HTTP mapping, validation, DTO mapping | Business rules, JPA queries |
| `application` | Use-cases, transactions | Framework-specific HTTP types |
| `domain` | Entities, enums, invariants | REST or SQL details |
| `infrastructure` | Repositories, security, filters | Domain policy |
| `config` | Wiring | Business logic |

Python ML service exposes validated inference HTTP endpoints. Reproducible preparation and training scripts live in `ml-service/scripts/`; `ml-service/artifacts/` records the held-out evaluation and model metadata. Docker builds train from a fixed-seed synthetic dataset. Synthetic evaluation results do not represent production accuracy.

## 5. Communication protocols

| From | To | Protocol | Auth |
| --- | --- | --- | --- |
| Dashboard | Backend | REST JSON, `/api/v1` | Bearer JWT |
| Backend | PostgreSQL | JDBC | Password from env |
| Backend | ML service | REST JSON, bounded connect/read timeouts | Host-local development port; callers authenticate to Spring; do not expose ML port publicly |
| Demo services | Collector | OTLP gRPC/HTTP | Local Compose network |
| Collector | Prometheus/Loki/Jaeger/Kafka | Prometheus scrape endpoint, OTLP, Kafka exporter | Local Compose network |
| Backend | Prometheus | PromQL HTTP API (read-only) | Internal URL from environment |
| Backend | ML service | Degradation forecast REST API (bounded timeout) | Internal URL from environment |
| Backend | Demo services | Read/write bounded trace-sampling interface | Localhost-bound published ports in demo Compose; production authentication/isolation required |

## 6. Adaptive control safety

1. Forecast output is a risk signal only. The deterministic backend policy, not the ML model, chooses the permitted SDK trace-sampling target.
2. Rates are clamped to `[0.05, 1.0]`. Risk targets are 0.10, 0.50, and 1.0 for low, elevated, and critical risk.
3. Escalation and safe full-sampling fallbacks are immediate. Reductions require three safer evaluations, cooldown, and the configured hourly automatic-change limit; the safety limit never blocks a sampling increase.
4. Disabled, stale, invalid, or unavailable telemetry/model states request the full-sampling baseline. Sampler failures are reported and the backend attempts rollback.
5. The supported demo actuator changes OpenTelemetry SDK trace sampling only. Prometheus scrape intervals remain fixed; the model process cannot call sampler endpoints.
6. Demo actuator ports are localhost-bound in Compose. Production requires an authenticated and isolated actuator interface plus measured overhead validation.

## 7. Runtime topology

The local development path runs Spring Boot with H2, React, and the ML service. Compose provides the telemetry stores and the trained ML container. Root-cause requests carry actual time-bounded metric, trace-edge, and log evidence from the caller; RCA does not query Jaeger or Loki itself.  is installed:

```
checkout-api :8081 -> inventory-api :8082 -> pricing-api :8083
     |                    |                       |
     +-------------------- OTLP ------------------+--> Collector
Collector --> Prometheus (scrape) / Loki (logs) / Jaeger (traces) / Kafka (topics)
React :5173 --> Spring Boot :8080 --> Prometheus :9090
Spring Boot --> H2 local OR PostgreSQL :5432
```
