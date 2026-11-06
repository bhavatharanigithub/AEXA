# REST API contracts (`/api/v1`)

Base URL (local): `http://localhost:8080/api/v1`  
Content-Type: `application/json`  
Auth: `Authorization: Bearer <jwt>` except health and login.  
Correlation: client may send `X-Correlation-Id`; otherwise the server generates one and always returns it.

## Error body (all 4xx/5xx)

```json
{
  "type": "https://aexa.dev/problems/validation",
  "title": "Validation failed",
  "status": 400,
  "detail": "username must not be blank",
  "instance": "/api/v1/auth/login",
  "correlationId": "7c1e0b2a-3d44-4c1e-9a0a-0c5f2d1e8b77"
}
```

| HTTP | When |
| --- | --- |
| 400 | Validation |
| 401 | Missing/invalid token |
| 403 | Authenticated but wrong role |
| 404 | Unknown id |
| 409 | Unique constraint |
| 500 | Unexpected |
| 503 | Required telemetry store unavailable or returned an invalid response |

## `GET /api/v1/health` (public)

**Response 200**

```json
{
  "status": "UP",
  "service": "aexa-backend",
  "version": "0.1.0",
  "profile": "local",
  "database": "UP",
  "timestamp": "2026-10-26T12:30:00.123Z"
}
```

If the database ping fails: HTTP 503, `"status": "DOWN"`, `"database": "DOWN"`.

Spring Actuator `GET /actuator/health` remains enabled for orchestrators.

## `POST /api/v1/auth/login` (public)

**Request**

```json
{ "username": "admin", "password": "admin-change-me" }
```

**Response 200**

```json
{
  "accessToken": "<jwt>",
  "tokenType": "Bearer",
  "expiresInSeconds": 28800,
  "username": "admin",
  "role": "ADMIN"
}
```

**401** invalid credentials.

## `GET /api/v1/services` (VIEWER+)

**Response 200**

```json
{
  "items": [
    {
      "id": "11111111-1111-1111-1111-111111111111",
      "name": "checkout-api",
      "namespace": "demo",
      "environment": "demo",
      "version": "0.2.0",
      "displayName": "Checkout API",
      "description": "Instrumented demo workload. Traffic is synthetic.",
      "owner": "platform",
      "createdAt": "2026-10-26T12:00:00.000Z",
      "updatedAt": "2026-10-26T12:00:00.000Z"
    }
  ]
}
```

Seeded catalog rows are labelled in `description`. An empty array is valid.

## `GET /api/v1/services/{id}` (VIEWER+)

200 entity or 404.

## `POST /api/v1/services` (ADMIN)

**Request**

```json
{
  "name": "inventory-api",
  "namespace": "demo",
  "environment": "local",
  "version": "0.1.0",
  "displayName": "Inventory API",
  "description": "Demo catalog entry",
  "owner": "platform"
}
```

**201** created entity. **409** if `(name, environment)` exists.

## `GET /api/v1/incidents` (VIEWER+)

```json
{
  "items": []
}
```

Phase 2 starts with **no persisted incidents**. Do not display invented outages in the UI.

## `GET /api/v1/incidents/{id}` (VIEWER+)

404 until incidents exist.

## `GET /api/v1/platform/capabilities` (VIEWER+)

Declares what is implemented vs planned so the UI never pretends a module is live.

```json
{
  "phase": 5,
  "telemetryIngestion": true,
  "anomalyDetection": true,
  "forecasting": true,
  "rootCauseAnalysis": true,
  "explainability": true,
  "adaptiveControlActuation": false,
  "incidentPersistence": true,
  "serviceCatalog": true
}
```

`telemetryIngestion` means the telemetry pipeline/API is implemented; it does not mean the stores are currently reachable. Query availability is reported by the telemetry endpoint. AI capabilities indicate the analysis endpoints are implemented, not that the models are production-validated.

## Telemetry evidence endpoints (VIEWER+)

All query endpoints are authenticated and time/size bounded:

- `GET /api/v1/telemetry/metrics?serviceName=checkout-api&lookbackMinutes=60` returns timestamped observations from Prometheus. Lookback is 5–60 minutes.
- `GET /api/v1/telemetry/logs?serviceName=checkout-api&lookbackMinutes=60&limit=50` returns timestamp, service, severity, message, and optional trace ID from Loki. Lookback is 1–1440 minutes; limit is 1–200.
- `GET /api/v1/telemetry/traces?serviceName=checkout-api&lookbackMinutes=60&limit=20` returns timestamp, trace/span IDs, service/parent, operation, duration, and tags from Jaeger. Lookback is 1–1440 minutes; limit is 1–200. Jaeger results include spans from matching traces.

The metric row uses the AI request contract's snake-case fields. Logs, spans, and history records use Spring's camel-case DTO field names. Empty arrays mean the query succeeded but had no matching evidence; an unavailable store returns HTTP 503 rather than a fabricated empty-success response.

## Analysis history and execution

`POST /api/v1/analysis/anomalies/run?serviceName=checkout-api&lookbackMinutes=60` and `POST /api/v1/analysis/forecasts/run?serviceName=checkout-api&lookbackMinutes=60` are restricted to `ANALYST` and `ADMIN`. The observation window must contain at least six real Prometheus samples. The backend calls FastAPI with a bounded timeout and stores returned anomalous samples/forecast output plus explanation payloads in PostgreSQL. Each returned response includes a persistence indicator. Runs with insufficient real data return `422`; an unavailable ML service returns `503`.

History endpoints are readable by any authenticated role:

- `GET /api/v1/analysis/anomalies?serviceName=checkout-api&limit=100`
- `GET /api/v1/analysis/forecasts?serviceName=checkout-api&limit=100`

History `limit` is 1–500. Omitting `serviceName` returns recent results across services. Empty history is `200 []`. Persisted forecast rows retain the returned features and threshold; the full explanation payload is kept in the linked explanation record.

## Direct AI and root-cause contracts

Direct inference `POST /api/v1/ai/anomalies/detect`, `/api/v1/ai/forecasts/degradation`, and `/api/v1/ai/root-causes/rank` require an `ANALYST` or `ADMIN` token. Timestamped observation windows require 6–5000 rows with non-negative values, error ratios in `[0,1]`, consistent service names, and no fabricated future samples. The ML FastAPI service validates its own request schemas as well.

Root-cause ranking is invoked by `POST /api/v1/ai/root-causes/rank` after the dashboard queries the configured Prometheus, Loki, and Jaeger stores. Request evidence must be no later than `incident_timestamp`, and `service_metadata` must cover every service in the evidence. Example shape:

```json
{
  "incident_timestamp": "2026-10-26T12:30:00Z",
  "observations": [{
    "timestamp": "2026-10-26T12:29:00Z",
    "service_name": "checkout-api",
    "request_rate": 2.5,
    "error_rate": 0.2,
    "p95_latency_seconds": 1.2
  }],
  "trace_edges": [{
    "timestamp": "2026-10-26T12:29:00Z",
    "parent_service": "checkout-api",
    "child_service": "inventory-api",
    "trace_id": "example-trace-id",
    "call_count": 1
  }],
  "logs": [{
    "timestamp": "2026-10-26T12:29:00Z",
    "service_name": "checkout-api",
    "severity": "ERROR",
    "message": "Downstream request failed",
    "trace_id": "example-trace-id"
  }],
  "service_metadata": [{
    "service_name": "checkout-api",
    "namespace": "demo",
    "environment": "local",
    "version": "0.1.0"
  }, {
    "service_name": "inventory-api",
    "namespace": "demo",
    "environment": "local",
    "version": "0.1.0"
  }]
}
```

The response includes ranked candidates, evidence, limitations, and `causality_confirmed: false`. Ranking scores are not confidence probabilities. The API does not persist RCA investigations as incidents.

The Phase 5 analysis views and storage paths are implemented, but live model-history persistence and end-to-end scenarios remain subject to the verification status in [`phased-checklist.md`](./phased-checklist.md). Model metrics in [`phase-3-evaluation.md`](./phase-3-evaluation.md) are held-out synthetic results only.

## `GET /api/v1/telemetry/overview` (VIEWER+)

The endpoint is read-only and queries Prometheus using five-minute rates. It returns measured demo workload telemetry; it does not return anomaly, forecast, incident, or causal-analysis results.

**Response 200 with samples** (illustrative contract shape; numeric values are not evaluation results):

```json
{
  "available": true,
  "hasData": true,
  "source": "prometheus",
  "queriedAt": "2026-10-26T12:30:00Z",
  "requestsPerSecond": 2.5,
  "errorRate": 0.02,
  "p95LatencySeconds": 0.12,
  "services": [
    {
      "serviceName": "checkout-api",
      "requestsPerSecond": 1.5,
      "errorRate": 0.03,
      "p95LatencySeconds": 0.15
    }
  ]
}
```

If Prometheus is reachable but has no samples, the response is still `200`, with `available: true`, `hasData: false`, null overall KPIs, and an empty `services` array. The dashboard renders an explicit waiting state rather than zero-valued sample data.

If Prometheus is unreachable or returns invalid series, the API returns HTTP `503` and the standard problem response with title `Telemetry unavailable`. The backend never fills the response with synthetic values.

Expected metric names emitted by the demo-service instrumentation:

- `aexa_service_requests_total` with labels `service_name` and `status_code`
- `aexa_request_duration_seconds_bucket` histogram with `service_name`

Set `PROMETHEUS_URL` to `http://localhost:9090` for a host-run backend or `http://prometheus:9090` for a backend running in the Compose network.

## Adaptive sampling control (authenticated)

Adaptive control adjusts runtime OpenTelemetry trace sampling on the three demo services only. Prometheus scrape intervals remain fixed. The controller starts disabled, requests full sampling by default, and does not report actuator readiness until it has successfully contacted the samplers.

All endpoints below require a JWT. State and audit are `VIEWER+`; enable/disable, evaluate, and rollback require `ADMIN`.

- `GET /api/v1/adaptive/state` returns enabled state, sampler readiness, current rates and risk bands, telemetry status, freshness threshold, cooldown and automatic-change limits, and the latest 20 audit events.
- `GET /api/v1/adaptive/audit?limit=20` returns 1–100 audit events.
- `PUT /api/v1/adaptive/enabled` accepts `{"enabled": true}` or `{"enabled": false}`. Enabling triggers an evaluation. Disabling requests full trace sampling.
- `POST /api/v1/adaptive/evaluate` runs one evaluation when enabled.
- `POST /api/v1/adaptive/rollback` disables adaptive changes and requests a full-sampling rollback.

Default state response shape:

```json
{
  "enabled": false,
  "actuationAvailable": false,
  "controllerMode": "DISABLED",
  "lastSignalStatus": "NOT_EVALUATED",
  "lastError": null,
  "traceSampleRates": {
    "checkout-api": 1.0,
    "inventory-api": 1.0,
    "pricing-api": 1.0
  },
  "riskLevels": {
    "checkout-api": "UNKNOWN",
    "inventory-api": "UNKNOWN",
    "pricing-api": "UNKNOWN"
  },
  "lastEvaluatedAt": null,
  "lastChangedAt": null,
  "cooldownSeconds": 60,
  "changesInLastHour": 0,
  "maxChangesPerHour": 12,
  "minimumTraceSampleRate": 0.05,
  "maximumTraceSampleRate": 1.0,
  "staleAfterSeconds": 45,
  "recentAudit": []
}
```

Rates are clamped to 0.05–1.0. The deterministic policy targets 0.10 for low risk, 0.50 for elevated risk, and 1.0 for critical risk. Escalation and safe full-sampling fallbacks are immediate; they are never blocked by reduction cooldowns. Reduction requires three safer evaluations, cooldown, and the configured automatic-change limit. Stale or unavailable telemetry, invalid model output, and model outages request 1.0 sampling. Actuator failure is surfaced in the state and audit rather than being represented as success.

Example enable request:

```http
PUT /api/v1/adaptive/enabled
Authorization: Bearer <jwt>
Content-Type: application/json

{"enabled": true}
```

The controller calls `/internal/telemetry/sampling` on the demo services. Those actuator ports are bound to localhost in the provided Compose configuration and the endpoint is intended for this demo trust boundary, not direct production exposure. Production deployment must authenticate/isolate the actuator interface and measure resource impact before making overhead claims.

## AI analysis (Spring Boot, authenticated)

Spring accepts ISO-8601 timezone-aware timestamps and strict, bounded metric/trace/log arrays. Metric window requests require at least six observations and at most 5,000; each observation needs at least one measured metric, while unavailable individual features may be null or omitted. The model uses past-only forward fills for up to two minutes and training-fitted medians for remaining feature gaps, and responses distinguish observed values from imputed features. RCA accepts at most 2,000 trace edges, 2,000 logs, 500 service records, and 200 unique services. All AI routes require `VIEWER+` JWT authorization. Spring calls FastAPI with a bounded timeout and returns an explicit 503 if the model is unavailable.

### `POST /api/v1/ai/anomalies/detect`

`/api/v1/ai/forecasts/degradation` uses the same request shape.

```json
{
  "service_name": "checkout-api",
  "observations": [
    {
      "timestamp": "2026-10-26T12:00:00Z",
      "service_name": "checkout-api",
      "request_rate": 0.8,
      "error_rate": 0.005,
      "p95_latency_seconds": 0.2
    }
  ]
}
```

The abbreviated sample above shows one row for readability; submit at least six observations per service.

Anomaly response includes timestamped per-window results, feature values, the Isolation Forest decision output/threshold, anomaly score, detection method, and LIME contributions for an anomalous sample. The anomaly score is **not a probability**. Forecast response reports a model probability for p95 latency `>= 1.0` second in the next five one-minute samples, with a fixed 0.5 operational threshold and LIME feature contributions. The probability is not a calibrated guarantee.

### `POST /api/v1/ai/root-causes/rank`

Request contains a timezone-aware `incident_timestamp`, historical `observations`, `service_metadata` (one item for every service referenced), `trace_edges` (`parent_service`, `child_service`, `trace_id`, `call_count`, `timestamp`), and correlated `logs` (`service_name`, `severity`, `message`, optional `trace_id`, `timestamp`). Trace and log evidence must be from the observed telemetry sources; the endpoint does not invent missing edges or log entries. It compares metrics in a five-minute event window against at least three prior samples.

The response returns ranked candidates with the supporting metric deviations, trace edges, and log records. `ranking_score` is only a deterministic evidence-ranking score (0.55 normalized metric deviation + 0.25 inverse trace-path distance + 0.10 bounded error-log count + 0.10 observed temporal precedence), **not** confidence or probability; `causality_confirmed` is always false.

### ML service direct endpoints (port 8090)

- `GET /health` returns HTTP 200 only when models are loaded; otherwise HTTP 503.
- `GET /model/metadata` returns model version, data hash, and saved evaluation.
- FastAPI analysis routes: `POST /api/v1/anomalies/detect`, `POST /api/v1/forecasts/degradation`, and `POST /api/v1/root-causes/rank`.

FastAPI rejects unknown fields, non-finite metric values, naive timestamps, empty observations, mismatched service names, and oversized request arrays with HTTP 422. ML results and saved metrics are from deterministic synthetic demo data and a chronological holdout; they are not production performance claims.

The currently generated metrics are saved in `ml-service/artifacts/evaluation.json`. Recreate the dataset and train deterministically with:

```powershell
cd D:\Final_year_project\ml-service
.\.venv\Scripts\Activate.ps1
python scripts\prepare_dataset.py
python scripts\train_models.py
pytest
```

Sample health response:

```json
{
  "status": "UP",
  "service": "aexa-ml",
  "modelStatus": "ready",
  "modelVersion": "aexa-phase3-2026.10.26",
  "phase": 3
}
```

## Frontend routes (not backend)

| Path | Page |
| --- | --- |
| `/` | Overview |
| `/services` | Service explorer |
| `/telemetry` | Placeholder for detailed metrics/logs/traces; overview reads Prometheus |
| `/anomalies` | Anomaly timeline (empty) |
| `/predictions` | Predictions (empty) |
| `/investigate` | RCA workspace (empty) |
| `/adaptive` | Adaptive settings (read-only copy) |
| `/incidents` | Incident history |
| `/settings` | About / roles |
| `/login` | Sign in |
