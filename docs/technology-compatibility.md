# Technology compatibility plan

## Selected versions (Phase 3)

| Technology | Version | Role | Notes |
| --- | --- | --- | --- |
| Java | 21.0.9 (Temurin) | Backend runtime | Installed |
| Maven | 3.9.11 | Build | Installed |
| Spring Boot | 3.4.5 | API | Java 21, Actuator, Security, JPA |
| Spring Data JPA | managed by Boot | Persistence | Hibernate 6.x via Boot |
| Flyway | managed by Boot | Migrations | SQL migrations, not Hibernate `ddl-auto` in postgres profile |
| PostgreSQL | 16 | Canonical DB | Local service present; Compose image `postgres:16-alpine` |
| H2 | managed by Boot | Local/test DB | `MODE=PostgreSQL`; **not** production |
| Node.js | 22.12.0 | Frontend toolchain | Installed |
| npm | 10.9.0 | Packages | Installed |
| React | 18.3.1 | UI | SPA |
| TypeScript | 5.6.3 | UI typing | Strict |
| Vite | 5.4.11 | Dev server / build | |
| Python | 3.13.3 | Demo workloads / ML service | Installed; training/inference supported |
| FastAPI | 0.115.6 | Demo workload HTTP | |
| Uvicorn | 0.32.1 | ASGI | |
| NumPy | 2.2.6 | Feature arrays | Pinned in ML service |
| pandas | 2.2.3 | UTC timestamp alignment and feature preparation | Past-only filling/features |
| scikit-learn | 1.6.1 | Isolation Forest and Random Forest | Pinned; chronological evaluation |
| LIME | 0.2.0.1 | Local model-agnostic explanations | Contributions are local surrogate approximations |
| OpenTelemetry Python SDK/exporters | 1.29.0 | Demo traces, metrics, logs | Pinned in `demo-services/requirements.txt` |
| OpenTelemetry Python instrumentors | 0.50b0 | FastAPI and HTTPX tracing | Pinned; compatible with SDK 1.29 |
| Kafka | Apache Kafka 3.9.1 | OTLP event topics | Single-node KRaft demo |
| Prometheus | 3.1.0 | Metrics store/query API | Scrapes Collector exporter |
| Loki | 3.3.2 | Log store | OTLP HTTP ingestion |
| Jaeger | 1.65.0 | Trace store/UI | OTLP receiver enabled |
| OpenTelemetry Collector Contrib | 0.123.0 | Signal routing | OTLP receiver and Prometheus/Loki/Jaeger/Kafka exporters |
| Docker Compose | Compose plugin | Local dev stack | Docker Desktop 4.97.0 / Engine 29.9.2 |

## Compatibility decisions

1. **Spring Boot 3.4.5** is a stable 3.x line compatible with Java 21. Spring Boot 4 is not used.
2. **Python OpenTelemetry SDK** instruments the Phase 2 demo services. Spring Boot remains without Java auto-instrumentation; backend requests Prometheus through a read-only adapter.
3. **Redis** is listed in the original stack but has no measured Phase 2 workload. Including it unused would duplicate PostgreSQL for session-less JWT auth. Add caching only after measuring a need.
4. **Python 3.13** supports the pinned NumPy, pandas, scikit-learn, and LIME wheels used for Phase 3.
5. **Grafana** as a product UI is **not** used for the student dashboard. The React app is the investigation UI. Grafana may be added later only as an optional operator view onto Prometheus/Loki, not as a second source of truth.
6. **JWT** is used instead of sessions so the API stays stateless for a future Kubernetes demo.

## Local constraint

Docker Desktop is available, and Compose services have been started and health-checked. Local paths remain available:

- Maven + H2 for the backend
- Vite for the frontend
- Python venv for demo-service tests

Compose is the full telemetry-stack path after Docker is installed.
