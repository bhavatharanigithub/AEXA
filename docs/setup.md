# Setup, startup, shutdown, troubleshooting (Windows)

Commands use PowerShell and assume the repository is `D:\Final_year_project`. Docker Desktop and the telemetry stack have been verified on this development machine. Phase 3 evaluation metrics are from deterministic synthetic telemetry only.

## Toolchain

| Tool | Inspected version/status |
| --- | --- |
| Java | Temurin OpenJDK 21.0.9 |
| Maven | 3.9.11 |
| Node.js / npm | 22.12.0 / 10.9.0 |
| Python | 3.13.3 |
| Docker Desktop / Engine | 4.97.0 / 29.9.2 (WSL 2) |

## One-time application setup

```powershell
cd D:\Final_year_project
if (-not (Test-Path .env)) { Copy-Item .env.example .env }

cd D:\Final_year_project\backend
mvn -q -DskipTests compile

cd D:\Final_year_project\frontend
npm install

cd D:\Final_year_project\demo-services
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements-dev.txt

cd D:\Final_year_project\ml-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python scripts\prepare_dataset.py
python scripts\train_models.py
```

If PowerShell blocks virtual-environment activation, run `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` in that terminal.

## Start the full local Compose application

### 1. Start all Compose services

Ensure Docker Desktop is running. Compose binds its published ports to localhost; PostgreSQL uses port 5433 to avoid the common local PostgreSQL 5432 conflict.

```powershell
cd D:\Final_year_project
docker compose -f infra\docker-compose.yml config --quiet
docker compose -f infra\docker-compose.yml up -d --build
docker compose -f infra\docker-compose.yml ps
```

The traffic generator begins calling `checkout-api` every two seconds. Its requests and telemetry are synthetic demo data. The ML image trains and evaluates the models from a deterministic synthetic dataset during image build.
Compose services use `restart: unless-stopped` so they recover when the Docker engine restarts. `docker compose down` still stops and removes the containers.

The full Compose application includes PostgreSQL, Kafka, Jaeger, Loki, Prometheus, the OTel Collector, three demo services, the ML API, Spring Boot, and the React production build. The UI is available at <http://localhost:5173>. Backend health is at <http://localhost:8080/actuator/health>. Compose is a local demonstration setup: default credentials are insecure, Jaeger uses in-memory storage, and `.env` is not production secret management.

### 2. Optional host-run development servers

```powershell
cd D:\Final_year_project\backend
$env:AEXA_PROFILE = "local"
$env:PROMETHEUS_URL = "http://localhost:9090"
$env:ML_SERVICE_URL = "http://localhost:8090"
mvn spring-boot:run
```

This keeps the backend on its existing H2 local profile. To use Compose PostgreSQL instead, set `$env:AEXA_PROFILE = "postgres"` and `$env:POSTGRES_PORT = "5433"`; database credentials come from the Compose environment.

### 3. Start React (new terminal)

```powershell
cd D:\Final_year_project\frontend
npm run dev
```

Open <http://localhost:5173> for the public platform overview, then choose **Sign in** to enter the authenticated workspace using the local development user `admin` / `admin-change-me`. Protected service and telemetry routes continue to require authentication.

The workspace navigation includes Overview, Services, Metrics · logs · traces, anomaly and prediction analysis, root-cause investigation, incidents, adaptive telemetry, Reports & evaluation, and System settings. The reports page points to repository evaluation artifacts; it does not fetch or invent results. System settings shows backend health and declared capabilities, but user-managed model and integration configuration is not exposed by the current API.

### 4. Start the ML service locally (optional when Compose ML is running)

```powershell
cd D:\Final_year_project\ml-service
.\.venv\Scripts\Activate.ps1
uvicorn app.main:app --host 127.0.0.1 --port 8090
```

Local inference requires model artifacts; create them with `python scripts\train_models.py`. Compose publishes the trained FastAPI service on port 8090.

## Health and data checks

```powershell
Invoke-RestMethod http://localhost:8081/health
Invoke-RestMethod http://localhost:8082/health
Invoke-RestMethod http://localhost:8083/health
Invoke-RestMethod http://localhost:9090/-/healthy
Invoke-RestMethod http://localhost:3100/ready
Invoke-RestMethod http://localhost:13133/
Invoke-RestMethod http://localhost:16686/api/services
Invoke-RestMethod http://localhost:8090/health
```

Backend health and documented endpoints:

- <http://localhost:8080/api/v1/health> — API and database
- <http://localhost:8080/actuator/health> — Spring health
- <http://localhost:8080/swagger-ui.html> — OpenAPI UI
- <http://localhost:16686> — Jaeger traces
- <http://localhost:9090> — Prometheus
- <http://localhost:3100> — Loki
- <http://localhost:8090/model/metadata> — trained model metadata and evaluation
- `POST http://localhost:8080/api/v1/ai/anomalies/detect` — anomaly detection (authenticated)
- `POST http://localhost:8080/api/v1/ai/forecasts/degradation` — five-minute degradation forecast (authenticated)
- `POST http://localhost:8080/api/v1/ai/root-causes/rank` — evidence-ranked RCA candidates (authenticated)

Check the authenticated telemetry API:

```powershell
$login = Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/auth/login `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"admin-change-me"}'
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-RestMethod -Uri http://localhost:8080/api/v1/telemetry/overview -Headers $headers
```

The metrics API returns null KPI values when Prometheus is reachable but no requests have been sampled yet. It returns HTTP 503 when Prometheus cannot be reached.

## Adaptive sampling controls (Phase 4)

Rebuild the demo-service images after changing their sampler code. The backend starts with adaptive changes disabled, full sampling as the safe default, and actuator readiness reported as unavailable until sampler state is successfully read.

Open **Adaptive telemetry** in the React app as `admin` to inspect sampler rates, risk bands, telemetry freshness, cooldown/change limits, and audit history. The page provides an administrator-only enable/disable switch, one-shot evaluation, and rollback to full sampling. Non-admin users have a read-only view.

PowerShell API example:

```powershell
$login = Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/auth/login `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"admin-change-me"}'
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-RestMethod -Uri http://localhost:8080/api/v1/adaptive/state -Headers $headers
Invoke-RestMethod -Method Put `
  -Uri http://localhost:8080/api/v1/adaptive/enabled `
  -Headers $headers -ContentType "application/json" -Body '{"enabled":true}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/adaptive/evaluate -Headers $headers
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/adaptive/rollback -Headers $headers
```

Rollback also disables automatic changes and requests 100% trace sampling. If an actuator is unreachable, the API/UI reports it and does not claim that rollback succeeded for that service. A service restart resets its sampler to 100%, a safe default. The controller adapts demo SDK trace sampling only; Prometheus scraping stays fixed at 10 seconds.

Reproduce the synthetic fixed-versus-adaptive sampling replay and regenerate its JSON report:

```powershell
cd D:\Final_year_project
python scripts\test_adaptive_sampling_replay.py
python scripts\adaptive_sampling_replay.py
```

`docs\phase-4-evaluation.json` reports the deterministic synthetic sampled-span proxy, incident recall, detection delay, and false-positive rate. This is not a hardware benchmark or production-data result: CPU, memory, bandwidth, and storage overhead are not measured.

## Controlled fault demonstration

Only use the unauthenticated fault endpoint on the localhost-bound demo services. For example, inject latency in inventory; requests through checkout will exercise the full dependency chain:

```powershell
Invoke-RestMethod -Method Put -Uri http://localhost:8082/demo/fault `
  -ContentType "application/json" -Body '{"mode":"latency"}'
```

Switch to errors or restore normal behavior:

```powershell
Invoke-RestMethod -Method Put -Uri http://localhost:8082/demo/fault `
  -ContentType "application/json" -Body '{"mode":"error"}'
Invoke-RestMethod -Method Put -Uri http://localhost:8082/demo/fault `
  -ContentType "application/json" -Body '{"mode":"none"}'
```

## Phase 5 end-to-end demonstration

With the full Compose stack healthy, run the repeatable local scenario driver from the repository root. It exercises normal requests, a latency injection, a downstream dependency failure, a bounded 20 ms CPU workload per checkout request, an error burst, recovery, a capped concurrent request probe, and authenticated metrics/logs/traces plus analysis APIs. The script accepts loopback endpoints only and always attempts to restore every service fault mode to `none`.

```powershell
cd D:\Final_year_project
.\scripts\phase5_demo.ps1 | Tee-Object -FilePath docs\phase-5-demo-results.json
```

The generated JSON records observed HTTP statuses/latencies, bounded-probe throughput, instantaneous Docker resource snapshots, analysis persistence, and the RCA candidate count. It explicitly does **not** treat the fixed CPU workload as host-wide saturation or Docker snapshots as measured peak overhead. A failed check exits non-zero; inspect the `failure` field and reset state if Docker was stopped during the run.

The script requires at least six real Prometheus samples, executes anomaly and forecast runs, and verifies a forecast record in PostgreSQL history. If there are insufficient observations, allow the traffic generator to run for at least six minutes and retry. For model evaluation metrics use the reproducible, synthetic-data report in `docs\phase-3-evaluation.md`. Do not merge synthetic evaluation metrics with results from this local demonstration.

## Tests

```powershell
cd D:\Final_year_project\backend
mvn test

cd D:\Final_year_project\frontend
npm test
npm run build

cd D:\Final_year_project\demo-services
.\.venv\Scripts\Activate.ps1
pytest

cd D:\Final_year_project\ml-service
.\.venv\Scripts\Activate.ps1
pytest
```

## Shutdown

For the Compose application:

```powershell
cd D:\Final_year_project
docker compose -f infra\docker-compose.yml down
```

This retains named PostgreSQL, Loki, and Prometheus volumes. Do not add `-v` unless you intentionally want to delete their persisted data. Host-run development servers stop with `Ctrl+C`.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Docker command unavailable | Start Docker Desktop using the Linux container engine; verify with `docker version` before starting Compose. |
| `docker compose config` fails | Check YAML and `.env` values; run the command from the repository root with the given `-f` path. |
| Compose reports a port conflict | Check 5433, 8081–8083, 8090, 9090, 3100, 4317–4318, 14317–14318, 16686, and 29092. Stop the conflicting process or change the published mapping. |
| Prometheus has no series | Wait for traffic generator and a scrape interval; check collector/traffic logs with `docker compose -f infra\docker-compose.yml logs --tail=100 otel-collector traffic-generator`. |
| Telemetry API returns 503 | Confirm Prometheus is healthy and `PROMETHEUS_URL` targets `localhost:9090` from the host-running backend. |
| Jaeger has no traces / Loki has no logs | Check collector exporter errors and service resource config; data is generated only after requests. |
| `401` on telemetry API | Log in and send the JWT as `Authorization: Bearer <token>`. |
| Local H2 database down | Read the backend log; close other processes using `backend\data`. |
| Frontend cannot reach backend | Confirm backend is listening on 8080 and Vite is on 5173. |
| Compose PostgreSQL fails to start | Check whether port 5433 is available and inspect `docker compose ... logs postgres`. |
| ML health returns 503 | Run `python scripts\train_models.py` locally or rebuild with `docker compose -f infra\docker-compose.yml up -d --build ml-service`. |
| Spring AI endpoint returns 503 | Check `ML_SERVICE_URL`, ML model health, and `ML_SERVICE_TIMEOUT_SECONDS`; Spring never substitutes synthetic predictions. |
| Adaptive state reports actuator unavailable | Check demo-service health on ports 8081–8083 and inspect sampler endpoint responses at `/internal/telemetry/sampling`; the controller remains fail-safe and reports unsuccessful actuation. |
| Adaptive controller stays at 100% sampling | Confirm Prometheus has fresh metric samples, ML health is 200, controller is enabled, and cooldown/change limits have not deferred a reduction. |

Useful diagnostics:

```powershell
docker compose -f infra\docker-compose.yml ps
docker compose -f infra\docker-compose.yml logs --tail=100
```
