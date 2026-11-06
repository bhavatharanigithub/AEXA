import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { ApiError, api, type LogEntry, type MetricObservation, type RegisteredService, type TraceSpan } from "../api/client";

type Tab = "metrics" | "logs" | "traces";

function timestampLabel(value: string): string {
  return new Date(value).toLocaleString();
}

function MetricChart({ observations }: { observations: MetricObservation[] }) {
  const values = observations.map((item) => item.p95_latency_seconds).filter((value): value is number => value !== null);
  if (values.length < 2) {
    return <p className="empty">At least two measured latency samples are needed to draw this chart.</p>;
  }
  const max = Math.max(...values, 0.001);
  const points = values
    .map((value, index) => `${(index / (values.length - 1)) * 600},${100 - (value / max) * 90}`)
    .join(" ");
  return (
    <figure className="metric-chart">
      <svg viewBox="0 0 600 110" role="img" aria-label="Measured p95 latency over time">
        <polyline points={points} fill="none" stroke="currentColor" strokeWidth="3" vectorEffect="non-scaling-stroke" />
      </svg>
      <figcaption>Measured p95 latency; max visible sample {(max * 1000).toFixed(1)} ms</figcaption>
    </figure>
  );
}

export function TelemetryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [requestedService] = useState(() => searchParams.get("serviceName") ?? "");
  const [services, setServices] = useState<RegisteredService[]>([]);
  const [serviceName, setServiceName] = useState(requestedService);
  const [tab, setTab] = useState<Tab>("metrics");
  const [lookbackMinutes, setLookbackMinutes] = useState(60);
  const [searchQuery, setSearchQuery] = useState("");
  const [metrics, setMetrics] = useState<MetricObservation[] | null>(null);
  const [logs, setLogs] = useState<LogEntry[] | null>(null);
  const [traces, setTraces] = useState<TraceSpan[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [queryError, setQueryError] = useState<string | null>(null);
  const [lastRefresh, setLastRefresh] = useState<string | null>(null);
  const [refreshTick, setRefreshTick] = useState(0);

  useEffect(() => {
    let active = true;
    api.services().then(({ items }) => {
      if (active) {
        setServices(items);
        const selected = items.find((item) => item.name === requestedService)?.name ?? items[0]?.name ?? "";
        setServiceName((current) => items.some((item) => item.name === current) ? current : selected);
      }
    }).catch((cause: unknown) => {
      if (active) setCatalogError(cause instanceof Error ? cause.message : "Service catalog is unavailable.");
    });
    return () => { active = false; };
  }, [requestedService]);

  useEffect(() => {
    const timer = window.setInterval(() => setRefreshTick((tick) => tick + 1), 15000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!serviceName) return;
    let active = true;
    setLoading(true);
    setQueryError(null);
    const request = tab === "metrics"
      ? api.telemetryMetrics(serviceName, lookbackMinutes).then((result) => { if (active) setMetrics(result); })
      : tab === "logs"
        ? api.telemetryLogs(serviceName, lookbackMinutes, 100).then((result) => { if (active) setLogs(result); })
        : api.telemetryTraces(serviceName, lookbackMinutes, 100).then((result) => { if (active) setTraces(result); });
    request.then(() => {
      if (active) setLastRefresh(new Date().toISOString());
    }).catch((cause: unknown) => {
      if (active) {
        const status = cause instanceof ApiError ? ` (HTTP ${cause.status})` : "";
        setQueryError(`${cause instanceof Error ? cause.message : "Telemetry query failed."}${status}`);
      }
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [serviceName, tab, lookbackMinutes, refreshTick]);

  const observations = metrics ?? [];
  const mostRecentSample = observations.at(-1)?.timestamp ?? null;
  const isStale = mostRecentSample !== null && Date.now() - new Date(mostRecentSample).getTime() > 120_000;
  const mostRecentEvidence = tab === "logs"
    ? logs?.[0]?.timestamp
    : tab === "traces"
      ? traces?.[0]?.timestamp
      : null;
  const evidenceIsStale = mostRecentEvidence !== null
    && mostRecentEvidence !== undefined
    && Date.now() - new Date(mostRecentEvidence).getTime() > 120_000;
  const normalizedSearch = searchQuery.trim().toLowerCase();
  const filteredLogs = (logs ?? []).filter((entry) => !normalizedSearch || `${entry.severity} ${entry.message} ${entry.traceId ?? ""}`.toLowerCase().includes(normalizedSearch));
  const filteredTraces = (traces ?? []).filter((span) => !normalizedSearch || `${span.serviceName} ${span.parentService ?? ""} ${span.operationName} ${span.traceId}`.toLowerCase().includes(normalizedSearch));

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Telemetry explorer</h1>
          <p className="muted">Metrics from Prometheus, logs from Loki, and traces from Jaeger.</p>
        </div>
        <label className="service-select">
          Service
          <select value={serviceName} onChange={(event) => {
            const value = event.currentTarget.value;
            setServiceName(value);
            setSearchParams(value ? { serviceName: value } : {});
          }} disabled={!services.length}>
            {services.map((service) => <option key={service.id} value={service.name}>{service.name}</option>)}
          </select>
        </label>
      </div>
      {catalogError ? <p className="error" role="alert">{catalogError}</p> : null}
      {!services.length && !catalogError ? <p className="muted">Loading service catalog…</p> : null}
      <div className="telemetry-tabs" role="tablist" aria-label="Telemetry type">
        {(["metrics", "logs", "traces"] as const).map((value) => (
          <button
            className={`ghost-btn${tab === value ? " selected-tab" : ""}`}
            key={value}
            role="tab"
            aria-selected={tab === value}
            onClick={() => setTab(value)}
          >
            {value[0].toUpperCase() + value.slice(1)}
          </button>
        ))}
        {lastRefresh ? <span className="muted">Updated {timestampLabel(lastRefresh)}</span> : null}
      </div>
      <div className="telemetry-filter-bar">
        <label className="explorer-filter">Time range<select aria-label="Telemetry time range" value={lookbackMinutes} onChange={(event) => setLookbackMinutes(Number(event.currentTarget.value))}>
          <option value={15}>Last 15 minutes</option><option value={60}>Last 60 minutes</option><option value={360}>Last 6 hours</option>
        </select></label>
        {tab !== "metrics" ? <label className="explorer-search telemetry-search"><span aria-hidden="true">⌕</span><input aria-label={`Search ${tab}`} placeholder={tab === "logs" ? "Search messages, severity, or trace ID" : "Search operation, service, or trace ID"} value={searchQuery} onChange={(event) => setSearchQuery(event.currentTarget.value)} /></label> : null}
      </div>
      {loading ? <p className="muted" role="status">Loading {tab}…</p> : null}
      {queryError ? (
        <p className="error" role="alert">
          {queryError}{lastRefresh ? ` Last successful refresh: ${timestampLabel(lastRefresh)}; any retained results are stale.` : ""}
        </p>
      ) : null}
      {isStale && tab === "metrics" ? (
        <p className="phase-banner stale-banner">Latest metric sample is older than two minutes; displayed metrics may be stale.</p>
      ) : null}
      {evidenceIsStale ? (
        <p className="phase-banner stale-banner">Latest {tab} evidence is older than two minutes; displayed data may be stale.</p>
      ) : null}

      {tab === "metrics" && !loading && !queryError ? (
        <section className="card">
          <h2>{serviceName} metrics</h2>
          {!metrics?.length ? <p className="empty">No Prometheus samples are available for this service in the last {lookbackMinutes} minutes.</p> : (
            <>
              <MetricChart observations={observations} />
              <div className="table-scroll">
                <table className="table">
                  <thead><tr><th>Timestamp</th><th>Requests / s</th><th>5xx ratio</th><th>p95 latency</th></tr></thead>
                  <tbody>{observations.slice().reverse().map((metric, index) => (
                    <tr key={`${metric.timestamp}-${index}`}>
                      <td>{timestampLabel(metric.timestamp)}</td>
                      <td>{metric.request_rate?.toFixed(3) ?? "—"}</td>
                      <td>{metric.error_rate === null ? "—" : `${(metric.error_rate * 100).toFixed(2)}%`}</td>
                      <td>{metric.p95_latency_seconds === null ? "—" : `${(metric.p95_latency_seconds * 1000).toFixed(1)} ms`}</td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            </>
          )}
        </section>
      ) : null}

      {tab === "logs" && !loading && !queryError ? (
        <section className="card">
          <h2>{serviceName} logs</h2>
          {!logs?.length ? <p className="empty">No Loki log entries were returned for this service and time range.</p> : !filteredLogs.length ? <p className="empty">No logs match this search.</p> : (
            <div className="table-scroll"><table className="table">
              <thead><tr><th>Timestamp</th><th>Severity</th><th>Message</th><th>Trace ID</th></tr></thead>
              <tbody>{filteredLogs.map((entry, index) => (
                <tr key={`${entry.timestamp}-${index}`}>
                  <td>{timestampLabel(entry.timestamp)}</td>
                  <td>{entry.severity}</td>
                  <td>{entry.message}</td>
                  <td>{entry.traceId ?? "—"}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )}
        </section>
      ) : null}

      {tab === "traces" && !loading && !queryError ? (
        <section className="card">
          <h2>{serviceName} traces and spans</h2>
          {!traces?.length ? <p className="empty">No Jaeger spans were returned for this service and time range.</p> : !filteredTraces.length ? <p className="empty">No spans match this search.</p> : (
            <div className="table-scroll"><table className="table">
              <thead><tr><th>Timestamp</th><th>Service path</th><th>Operation</th><th>Duration</th><th>Trace</th></tr></thead>
              <tbody>{filteredTraces.map((span: TraceSpan) => (
                <tr key={span.spanId}>
                  <td>{timestampLabel(span.timestamp)}</td>
                  <td>{span.parentService ? `${span.parentService} → ${span.serviceName}` : span.serviceName}</td>
                  <td>{span.operationName}</td>
                  <td>{(span.durationMicros / 1000).toFixed(2)} ms</td>
                  <td><a href={`http://localhost:16686/trace/${encodeURIComponent(span.traceId)}`} target="_blank" rel="noreferrer">{span.traceId.slice(0, 12)}…</a></td>
                </tr>
              ))}</tbody>
            </table></div>
          )}
        </section>
      ) : null}
    </>
  );
}
