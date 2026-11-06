# Aexa — Adaptive Explainable Observability Platform

Final-year engineering project: an adaptive, explainable observability platform for cloud-native microservices.

**Current increment: Phase 3 — AI-assisted telemetry analysis.** Demo workload telemetry and model evaluation use synthetic data; results are not claims of production accuracy or confirmed causality.

## What is implemented

- Requirements, architecture, telemetry contract, API and database design (`docs/`)
- Spring Boot 3.4.5 orchestration API (health, JWT auth, service catalog, empty incidents)
- React + TypeScript public landing page and authenticated investigation dashboard with Prometheus-backed demo metrics
- FastAPI ML service with time-ordered anomaly detection, degradation forecasting, LIME explanations, and evidence-ranked RCA candidates
- Reproducible synthetic telemetry preparation/training scripts with saved held-out evaluation and model metadata
- Authenticated Spring Boot proxy APIs with request validation, timeouts, and explicit ML-unavailable errors
- Flyway control-plane schema
- Docker Compose for PostgreSQL, Kafka, OpenTelemetry Collector, Prometheus, Loki, Jaeger, and three demo services
- Automated backend, frontend, demo-service, and ML health tests

## Repository layout

```
backend/          Spring Boot API
frontend/         React dashboard
ml-service/       FastAPI inference process
infra/            Docker Compose, OTel Collector, Prometheus, Loki
demo-services/    Instrumented checkout, inventory, pricing workloads
docs/             Architecture and contracts
```

## Quick start (Windows)

See [docs/setup.md](docs/setup.md) for full commands.
Docker Desktop and the telemetry stack have been verified in the development environment. Compose builds and evaluates the ML service model during image construction.

```powershell
cd D:\Final_year_project\infra
docker compose up -d --build
```

Then start the backend and frontend in separate PowerShell terminals using the commands in `docs/setup.md`.

## Documentation map

| Document | Purpose |
| --- | --- |
| [docs/requirements.md](docs/requirements.md) | Functional / non-functional requirements |
| [docs/architecture.md](docs/architecture.md) | Components, ownership, failure handling |
| [docs/telemetry-data-flow.md](docs/telemetry-data-flow.md) | Implemented signal path and join keys |
| [docs/technology-compatibility.md](docs/technology-compatibility.md) | Chosen versions |
| [docs/database-design.md](docs/database-design.md) | Schema |
| [docs/api-contracts.md](docs/api-contracts.md) | REST examples |
| [docs/phase-3-evaluation.md](docs/phase-3-evaluation.md) | Actual synthetic holdout metrics and limitations |
| [docs/phased-checklist.md](docs/phased-checklist.md) | Phase status |
| [docs/risks-acceptance.md](docs/risks-acceptance.md) | Risks and acceptance gates |
| [docs/conventions.md](docs/conventions.md) | Logging, security, UI theme |
| [docs/setup.md](docs/setup.md) | Windows runbook |

The Phase 3 evaluation report is generated in `ml-service/artifacts/evaluation.json` by `python scripts/train_models.py`. Metrics describe only the reproducible synthetic chronological holdout; see [docs/phase-3-evaluation.md](docs/phase-3-evaluation.md) for measured metrics and limitations.
"# AEXA" 
