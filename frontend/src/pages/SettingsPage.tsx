import { useEffect, useState } from "react";
import { api, type Capabilities, type HealthResponse, type TelemetryOverview } from "../api/client";

type StatusValue = "Available" | "Unavailable" | "Unknown" | "Supported" | "Not available" | "Not reported";

function capabilityStatus(enabled: boolean | undefined): StatusValue {
  if (enabled === undefined) return "Unknown";
  return enabled ? "Supported" : "Not available";
}

function statusClass(value: StatusValue): string {
  return value === "Available" || value === "Supported" ? "system-state available" : "system-state unavailable";
}

export function SettingsPage() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null);
  const [telemetry, setTelemetry] = useState<TelemetryOverview | null>(null);
  const [errors, setErrors] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    Promise.allSettled([api.health(), api.capabilities(), api.telemetryOverview()]).then((results) => {
      if (!active) return;
      const failures: string[] = [];
      if (results[0].status === "fulfilled") setHealth(results[0].value);
      else failures.push("Backend health is unavailable.");
      if (results[1].status === "fulfilled") setCapabilities(results[1].value);
      else failures.push("Platform capability metadata is unavailable.");
      if (results[2].status === "fulfilled") setTelemetry(results[2].value);
      else failures.push("Prometheus query health is unavailable.");
      setErrors(failures);
      setLoading(false);
    });
    return () => { active = false; };
  }, []);

  const integrations: Array<[string, StatusValue, string]> = [
    ["Spring Boot API", health ? health.status === "UP" ? "Available" : "Unavailable" : "Unknown", "Backend health endpoint"],
    ["PostgreSQL", health ? health.database === "UP" ? "Available" : "Unavailable" : "Unknown", "Backend database health"],
    ["Prometheus", telemetry ? telemetry.available ? "Available" : "Unavailable" : "Unknown", telemetry ? `Query source: ${telemetry.source}` : "No telemetry health response"],
    ["ML analysis capability", capabilities ? capabilities.anomalyDetection || capabilities.forecasting ? "Supported" : "Not reported" : "Unknown", "Capability flags only; the ML service has no separate health endpoint here"],
    ["Loki · Jaeger · Kafka", "Not reported", "Configured in local infrastructure; independent health is not exposed here"]
  ];

  const features: Array<[string, boolean | undefined]> = [
    ["Anomaly detection", capabilities?.anomalyDetection],
    ["Forecasting", capabilities?.forecasting],
    ["Root-cause ranking", capabilities?.rootCauseAnalysis],
    ["Explainability", capabilities?.explainability],
    ["Adaptive controller actuation", capabilities?.adaptiveControlActuation],
    ["Incident persistence", capabilities?.incidentPersistence]
  ];

  return (
    <>
      <div className="page-head">
        <div><h1>System health & settings</h1><p className="muted">Read-only status from existing backend health and capability APIs.</p></div>
      </div>
      {errors.map((error) => <p className="error" role="alert" key={error}>{error}</p>)}
      {loading ? <p className="muted" role="status">Loading system status…</p> : null}
      <section className="card system-status-card">
        <div className="panel-heading"><div><h2>Connected components</h2><p>Only status values returned by the application are shown as available.</p></div><span className="report-source-badge">LIVE API STATUS</span></div>
        <div className="system-integration-list">{integrations.map(([name, status, detail]) => (
          <article className="system-integration-row" key={name}>
            <span className="integration-glyph">{name.slice(0, 1)}</span>
            <div><strong>{name}</strong><small>{detail}</small></div>
            <span className={statusClass(status)}>{status}</span>
          </article>
        ))}</div>
        {health?.timestamp ? <p className="system-checked">Backend health checked {new Date(health.timestamp).toLocaleString()} · profile {health.profile}</p> : null}
      </section>
      <section className="card system-status-card">
        <div className="panel-heading"><div><h2>Platform capabilities</h2><p>Capability flags are not runtime performance measurements.</p></div>{capabilities ? <span className="report-source-badge">PHASE {capabilities.phase}</span> : null}</div>
        <div className="capability-status-grid">{features.map(([name, enabled]) => {
          const status = capabilityStatus(enabled);
          return <div className="capability-status-row" key={name}><span>{name}</span><strong className={statusClass(status)}>{status}</strong></div>;
        })}</div>
      </section>
      <section className="card report-note">
        <h2>Configuration controls</h2>
        <p>Credentials and role enforcement are managed by the backend. Model configuration, notification preferences, and editing telemetry integrations are not exposed by the current API. Adaptive sampling controls remain in the dedicated Adaptive Telemetry page.</p>
      </section>
    </>
  );
}
