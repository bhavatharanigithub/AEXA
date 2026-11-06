# Engineering conventions

## Configuration

- Environment variables override Spring/FastAPI/Vite settings.
- Secrets never live in source files committed to git. `.env.example` documents keys only.
- Spring profiles: `local` (H2), `postgres` (PostgreSQL), `test` (surefire).
- Spring telemetry query target: `PROMETHEUS_URL`; local host default is `http://localhost:9090`.
- Demo workload settings are supplied through environment variables, including `SERVICE_NAME`, `DEPLOYMENT_ENVIRONMENT`, and `OTEL_EXPORTER_OTLP_ENDPOINT`.

## Logging

- Backend: Logback JSON-friendly pattern with `%X{correlationId}`.
- Do not log passwords, tokens, or raw authorization headers.
- Prefer `info` for requests completed, `warn` for handled business failures, `error` for unexpected.
- Demo-service logs, spans, and metrics use the same OTel resource identity. Do not log secrets or customer data.

## Error handling

- Bean Validation on request bodies.
- `GlobalExceptionHandler` maps to the problem JSON in `api-contracts.md`.
- Controllers do not catch generic `Exception`.

## Testing

| Layer | Tool | What |
| --- | --- | --- |
| Backend unit/API | JUnit 5 + MockMvc / Spring Boot Test | Health, auth, catalog, error shape |
| Frontend | Vitest + Testing Library | Layout, empty states, login form |
| ML | Pytest + httpx | `/health` |
| Demo services | Pytest + FastAPI TestClient | health and controlled fault behavior; exporters disabled in unit tests so no Collector is required |

The Spring telemetry adapter is tested using Prometheus-compatible HTTP responses and explicit unavailable/empty cases. The React overview is tested with real response shapes and an empty-state response. A container-level smoke test still requires Docker.

No test may assert fabricated detector accuracy.

## Naming

- Java packages: `com.aexa.platform.*`
- REST resources: plural nouns, kebab-case paths only if needed; we use lowercase singular resource groups (`services`, `incidents`).
- Database: snake_case tables.

## Frontend theme (mandatory)

| Token | Value | Use |
| --- | --- | --- |
| `--bg` | `#e7f4f1` | App canvas |
| `--sidebar` | `#f3faf8` | Left nav |
| `--teal` | `#0f3d3a` | Active nav, primary buttons |
| `--text` | `#143230` | Headings |
| `--muted` | `#6b7f7c` | Secondary text |
| `--card` | `#ffffff` | Panels |
| `--radius` | `24px` | Cards |
| Pastel lime / mint / rose / lilac | KPI wells | Visual only; never fill with fake live numbers |

Typography: Inter. Charts (later) use the same pastel fills.

## Phase 2 telemetry rules

- All workload traffic is synthetic and visibly labelled as demo traffic.
- Use `service.name`, `service.namespace`, `service.version`, `service.instance.id`, and `deployment.environment`; propagate W3C trace context to downstream HTTP calls.
- Export with OTLP; do not add direct workload-to-store connections.
- Prometheus values in the UI must originate from backend PromQL responses. Empty samples render as empty state; store errors render as an error.
- Demo fault control is unauthenticated only because every mapped demo port is bound to `127.0.0.1`. Never expose it on a shared or public interface.
- Backend telemetry access is read-only. No anomaly, forecast, RCA, or incident result is inferred from threshold metrics in this phase.
