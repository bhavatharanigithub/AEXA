import { useCallback, useEffect, useState } from "react";
import { ApiError, api, type AnomalyAnalysisResult, type RegisteredService, type StoredAnomaly } from "../api/client";
import { useAuth } from "../auth/AuthContext";

export function AnomaliesPage() {
  const { user } = useAuth();
  const [services, setServices] = useState<RegisteredService[]>([]);
  const [serviceName, setServiceName] = useState("");
  const [history, setHistory] = useState<StoredAnomaly[]>([]);
  const [latest, setLatest] = useState<AnomalyAnalysisResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canAnalyze = user?.role === "ADMIN" || user?.role === "ANALYST";

  const refresh = useCallback(async (service = serviceName) => {
    try {
      const stored = await api.storedAnomalies(service || undefined);
      setHistory(stored);
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to load saved anomaly results.");
    } finally {
      setLoading(false);
    }
  }, [serviceName]);

  useEffect(() => {
    let active = true;
    api.services().then(({ items }) => {
      if (!active) return;
      setServices(items);
      setServiceName((current) => current || items[0]?.name || "");
      if (!items.length) setLoading(false);
    }).catch((cause: unknown) => {
      if (active) {
        setError(cause instanceof Error ? cause.message : "Unable to load service catalog.");
        setLoading(false);
      }
    });
    return () => { active = false; };
  }, []);

  useEffect(() => { if (serviceName) void refresh(serviceName); }, [serviceName, refresh]);

  async function analyze() {
    setBusy(true);
    setError(null);
    try {
      setLatest(await api.runAnomalyAnalysis(serviceName));
      await refresh(serviceName);
    } catch (cause) {
      const status = cause instanceof ApiError ? ` (HTTP ${cause.status})` : "";
      setError(`${cause instanceof Error ? cause.message : "Anomaly analysis failed."}${status}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-head">
        <div><h1>Anomaly timeline</h1><p className="muted">Saved model detections from measured Prometheus windows.</p></div>
        <label className="service-select">Service
          <select value={serviceName} onChange={(event) => setServiceName(event.currentTarget.value)}>
            {services.map((service) => <option key={service.id} value={service.name}>{service.name}</option>)}
          </select>
        </label>
      </div>
      {error ? <p className="error" role="alert">{error}</p> : null}
      {canAnalyze ? (
        <section className="card analysis-toolbar">
          <div><strong>Run the trained anomaly model</strong><p className="muted">Requires at least six measured observations within the selected 60-minute window.</p></div>
          <button className="primary-btn" disabled={busy || !serviceName} onClick={() => void analyze()}>{busy ? "Analyzing…" : "Analyze latest window"}</button>
        </section>
      ) : <p className="phase-banner">Read-only role: contact an analyst or administrator to run a new analysis.</p>}

      {latest ? (
        <section className="card">
          <h2>Latest model response · {latest.model_version}</h2>
          <p className="muted">{latest.results.length} evaluated samples · {latest.persisted_anomalies} new anomaly records saved.</p>
          {!latest.results.some((item) => item.anomalous) ? <p className="empty">The model marked no samples anomalous in this window.</p> : (
            <div className="table-scroll"><table className="table">
              <thead><tr><th>Time</th><th>Score</th><th>Method</th><th>Top contributions</th></tr></thead>
              <tbody>{latest.results.filter((item) => item.anomalous).map((item) => (
                <tr key={item.timestamp}>
                  <td>{new Date(item.timestamp).toLocaleString()}</td><td>{item.anomaly_score.toFixed(4)}</td><td>{item.detection_method}</td>
                  <td>{item.feature_contributions.slice(0, 3).map((feature) => feature.feature_condition).join("; ") || "No explanation returned"}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )}
          <ul className="limitations">{latest.limitations.map((limitation) => <li key={limitation}>{limitation}</li>)}</ul>
        </section>
      ) : null}

      <section className="card">
        <h2>Persisted anomaly records</h2>
        {loading ? <p className="muted" role="status">Loading anomaly history…</p> : null}
        {!loading && !history.length ? <p className="empty">No anomaly records have been saved for this service. Run an analysis when telemetry is available.</p> : null}
        {history.length ? <div className="table-scroll"><table className="table">
          <thead><tr><th>Detected at</th><th>Score</th><th>Detector</th><th>Stored features</th></tr></thead>
          <tbody>{history.map((record) => (
            <tr key={record.id}><td>{new Date(record.detectedAt).toLocaleString()}</td><td>{record.score?.toFixed(4) ?? "—"}</td><td>{record.detector}</td><td>{Object.entries(record.features ?? {}).map(([key, value]) => `${key}: ${value ?? "—"}`).join(", ")}</td></tr>
          ))}</tbody>
        </table></div> : null}
      </section>
    </>
  );
}
