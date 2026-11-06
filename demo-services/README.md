# Demonstration microservices

Phase 2 uses one FastAPI image as three local-only services:

`checkout-api -> inventory-api -> pricing-api`

All three export OpenTelemetry traces, metrics, and logs using OTLP. Resource attributes include `service.name`, `service.namespace`, `service.version`, `service.instance.id`, and `deployment.environment`. W3C trace context is propagated on downstream HTTP calls. The traffic generator creates clearly synthetic demonstration load; it is not production telemetry.

The `/demo/fault` endpoint is intentionally unauthenticated for local demonstrations and must never be exposed beyond localhost. Set a controlled fault on any demo service:

```powershell
Invoke-RestMethod -Method Put -Uri http://localhost:8082/demo/fault `
  -ContentType "application/json" -Body '{"mode":"latency"}'
Invoke-RestMethod -Method Put -Uri http://localhost:8082/demo/fault `
  -ContentType "application/json" -Body '{"mode":"error"}'
Invoke-RestMethod -Method Put -Uri http://localhost:8082/demo/fault `
  -ContentType "application/json" -Body '{"mode":"none"}'
```

Allowed modes are `none`, `latency` (adds one second), and `error` (returns HTTP 503). Fault state is in-memory and resets when that service container restarts.
