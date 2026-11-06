# Phase 5 integration evaluation (in progress)

Phase 5 is not complete. The application and telemetry integrations are implemented, but completion requires reproducible end-to-end evidence across the required failure scenarios, measured overhead, and Kubernetes cluster validation.

## Verification environment

- Windows development host with Docker Desktop using Linux containers.
- Compose PostgreSQL, Prometheus, Loki, Jaeger, OpenTelemetry Collector, Kafka, instrumented synthetic demo services, Python ML service, Spring Boot API, and React production build.
- The demo driver is [`../scripts/phase5_demo.ps1`](../scripts/phase5_demo.ps1). Its generated local result is [`phase-5-demo-results.json`](./phase-5-demo-results.json).
- Phase 3 accuracy metrics are reported separately in [`phase-3-evaluation.md`](./phase-3-evaluation.md) and are from deterministic synthetic data.
- Adaptive replay measurements are in [`phase-4-evaluation.md`](./phase-4-evaluation.md) and are a sampled-span proxy, not measured CPU, memory, network, or storage overhead.

## Automated test results

| Component | Command | Result |
| --- | --- | --- |
| Spring backend | `cd D:\Final_year_project\backend; mvn test` | 25 tests passed, 0 failures/errors |
| React dashboard | `cd D:\Final_year_project\frontend; npm test -- --run` | 14 tests passed, 0 failures |
| React production build | `cd D:\Final_year_project\frontend; npm run build` | TypeScript check and Vite build passed |
| Demo services | `cd D:\Final_year_project\demo-services; .\.venv\Scripts\python.exe -m pytest` | 5 tests passed |
| FastAPI / ML service | `cd D:\Final_year_project\ml-service; .\.venv\Scripts\python.exe -m pytest` | 10 tests passed |
| Docker Compose | `docker compose -f infra\docker-compose.yml config --quiet` | Passed |
| Kubernetes YAML | Parsed as eight YAML documents offline | Syntax parsed; no cluster is configured for deployment validation |

These are software test results, not service SLO or performance claims. The Phase 5 page tests cover telemetry data/empty/stale/error states, model run/history views, root-cause evidence display, duplicate trace-edge removal, and viewer role behavior; broad browser E2E coverage remains outstanding.

## Results and limitations

The latest successful local scenario is recorded in [`phase-5-demo-results.json`](./phase-5-demo-results.json). The report includes 34 HTTP operations, all meeting their scenario-specific expected statuses; it captured 32 checkout metric points, 100 returned Loki log entries (the configured limit), and 250 Jaeger spans. The run persisted two anomaly results and a forecast result; the queried histories contained 10 anomaly and four forecast records. RCA returned three evidence-ranked candidates and `causality_confirmed: false`. The driver now writes its JSON report on both successful and failed runs, before it exits.

The scenario's mixed set of endpoints and injected faults had a measured latency median of 43.45 ms and p95 of 1,583.44 ms; these figures combine fast telemetry reads, nested demo requests, analysis calls, and error responses. They are not a homogeneous endpoint benchmark or an SLO. The capped eight-request probe completed in 2.03 seconds (3.94 observed requests/second) with all responses 200; it is not a sustained ingestion throughput test.

The bounded CPU-pressure scenario executes a fixed 20 ms CPU workload per request in the checkout demo service. The script captures instantaneous `docker stats` before/after snapshots; they are not peak or attributable resource overhead. No host-wide stress test or claimed CPU/memory savings was performed.

No production throughput, latency SLO, hardware overhead, real-incident precision/recall, forecast calibration, or RCA ranking-quality claim is established. RCA candidate ranking remains non-causal and is not currently persisted as an incident. Kubernetes manifests are app-tier scaffolding with external dependencies; they have not been applied to a cluster.

Any scenario with a non-200 expected status, failed telemetry source, failed persistence, or failure to restore injected faults must be reported as a failed run. Do not replace missing evidence with estimates.
