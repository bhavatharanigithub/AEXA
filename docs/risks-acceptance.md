# Risks, dependencies, and acceptance criteria

## Dependencies

| Dependency | Status on 2026-10-26 | Impact |
| --- | --- | --- |
| JDK 21 | Installed Temurin 21.0.9 | Backend can build |
| Maven 3.9 | Installed 3.9.11 | Backend can build |
| Node 22 | Installed 22.12.0 | Frontend can build |
| Python 3.13 | Installed | FastAPI, scikit-learn, LIME |
| Git | Installed | Version control |
| Docker Desktop 4.97.0 / Engine 29.9.2 | Installed and running | Compose telemetry and ML stack |
| PostgreSQL 16 | Service running; password unknown | Cannot bind backend to local PG until credentials are set |
| Network for Maven/npm/PyPI | Required on first build | Downloads |

## Risks

| ID | Risk | Mitigation |
| --- | --- | --- |
| R1 | Docker/WSL and disk pressure can interrupt local containers | Compose services use restart policies; Docker data is configured on D: |
| R2 | H2 diverges from PostgreSQL | SQL kept portable; postgres profile + Flyway is canonical |
| R3 | Synthetic evaluation may not generalize to real production | Label all evaluation data/source; do not imply production accuracy |
| R4 | Scope explosion (too many services) | Modular monolith + 1 ML process |
| R5 | Fake-looking UI metrics | Dashboard uses Prometheus queries, labels synthetic traffic, separates empty data from failures |
| R6 | Unbounded adaptive control | Clamps, cooldown, audit, no direct ML→infra |
| R7 | Treating correlation as cause | API/docs/UI disclaimer required in RCA phase |
| R8 | Secret leakage | `.env` gitignored; local JWT secret labelled development-only |

## Phase 1 acceptance criteria (historical)

| Criterion | How verified |
| --- | --- |
| Every component has a written responsibility | `docs/architecture.md` |
| Required Phase 1 services start | documented commands; backend + ML + frontend |
| Health checks work | `/api/v1/health`, `/actuator/health`, ML `/health` |
| Database connectivity verified | health `database: UP` against H2 (and Postgres when profiled) |
| Initial backend and frontend tests pass | `mvn test`, `npm test`, `pytest` |
| Architecture and API docs committed | git commit of `docs/` + source |

## Phase 2 acceptance criteria

| Criterion | How verified |
| --- | --- |
| Three services emit trace-correlated metrics/logs/traces and controlled faults | Demo-service tests and Compose service health checks |
| Collector sends metrics/logs/traces to Prometheus/Loki/Jaeger and telemetry topics to Kafka | Compose config and container health checks on the development machine |
| Backend returns Prometheus-backed aggregate and per-service metrics | Backend unit tests with real Prometheus API response shapes |
| Dashboard displays samples and explicit empty/unavailable states | Vitest component tests |
| All reported measurements are taken from Prometheus | Query adapter only uses PromQL; no fallback values |

## Phase 3 acceptance criteria

| Criterion | How verified |
| --- | --- |
| Reproducible time-aware dataset with missing and irregular samples | Fixed-seed preparation script, data hash, pytest |
| No future-data leakage in features or evaluation split | Past-only feature tests, chronological holdout, five-minute target gap |
| Statistical baseline and Isolation Forest evaluated on held-out labels | `ml-service/artifacts/evaluation.json`; actual precision, recall, F1, FPR, detection delay |
| Forecast compared to persistence baseline | Same evaluation report; five-minute horizon and 1-second p95 target |
| Explainability and root-cause evidence are explicit | LIME contributions; RCA tests; causality disclaimer |
| Spring and ML APIs validate requests and expose upstream failures | Spring API and ML service tests |

Phase 3 evaluation uses synthetic labeled data. It does not estimate production accuracy or confirm causal relationships.

## Explicit non-acceptance (must not be claimed)

- Production accuracy/generalization or MTTR improvement
- Root-cause causal accuracy or causal proof
- Production readiness
