import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  ApiError,
  api,
  type Capabilities,
  type HealthResponse,
  type TelemetryOverview
} from "../api/client";

export function OverviewPage() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [telemetry, setTelemetry] = useState<TelemetryOverview | null>(null);
  const [telemetryError, setTelemetryError] = useState<string | null>(null);
  const [incidentCount, setIncidentCount] = useState<number | null>(null);
  const [incidentError, setIncidentError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    Promise.all([api.health(), api.capabilities()])
      .then(([h, c]) => {
        if (active) {
          setHealth(h);
          setCapabilities(c);
        }
      })
      .catch(() => {
        if (active) setError("Backend is unreachable. Start the Spring Boot API on port 8080.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    api.incidents()
      .then(({ items }) => {
        if (active) {
          setIncidentCount(items.length);
          setIncidentError(null);
        }
      })
      .catch(() => {
        if (active) setIncidentError("Incident count unavailable.");
      });

    const refreshTelemetry = () => {
      api.telemetryOverview()
        .then((result) => {
          if (active) {
            setTelemetry(result);
            setTelemetryError(null);
          }
        })
        .catch((cause: unknown) => {
          if (!active) return;
          if (cause instanceof ApiError && cause.status === 503) {
            setTelemetryError(
              "Prometheus is unavailable. Start Docker Desktop, then run `docker compose -f infra\\docker-compose.yml up -d --build` from the project folder."
            );
          } else if (cause instanceof ApiError && cause.status === 401) {
            setTelemetryError("Your session has expired. Sign in again to load telemetry.");
          } else if (cause instanceof ApiError) {
            setTelemetryError(`Telemetry query failed (HTTP ${cause.status}): ${cause.message}`);
          } else {
            setTelemetryError("Could not reach the telemetry API. Check that the backend is running.");
          }
        });
    };
    refreshTelemetry();
    const refreshTimer = window.setInterval(refreshTelemetry, 15_000);

    return () => {
      active = false;
      window.clearInterval(refreshTimer);
    };
  }, []);

  const hasTelemetry = telemetry?.available === true && telemetry.hasData;
  const telemetryEmpty = telemetry?.available === true && !telemetry.hasData;
  const requestRate = telemetry?.requestsPerSecond ?? null;
  const errorRate = telemetry?.errorRate ?? null;
  const p95Latency = telemetry?.p95LatencySeconds ?? null;
  const telemetryIsStale = telemetry !== null && Date.now() - new Date(telemetry.queriedAt).getTime() > 120_000;

  return (
    <div className="overview-shell">
      <div className="page-head overview-heading">
        <div><p className="page-eyebrow">WORKSPACE OVERVIEW</p><h1>Welcome to Aexa</h1><p className="muted">A live view of the telemetry and incident records currently available to this workspace.</p></div>
        <div className="overview-range"><span className="range-dot" />Last 5 minutes <span aria-hidden="true">⌄</span></div>
      </div>

      {error ? <p className="error" role="alert">{error}</p> : null}
      {telemetryError ? <p className="error" role="alert">{telemetryError}</p> : null}
      {incidentError ? <p className="error" role="alert">{incidentError}</p> : null}
      {telemetryIsStale ? <p className="phase-banner stale-banner" role="status">The most recent telemetry query is older than two minutes. Displayed values may be stale.</p> : null}

      <section className="health-strip">
        <span className={`health-status${health?.status === "UP" ? " is-up" : ""}`}><i />API {health?.status ?? "UNKNOWN"}</span>
        <span>Database <strong>{health?.database ?? "UNKNOWN"}</strong></span>
        <span>Profile <strong>{health?.profile ?? "—"}</strong></span>
        <span>Capabilities phase <strong>{capabilities?.phase ?? "—"}</strong></span>
        <span className="health-updated">{health?.timestamp ? `Checked ${new Date(health.timestamp).toLocaleTimeString()}` : loading ? "Checking system health…" : "Health unavailable"}</span>
      </section>

      <div className="overview-section-heading"><div><h2>Service signals</h2><p>Values returned by the connected Prometheus query; no samples are estimated.</p></div><Link to="/telemetry">Open telemetry explorer <span>↗</span></Link></div>
      <div className="card-grid kpi-grid overview-kpis">
        <article className="card kpi"><div className="metric-card-top"><span className="metric-icon">↗</span><span className="metric-period">5 min</span></div><div className="kpi-title">Request throughput</div><div className="kpi-value">{hasTelemetry && requestRate !== null ? `${requestRate.toFixed(2)}/s` : "—"}</div><div className="kpi-meta">Requests per second · Prometheus</div></article>
        <article className="card kpi"><div className="metric-card-top"><span className="metric-icon error-icon">!</span><span className="metric-period">5 min</span></div><div className="kpi-title">5xx error rate</div><div className="kpi-value">{hasTelemetry && errorRate !== null ? `${(errorRate * 100).toFixed(2)}%` : "—"}</div><div className="kpi-meta">Observed server-error ratio</div></article>
        <article className="card kpi"><div className="metric-card-top"><span className="metric-icon latency-icon">◷</span><span className="metric-period">p95</span></div><div className="kpi-title">Request latency</div><div className="kpi-value">{hasTelemetry && p95Latency !== null ? `${(p95Latency * 1000).toFixed(1)} ms` : "—"}</div><div className="kpi-meta">Prometheus histogram quantile</div></article>
        <article className="card kpi"><div className="metric-card-top"><span className="metric-icon incident-icon">▤</span><span className="metric-period">Stored</span></div><div className="kpi-title">Incident records</div><div className="kpi-value">{incidentCount ?? "—"}</div><div className="kpi-meta">Persisted incident history</div></article>
      </div>

      <section className="card live-services-card">
        <div className="panel-heading"><div><h2>Service health</h2><p>Registered services with current measured telemetry.</p></div><Link to="/services">View services <span>↗</span></Link></div>
        <p className="telemetry-source">{telemetryEmpty ? "Prometheus is reachable, but no request metrics have arrived yet." : hasTelemetry ? `Source: ${telemetry.source} · queried ${new Date(telemetry.queriedAt).toLocaleTimeString()}` : "Waiting for Prometheus telemetry."}</p>
        {hasTelemetry && telemetry.services.length ? <div className="table-scroll"><table className="table overview-table">
          <thead><tr><th>Service</th><th>Requests / second</th><th>5xx error rate</th><th>p95 latency</th><th>Signal</th></tr></thead>
          <tbody>{telemetry.services.map((service) => <tr key={service.serviceName}>
            <td><span className="service-name-mark">{service.serviceName.slice(0, 1).toUpperCase()}</span><strong>{service.serviceName}</strong></td>
            <td>{service.requestsPerSecond.toFixed(2)}</td><td>{(service.errorRate * 100).toFixed(2)}%</td>
            <td>{service.p95LatencySeconds === null ? "—" : `${(service.p95LatencySeconds * 1000).toFixed(1)} ms`}</td>
            <td><span className="measured-status"><i />Measured</span></td>
          </tr>)}</tbody>
        </table></div> : null}
        {!hasTelemetry && !telemetryEmpty && !telemetryError && !loading ? <p className="empty">No telemetry values are currently available.</p> : null}
      </section>

      <section className="overview-lower-grid">
        <article className="card overview-action-card"><span className="overview-card-icon">⌁</span><p className="page-eyebrow">INTELLIGENCE</p><h2>Investigate a signal</h2><p>Run an analysis when recent telemetry is available. Model results include returned evidence and limitations.</p><div><Link to="/anomalies">Anomalies <span>↗</span></Link><Link to="/predictions">Predictions <span>↗</span></Link><Link to="/investigate">Root-cause analysis <span>↗</span></Link></div></article>
        <article className="card overview-action-card"><span className="overview-card-icon control-icon">◉</span><p className="page-eyebrow">OPERATIONS</p><h2>Adaptive telemetry</h2><p>Review controller mode, sampling bounds, signal freshness, and audited decisions. Sampling adjustments are not resource measurements.</p><Link className="action-text-link" to="/adaptive">Open adaptive controls <span>↗</span></Link></article>
      </section>
    </div>
  );
}
