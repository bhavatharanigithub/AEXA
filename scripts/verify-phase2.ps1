$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

docker compose -f infra\docker-compose.yml config --quiet

$expectedServices = @("checkout-api", "inventory-api", "pricing-api")
foreach ($port in @(8081, 8082, 8083)) {
  $health = Invoke-RestMethod "http://localhost:$port/health" -TimeoutSec 5
  if ($health.status -ne "UP") {
    throw "Demo service on port $port is not healthy"
  }
}

Invoke-RestMethod http://localhost:9090/-/healthy -TimeoutSec 5 | Out-Null
Invoke-RestMethod http://localhost:3100/ready -TimeoutSec 5 | Out-Null
Invoke-RestMethod http://localhost:13133/ -TimeoutSec 5 | Out-Null

$jaeger = Invoke-RestMethod http://localhost:16686/api/services -TimeoutSec 5
foreach ($service in $expectedServices) {
  if ($jaeger.data -notcontains $service) {
    throw "Jaeger has not received traces for $service. Wait for demo traffic and retry."
  }
}

$login = Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/auth/login `
  -ContentType "application/json" `
  -Body '{"username":"admin","password":"admin-change-me"}' `
  -TimeoutSec 10
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$overview = Invoke-RestMethod `
  -Uri http://localhost:8080/api/v1/telemetry/overview `
  -Headers $headers `
  -TimeoutSec 10
if (-not $overview.available -or -not $overview.hasData) {
  throw "Prometheus is reachable but has not returned demo telemetry samples"
}
foreach ($service in $expectedServices) {
  if ($overview.services.serviceName -notcontains $service) {
    throw "Prometheus telemetry is missing $service"
  }
}

Write-Host "Phase 2 telemetry smoke checks passed."
