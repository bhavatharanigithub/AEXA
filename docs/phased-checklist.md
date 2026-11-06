# Phased implementation checklist

## Phase 1 — Foundation

- [x] Inspect machine toolchain and repository
- [x] Requirements, architecture, data-flow, compatibility, DB, API, risks
- [x] Repository layout
- [x] Flyway schema
- [x] Spring Boot health, auth, catalog, incidents, tests
- [x] React themed shell + tests
- [x] FastAPI health + tests
- [x] Docker Compose for PostgreSQL
- [x] Windows setup documentation
- [x] Docker-based PostgreSQL verification
- [ ] GitHub remote (optional; local git commit is the Phase 1 evidence)

## Phase 2 — Demo services and telemetry path (implemented and verified)

- [x] Three instrumented services with controlled failure modes
- [x] OTel Collector, Prometheus, Loki, Jaeger, Kafka in Compose
- [x] Shared attribute enforcement
- [x] Backend read-only Prometheus query adapter with explicit unavailable errors
- [x] Dashboard live panels from Prometheus queries; synthetic traffic is labelled
- [x] Backend, frontend, and demo-service tests
- [x] Start Compose and verify stores, demo-service health, Prometheus samples, and backend telemetry

## Phase 3 — AI analysis (implemented)

- [x] Deterministic labeled data with missing values and irregular timestamps
- [x] Past-only features, chronological 70/30 holdout, and five-minute leakage gap
- [x] Rolling median/MAD baseline and RobustScaler + Isolation Forest with actual evaluation
- [x] Five-minute latency-degradation classifier compared to persistence baseline
- [x] LIME feature contributions for anomaly and forecast outputs
- [x] RCA candidate ranking from supplied metric, trace-edge, and log evidence; no causal claims
- [x] Validated FastAPI schemas and authenticated Spring proxy with bounded timeouts/errors
- [x] Saved evaluation report, model metadata, reproducible scripts, and automated tests

## Phase 4 — Adaptive telemetry control

- [x] Deterministic bounded controller with low/elevated/critical risk tiers
- [x] Runtime OTel SDK trace sampling actuation on demo services; fixed Prometheus scrape interval
- [x] Safe disabled/full-sampling defaults, stale/model failure fallback, rollback, and failure reporting
- [x] Escalation, three-evaluation hysteresis, cooldown, automatic change limit, and persisted audit history
- [x] Admin-only enable/disable/evaluate/rollback API; viewer state and audit endpoints
- [x] React controls, per-service sampling/risk state, safety limits, and audit history
- [x] Deterministic replay comparing fixed and adaptive sampled-span proxy and synthetic failure detection
- [x] Windows setup, API contracts, safety boundary, limitations, and reproducible evaluation documented

## Phase 5 — End-to-end integration (in progress; not complete)

- [x] Add authenticated Spring endpoints that query timestamped Prometheus metrics, Loki logs, and Jaeger traces with bounded inputs/timeouts
- [x] Add model-run and persisted-history APIs for anomaly and forecast results; save returned explanations without inventing values
- [x] Add dashboard routes for telemetry exploration, anomaly history, forecasts, RCA evidence, and persisted incidents
- [x] Enforce analyst/admin authorization on analysis execution; test API authentication and request bounds
- [x] Add backend and frontend container builds and wire the application into local Compose
- [x] Verify live Prometheus/Loki/Jaeger reads and anomaly/forecast persistence with Compose PostgreSQL and ML service
- [x] Add page tests for anomaly history, forecast output, RCA evidence, telemetry empty/stale/error states, and analyst/viewer behavior
- [x] Execute and save normal, latency, dependency-failure, bounded CPU-pressure, error-burst, and recovery scenarios
- [x] Capture reproducible local API latency, bounded concurrent-request, telemetry evidence, and analysis persistence observations
- [ ] Measure sustained ingestion throughput, peak/attributable resource consumption, forecast calibration, RCA ranking quality against ground truth, and adaptive overhead
- [x] Publish actual local observations with synthetic-data and measurement limitations documented
- [x] Add app-tier Kubernetes demonstration manifests with probes, resource requests/limits, and external Secret references
- [ ] Apply and validate Kubernetes manifests against a configured cluster
- [x] Document Compose startup/shutdown, secrets, health, persistence, deployment limitations, APIs, architecture, and the demo script

## Remaining platform work

- [ ] Production-grade authenticated collector/sampler actuation and rollout governance
- [ ] Incident lifecycle management beyond read-only incident history
- [ ] Validate models and RCA utility on labeled real incidents and unseen services before production claims
