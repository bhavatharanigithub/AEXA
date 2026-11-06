param(
    [string]$BackendUrl = "http://localhost:8080",
    [string]$CheckoutUrl = "http://localhost:8081",
    [string]$InventoryUrl = "http://localhost:8082",
    [string]$PricingUrl = "http://localhost:8083",
    [string]$Username = "admin",
    [string]$Password = "admin-change-me",
    [int]$ConcurrentProbeRequests = 2,
    [bool]$RestartServicesForPersistenceCheck = $true,
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\docs\phase-5-results\phase5-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')).json")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
if ($ConcurrentProbeRequests -lt 1 -or $ConcurrentProbeRequests -gt 16) {
    throw "ConcurrentProbeRequests must be between 1 and 16 for this bounded local demo."
}

$localEndpoints = @($BackendUrl, $CheckoutUrl, $InventoryUrl, $PricingUrl)
foreach ($endpoint in $localEndpoints) {
    $parsedEndpoint = [Uri]$endpoint
    if (-not $parsedEndpoint.IsLoopback) {
        throw "The Phase 5 demo driver only sends credentials or fault controls to loopback endpoints: $endpoint"
    }
}

$client = New-Object System.Net.Http.HttpClient
$measurements = New-Object System.Collections.Generic.List[object]
$parallelTimer = [System.Diagnostics.Stopwatch]::new()
$parallelStatuses = @()
$rcaPayload = $null
$resourceAfter = @()
$persistenceCheck = @{
    performed = $false
    status = "NOT_RUN"
}
$adaptiveControlTouched = $false

function Invoke-MeasuredRequest {
    param(
        [string]$Method,
        [string]$Url,
        [string]$Body
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method),
        $Url
    )
    if (-not [string]::IsNullOrEmpty($Body)) {
        $request.Content = [System.Net.Http.StringContent]::new(
            $Body,
            [System.Text.Encoding]::UTF8,
            "application/json"
        )
    }
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $timer.Stop()
        return [pscustomobject]@{
            status = [int]$response.StatusCode
            latency_ms = [math]::Round($timer.Elapsed.TotalMilliseconds, 2)
            body = $responseBody
        }
    } finally {
        $request.Dispose()
        if ($null -ne $response) {
            $response.Dispose()
        }
    }
}

function Add-Measurement {
    param([string]$Name, [string]$Method, [string]$Url, [string]$Body)
    $result = Invoke-MeasuredRequest -Method $Method -Url $Url -Body $Body
    $detail = $null
    if ($result.status -ge 400) {
        try {
            $problem = $result.body | ConvertFrom-Json
            $detail = "$($problem.title): $($problem.detail)"
        } catch {
            $detail = $result.body
        }
        if ($detail.Length -gt 500) {
            $detail = $detail.Substring(0, 500)
        }
    }
    $measurements.Add([pscustomobject]@{
        scenario = $Name
        method = $Method
        url = $Url
        status = $result.status
        latency_ms = $result.latency_ms
        detail = $detail
    })
    return $result
}

function Set-Fault {
    param([string]$ServiceUrl, [string]$Mode)
    $body = @{ mode = $Mode } | ConvertTo-Json -Compress
    $result = Invoke-MeasuredRequest -Method "PUT" -Url "$ServiceUrl/demo/fault" -Body $body
    if ($result.status -ne 200) {
        throw "Could not set $ServiceUrl fault mode to '$Mode' (HTTP $($result.status))."
    }
}

function Assert-Status {
    param([string]$Name, $Result, [int]$ExpectedStatus)
    if ($Result.status -ne $ExpectedStatus) {
        throw "$Name expected HTTP $ExpectedStatus but received HTTP $($Result.status)."
    }
}

function Convert-Body {
    param($Result)
    return $Result.body | ConvertFrom-Json
}

function Invoke-ComposeRestart {
    param([string]$Service)
    $composeFile = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\infra\docker-compose.yml"))
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & docker compose -f $composeFile restart $Service 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "Could not restart Compose service '$Service': $($output -join ' ')"
    }
}

function Wait-PostgresHealthy {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        $status = & docker inspect aexa-postgres --format '{{.State.Health.Status}}' 2>$null
        if ($LASTEXITCODE -eq 0 -and $status -eq "healthy") {
            return
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "PostgreSQL did not become healthy after restart."
}

function Wait-BackendHealthy {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        try {
            $health = Invoke-RestMethod -Uri "$($BackendUrl.TrimEnd('/'))/actuator/health" -TimeoutSec 5
            if ($health.status -eq "UP") {
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Spring backend did not become healthy after restart."
}

function Get-PostgresScalar {
    param([string]$Query)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $containerEnvironment = & docker inspect aexa-postgres --format '{{range .Config.Env}}{{println .}}{{end}}' 2>&1
        $inspectExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($inspectExitCode -ne 0) {
        throw "Could not read PostgreSQL container configuration: $($containerEnvironment -join ' ')"
    }
    $userSetting = $containerEnvironment | Where-Object { $_ -like "POSTGRES_USER=*" } | Select-Object -First 1
    $databaseSetting = $containerEnvironment | Where-Object { $_ -like "POSTGRES_DB=*" } | Select-Object -First 1
    if (-not $userSetting -or -not $databaseSetting) {
        throw "PostgreSQL container does not expose its configured database name and user."
    }
    $postgresUser = $userSetting.Substring("POSTGRES_USER=".Length)
    $postgresDatabase = $databaseSetting.Substring("POSTGRES_DB=".Length)
    try {
        $ErrorActionPreference = "Continue"
        $value = & docker exec aexa-postgres psql -U $postgresUser -d $postgresDatabase -Atc $Query 2>&1
        $queryExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($queryExitCode -ne 0) {
        throw "Could not verify PostgreSQL persistence: $($value -join ' ')"
    }
    return (($value | Select-Object -Last 1).ToString()).Trim()
}

function Get-ContainerResourceSnapshot {
    $stats = & docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' 2>$null
    if ($LASTEXITCODE -ne 0) {
        return @("Docker stats unavailable (Docker is not running or is not on PATH).")
    }
    return @($stats)
}

$apiBase = "$($BackendUrl.TrimEnd('/'))/api/v1"
$startedAt = [DateTime]::UtcNow
$resourceBefore = Get-ContainerResourceSnapshot
$scenarioFailure = $null
$apiObservations = @{}
$analysisResults = @{}

try {
    $loginBody = @{
        username = $Username
        password = $Password
    } | ConvertTo-Json -Compress
    $login = Invoke-MeasuredRequest -Method "POST" -Url "$apiBase/auth/login" -Body $loginBody
    Assert-Status -Name "login" -Result $login -ExpectedStatus 200
    $loginPayload = Convert-Body $login
    $client.DefaultRequestHeaders.Authorization =
        New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $loginPayload.accessToken)

    $normal = Add-Measurement -Name "normal" -Method "GET" -Url "$($CheckoutUrl.TrimEnd('/'))/work"
    Assert-Status -Name "normal checkout request" -Result $normal -ExpectedStatus 200

    Set-Fault -ServiceUrl $InventoryUrl -Mode "latency"
    $latency = Add-Measurement -Name "latency-spike" -Method "GET" -Url "$($CheckoutUrl.TrimEnd('/'))/work"
    Assert-Status -Name "latency-spike checkout request" -Result $latency -ExpectedStatus 200
    Set-Fault -ServiceUrl $InventoryUrl -Mode "none"

    Set-Fault -ServiceUrl $PricingUrl -Mode "error"
    $dependency = Add-Measurement -Name "dependency-failure" -Method "GET" -Url "$($CheckoutUrl.TrimEnd('/'))/work"
    Assert-Status -Name "dependency-failure checkout request" -Result $dependency -ExpectedStatus 502
    Set-Fault -ServiceUrl $PricingUrl -Mode "none"

    Set-Fault -ServiceUrl $CheckoutUrl -Mode "resource"
    $resourcePressure = Add-Measurement -Name "bounded-resource-pressure" -Method "GET" -Url "$($CheckoutUrl.TrimEnd('/'))/work"
    Assert-Status -Name "bounded resource-pressure request" -Result $resourcePressure -ExpectedStatus 200
    Set-Fault -ServiceUrl $CheckoutUrl -Mode "none"

    Set-Fault -ServiceUrl $CheckoutUrl -Mode "error"
    for ($index = 0; $index -lt 5; $index++) {
        $burst = Add-Measurement -Name "error-burst" -Method "GET" -Url "$($CheckoutUrl.TrimEnd('/'))/work"
        Assert-Status -Name "error-burst request" -Result $burst -ExpectedStatus 503
    }
    Set-Fault -ServiceUrl $CheckoutUrl -Mode "none"
    $recovery = Add-Measurement -Name "recovery" -Method "GET" -Url "$($CheckoutUrl.TrimEnd('/'))/work"
    Assert-Status -Name "recovery checkout request" -Result $recovery -ExpectedStatus 200

    $parallelTimer = [System.Diagnostics.Stopwatch]::StartNew()
    $parallelTasks = @()
    for ($index = 0; $index -lt $ConcurrentProbeRequests; $index++) {
        $parallelTasks += $client.GetAsync("$($CheckoutUrl.TrimEnd('/'))/work")
    }
    $parallelResponses = [System.Threading.Tasks.Task]::WhenAll($parallelTasks).GetAwaiter().GetResult()
    $parallelTimer.Stop()
    $parallelStatuses = @($parallelResponses | ForEach-Object { [int]$_.StatusCode })
    foreach ($parallelResponse in $parallelResponses) {
        $parallelResponse.Dispose()
    }
    if (@($parallelStatuses | Where-Object { $_ -ne 200 }).Count -gt 0) {
        throw "Bounded concurrent probe had non-200 results: $($parallelStatuses -join ',')."
    }

    foreach ($service in @("checkout-api", "inventory-api", "pricing-api")) {
        foreach ($evidenceType in @("metrics", "logs", "traces")) {
            $suffix = switch ($evidenceType) {
                "metrics" { "?serviceName=$service&lookbackMinutes=60" }
                "logs" { "?serviceName=$service&lookbackMinutes=60&limit=100" }
                "traces" { "?serviceName=$service&lookbackMinutes=60&limit=20" }
            }
            $evidence = Add-Measurement -Name "telemetry-$evidenceType" -Method "GET" -Url "$apiBase/telemetry/$evidenceType$suffix"
            Assert-Status -Name "$service telemetry $evidenceType" -Result $evidence -ExpectedStatus 200
            if ($service -eq "checkout-api") {
                $apiObservations[$evidenceType] = Convert-Body $evidence
            }
        }
    }

    foreach ($analysis in @("anomalies", "forecasts")) {
        $run = Add-Measurement -Name "analysis-$analysis" -Method "POST" -Url "$apiBase/analysis/$analysis/run?serviceName=checkout-api&lookbackMinutes=60"
        if ($run.status -ne 200) {
            throw "Analysis endpoint '$analysis' must return HTTP 200 after telemetry collection, received HTTP $($run.status)."
        }
        $analysisResults[$analysis] = Convert-Body $run
    }
    foreach ($historyType in @("anomalies", "forecasts")) {
        $history = Add-Measurement -Name "history-${historyType}" -Method "GET" -Url "$apiBase/analysis/${historyType}?serviceName=checkout-api&limit=10"
        Assert-Status -Name "$historyType history" -Result $history -ExpectedStatus 200
        $historyRows = Convert-Body $history
        $analysisResults["${historyType}-history-count"] = @($historyRows).Count
        $analysisResults["${historyType}-history-ids"] = @($historyRows | ForEach-Object { $_.id })
    }
    if (-not $analysisResults["forecasts-history-count"] -or -not $analysisResults["anomalies-history-count"]) {
        throw "Analysis API returned success but anomaly and forecast records were not both found in PostgreSQL history."
    }

    $adaptiveControlTouched = $true
    $adaptiveEnabled = Add-Measurement -Name "adaptive-enable" -Method "PUT" -Url "$apiBase/adaptive/enabled" -Body '{"enabled":true}'
    Assert-Status -Name "adaptive enable" -Result $adaptiveEnabled -ExpectedStatus 200
    $adaptiveDisabled = Add-Measurement -Name "adaptive-disable" -Method "PUT" -Url "$apiBase/adaptive/enabled" -Body '{"enabled":false}'
    Assert-Status -Name "adaptive disable and safe rollback" -Result $adaptiveDisabled -ExpectedStatus 200
    $adaptiveState = Convert-Body $adaptiveDisabled
    if ($adaptiveState.enabled) {
        throw "Adaptive control did not remain disabled after the persistence check."
    }
    $adaptiveAudit = Add-Measurement -Name "adaptive-audit" -Method "GET" -Url "$apiBase/adaptive/audit?limit=100"
    Assert-Status -Name "adaptive audit history" -Result $adaptiveAudit -ExpectedStatus 200
    $adaptiveAuditRows = Convert-Body $adaptiveAudit
    $adaptiveAuditCountBeforeRestart = @($adaptiveAuditRows).Count
    $adaptiveAuditIdsBeforeRestart = @($adaptiveAuditRows | ForEach-Object { $_.id })
    $controlStateRowsBeforeRestart = [int](Get-PostgresScalar -Query "SELECT COUNT(*) FROM telemetry_control_state")
    $controlStateSnapshotBeforeRestart = Get-PostgresScalar -Query "SELECT string_agg(s.name || '=' || to_char(state.trace_sample_rate, 'FM0.00') || ':' || state.source, ',' ORDER BY s.name) FROM telemetry_control_state state JOIN registered_service s ON s.id = state.service_id"
    if ($controlStateRowsBeforeRestart -lt 3) {
        throw "Expected persisted sampler state for all three demo services; found $controlStateRowsBeforeRestart rows."
    }

    if ($RestartServicesForPersistenceCheck) {
        $persistenceCheck.performed = $true
        Invoke-ComposeRestart -Service "postgres"
        Wait-PostgresHealthy
        Invoke-ComposeRestart -Service "backend"
        Wait-BackendHealthy

        $loginAfterRestart = Invoke-MeasuredRequest -Method "POST" -Url "$apiBase/auth/login" -Body $loginBody
        Assert-Status -Name "login after backend restart" -Result $loginAfterRestart -ExpectedStatus 200
        $loginPayload = Convert-Body $loginAfterRestart
        $client.DefaultRequestHeaders.Authorization =
            New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $loginPayload.accessToken)

        foreach ($historyType in @("anomalies", "forecasts")) {
            $historyAfterRestart = Add-Measurement -Name "history-${historyType}-after-restart" -Method "GET" -Url "$apiBase/analysis/${historyType}?serviceName=checkout-api&limit=10"
            Assert-Status -Name "$historyType history after PostgreSQL/backend restart" -Result $historyAfterRestart -ExpectedStatus 200
            $restoredRows = Convert-Body $historyAfterRestart
            foreach ($savedId in $analysisResults["${historyType}-history-ids"]) {
                if (-not (@($restoredRows | ForEach-Object { $_.id }) -contains $savedId)) {
                    throw "Persisted $historyType record $savedId was not available after the database/backend restart."
                }
            }
        }
        $auditAfterRestart = Add-Measurement -Name "adaptive-audit-after-restart" -Method "GET" -Url "$apiBase/adaptive/audit?limit=100"
        Assert-Status -Name "adaptive audit after restart" -Result $auditAfterRestart -ExpectedStatus 200
        $auditAfterRestartRows = Convert-Body $auditAfterRestart
        if (@($auditAfterRestartRows).Count -lt $adaptiveAuditCountBeforeRestart) {
            throw "Adaptive audit history shrank after PostgreSQL/backend restart."
        }
        $auditIdsAfterRestart = @($auditAfterRestartRows | ForEach-Object { $_.id })
        foreach ($auditId in $adaptiveAuditIdsBeforeRestart) {
            if (-not ($auditIdsAfterRestart -contains $auditId)) {
                throw "Adaptive audit record $auditId was not available after the database/backend restart."
            }
        }
        $controlStateRowsAfterRestart = [int](Get-PostgresScalar -Query "SELECT COUNT(*) FROM telemetry_control_state")
        $controlStateSnapshotAfterRestart = Get-PostgresScalar -Query "SELECT string_agg(s.name || '=' || to_char(state.trace_sample_rate, 'FM0.00') || ':' || state.source, ',' ORDER BY s.name) FROM telemetry_control_state state JOIN registered_service s ON s.id = state.service_id"
        if ($controlStateRowsAfterRestart -lt 3) {
            throw "Persisted sampler state was unavailable after PostgreSQL/backend restart."
        }
        if ($controlStateSnapshotAfterRestart -ne $controlStateSnapshotBeforeRestart) {
            throw "Persisted sampler state values changed across the PostgreSQL/backend restart."
        }
        $persistenceCheck.status = "PASSED"
        $persistenceCheck.analysis_history = "Anomaly and forecast IDs remained queryable after PostgreSQL and backend restarts."
        $persistenceCheck.adaptive_audit_before_restart = $adaptiveAuditCountBeforeRestart
        $persistenceCheck.adaptive_audit_after_restart = @($auditAfterRestartRows).Count
        $persistenceCheck.sampler_state_rows_before_restart = $controlStateRowsBeforeRestart
        $persistenceCheck.sampler_state_rows_after_restart = $controlStateRowsAfterRestart
        $persistenceCheck.sampler_state_snapshot = $controlStateSnapshotAfterRestart
    } else {
        $persistenceCheck.status = "SKIPPED_BY_PARAMETER"
    }

    $servicesResponse = Add-Measurement -Name "catalog" -Method "GET" -Url "$apiBase/services"
    Assert-Status -Name "service catalog" -Result $servicesResponse -ExpectedStatus 200
    $catalog = Convert-Body $servicesResponse
    $allObservations = New-Object System.Collections.Generic.List[object]
    $allLogs = New-Object System.Collections.Generic.List[object]
    $edgeMap = @{}
    foreach ($service in $catalog.items) {
        $metricResult = Add-Measurement -Name "rca-metrics" -Method "GET" -Url "$apiBase/telemetry/metrics?serviceName=$($service.name)&lookbackMinutes=60"
        Assert-Status -Name "RCA metrics for $($service.name)" -Result $metricResult -ExpectedStatus 200
        foreach ($sample in (Convert-Body $metricResult)) {
            $allObservations.Add($sample)
        }
        $logResult = Add-Measurement -Name "rca-logs" -Method "GET" -Url "$apiBase/telemetry/logs?serviceName=$($service.name)&lookbackMinutes=60&limit=100"
        Assert-Status -Name "RCA logs for $($service.name)" -Result $logResult -ExpectedStatus 200
        foreach ($entry in (Convert-Body $logResult)) {
            $allLogs.Add([pscustomobject]@{
                timestamp = $entry.timestamp
                service_name = $entry.serviceName
                severity = $entry.severity
                message = $entry.message
                trace_id = $entry.traceId
            })
        }
        $traceResult = Add-Measurement -Name "rca-traces" -Method "GET" -Url "$apiBase/telemetry/traces?serviceName=$($service.name)&lookbackMinutes=60&limit=20"
        Assert-Status -Name "RCA traces for $($service.name)" -Result $traceResult -ExpectedStatus 200
        foreach ($span in (Convert-Body $traceResult)) {
            if ($span.parentService -and $span.parentService -ne $span.serviceName) {
                $edgeMap["$($span.traceId):$($span.spanId)"] = [pscustomobject]@{
                    timestamp = $span.timestamp
                    parent_service = $span.parentService
                    child_service = $span.serviceName
                    trace_id = $span.traceId
                    call_count = 1
                }
            }
        }
    }

    if ($allObservations.Count -eq 0) {
        throw "No measured Prometheus observations are available to construct an RCA request."
    }
    $metadata = @($catalog.items | ForEach-Object {
        [pscustomobject]@{
            service_name = $_.name
            namespace = $_.namespace
            environment = $_.environment
            version = $_.version
        }
    })
    $incidentTimestamp = [DateTime]::UtcNow.ToString("o")
    $rcaBody = [pscustomobject]@{
        incident_timestamp = $incidentTimestamp
        observations = $allObservations.ToArray()
        trace_edges = @($edgeMap.Values)
        logs = $allLogs.ToArray()
        service_metadata = $metadata
    } | ConvertTo-Json -Depth 12 -Compress
    $rca = Add-Measurement -Name "analysis-root-causes" -Method "POST" -Url "$apiBase/ai/root-causes/rank" -Body $rcaBody
    Assert-Status -Name "root-cause ranking" -Result $rca -ExpectedStatus 200
    $rcaPayload = Convert-Body $rca
    $resourceAfter = Get-ContainerResourceSnapshot
} catch {
    $scenarioFailure = $_.Exception.Message
} finally {
    foreach ($serviceUrl in @($CheckoutUrl, $InventoryUrl, $PricingUrl)) {
        try {
            Set-Fault -ServiceUrl $serviceUrl -Mode "none"
        } catch {
            $scenarioFailure = "Fault reset failed for ${serviceUrl}: $($_.Exception.Message). $scenarioFailure"
        }
    }
    if ($adaptiveControlTouched) {
        try {
            $disabled = Invoke-MeasuredRequest -Method "PUT" -Url "$apiBase/adaptive/enabled" -Body '{"enabled":false}'
            if ($disabled.status -ne 200) {
                throw "HTTP $($disabled.status)"
            }
        } catch {
            $scenarioFailure = "Adaptive control cleanup failed: $($_.Exception.Message). $scenarioFailure"
        }
    }
    $client.Dispose()
}

$latencies = @($measurements | ForEach-Object { [double]$_.latency_ms } | Sort-Object)
$p50 = $null
$p95 = $null
if ($latencies.Count -gt 0) {
    $p50 = $latencies[[math]::Ceiling(0.50 * $latencies.Count) - 1]
    $p95 = $latencies[[math]::Ceiling(0.95 * $latencies.Count) - 1]
}
$elapsedSeconds = ([DateTime]::UtcNow - $startedAt).TotalSeconds
$finalStatus = "COMPLETED"
if ($scenarioFailure) {
    $finalStatus = "FAILED"
}
$observedRequestsPerSecond = $null
if ($parallelTimer.Elapsed.TotalSeconds -gt 0) {
    $observedRequestsPerSecond = [math]::Round(
        $ConcurrentProbeRequests / $parallelTimer.Elapsed.TotalSeconds,
        2
    )
}
$candidateCount = $null
$causalityConfirmed = $null
if ($null -ne $rcaPayload) {
    $candidateCount = @($rcaPayload.candidates).Count
    $causalityConfirmed = [bool]$rcaPayload.causality_confirmed
}
$anomalyPersistedCount = $null
$forecastPersisted = $null
if ($analysisResults.ContainsKey("anomalies")) {
    $anomalyPersistedCount = $analysisResults["anomalies"].persisted_anomalies
}
if ($analysisResults.ContainsKey("forecasts")) {
    $forecastPersisted = $analysisResults["forecasts"].persisted
}
$report = [pscustomobject]@{
    generated_at_utc = [DateTime]::UtcNow.ToString("o")
    run_id = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
    environment = "Local Docker Compose demonstration; synthetic workload"
    persistence_check = $persistenceCheck
    status = $finalStatus
    failure = $scenarioFailure
    scenarios = $measurements.ToArray()
    api_latency_ms = @{ sample_count = $latencies.Count; p50 = $p50; p95 = $p95 }
    bounded_concurrency_probe = @{
        request_count = $ConcurrentProbeRequests
        elapsed_seconds = [math]::Round($parallelTimer.Elapsed.TotalSeconds, 3)
        observed_requests_per_second = $observedRequestsPerSecond
        status_codes = @($parallelStatuses)
        note = "Bounded local concurrency only; not a CPU or memory saturation test."
    }
    analyses = @{
        telemetry_sources = @("Prometheus", "Loki", "Jaeger")
        checkout_evidence_counts = @{
            metrics = @($apiObservations["metrics"]).Count
            logs = @($apiObservations["logs"]).Count
            traces = @($apiObservations["traces"]).Count
        }
        root_cause_candidate_count = $candidateCount
        root_cause_causality_confirmed = $causalityConfirmed
        anomaly_persisted_count = $anomalyPersistedCount
        forecast_persisted = $forecastPersisted
        anomaly_history_count = $analysisResults["anomalies-history-count"]
        forecast_history_count = $analysisResults["forecasts-history-count"]
        normal_anomaly_and_forecast_status = "Both model-run endpoints must return HTTP 200 after collecting at least six measured samples."
    }
    docker_resource_snapshots = @{
        before = @($resourceBefore)
        after = @($resourceAfter)
        limitation = "Instantaneous docker stats snapshots, not peak or attributable resource overhead."
    }
    elapsed_seconds = [math]::Round($elapsedSeconds, 2)
    limitations = @(
        "Traffic and injected faults are synthetic.",
        "Resource pressure is a bounded 20 ms CPU workload per request in the local demo service, not host-wide saturation.",
        "Docker stats are instantaneous snapshots and do not measure peak or attributable adaptive overhead.",
        "This script's PostgreSQL/backend restart test verifies persistence for analysis history, adaptive audit, and sampler-state rows; it does not validate multi-node failover.",
        "Model metrics remain the held-out synthetic results documented in phase-3-evaluation.md."
    )
}

$jsonReport = $report | ConvertTo-Json -Depth 12
$resolvedOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($resolvedOutputPath)) | Out-Null
[System.IO.File]::WriteAllText(
    $resolvedOutputPath,
    $jsonReport,
    [System.Text.UTF8Encoding]::new($false)
)
Write-Output $jsonReport
if ($scenarioFailure) {
    throw $scenarioFailure
}
