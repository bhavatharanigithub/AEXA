import { useCallback, useEffect, useState } from "react";
import { ApiError, api, type ForecastAnalysisResult, type RegisteredService, type StoredForecast } from "../api/client";
import { useAuth } from "../auth/AuthContext";

export function PredictionsPage() {
  const { user } = useAuth();
  const [services, setServices] = useState<RegisteredService[]>([]);
  const [serviceName, setServiceName] = useState("");
  const [history, setHistory] = useState<StoredForecast[]>([]);
  const [latest, setLatest] = useState<ForecastAnalysisResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canAnalyze = user?.role === "ADMIN" || user?.role === "ANALYST";

  const refresh = useCallback(async (service = serviceName) => {
    try {
      setHistory(await api.storedForecasts(service || undefined));
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to load saved forecasts.");
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

  async function forecast() {
    setBusy(true);
    setError(null);
    try {
      setLatest(await api.runForecast(serviceName));
      await refresh(serviceName);
    } catch (cause) {
      const status = cause instanceof ApiError ? ` (HTTP ${cause.status})` : "";
      setError(`${cause instanceof Error ? cause.message : "Forecast request failed."}${status}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-head">
        <div><h1>Performance predictions</h1><p className="muted">Five-minute p95 latency degradation classifier; probability is not a guarantee.</p></div>
        <label className="service-select">Service
          <select value={serviceName} onChange={(event) => setServiceName(event.currentTarget.value)}>
            {services.map((service) => <option key={service.id} value={service.name}>{service.name}</option>)}
          </select>
        </label>
      </div>
      {error ? <p className="error" role="alert">{error}</p> : null}
      {canAnalyze ? (
        <section className="card analysis-toolbar">
          <div><strong>Forecast degradation</strong><p className="muted">Target: p95 latency at least 1.0 second within five one-minute samples. Needs six or more measured observations.</p></div>
          <button className="primary-btn" disabled={busy || !serviceName} onClick={() => void forecast()}>{busy ? "Forecasting…" : "Forecast latest window"}</button>
        </section>
      ) : <p className="phase-banner">Read-only role: contact an analyst or administrator to request a forecast.</p>}

      {latest ? (
        <section className={`card prediction-result${latest.degradation_predicted ? " prediction-alert" : ""}`}>
          <div className="prediction-heading">
            <div><p className="muted">Model {latest.model_version} · as of {new Date(latest.as_of).toLocaleString()}</p><h2>{latest.degradation_predicted ? "Degradation predicted" : "No degradation predicted"}</h2></div>
            <strong>{(latest.degradation_probability * 100).toFixed(1)}%</strong>
          </div>
          <p>{latest.target}</p>
          <p className="muted">Operational threshold: {(latest.operational_probability_threshold * 100).toFixed(0)}% · saved to analysis history: {latest.persisted ? "yes" : "no"}.</p>
          <h3>Feature contributions</h3>
          {!latest.feature_contributions.length ? <p className="empty">No feature contribution data was returned.</p> : (
            <ul className="contribution-list">{latest.feature_contributions.map((item, index) => <li key={`${item.feature_condition}-${index}`}><span>{item.feature_condition}</span><strong>{item.contribution.toFixed(4)}</strong></li>)}</ul>
          )}
          <ul className="limitations">{latest.limitations.map((limitation) => <li key={limitation}>{limitation}</li>)}</ul>
        </section>
      ) : null}

      <section className="card">
        <h2>Persisted forecast history</h2>
        {loading ? <p className="muted" role="status">Loading prediction history…</p> : null}
        {!loading && !history.length ? <p className="empty">No saved predictions for this service.</p> : null}
        {history.length ? <div className="table-scroll"><table className="table">
          <thead><tr><th>As of</th><th>Degradation probability</th><th>Threshold</th><th>Horizon</th><th>Outcome</th></tr></thead>
          <tbody>{history.map((record) => (
            <tr key={record.id}><td>{new Date(record.predictedFor).toLocaleString()}</td><td>{(record.probability * 100).toFixed(1)}%</td><td>{(record.threshold * 100).toFixed(0)}%</td><td>{record.horizonMinutes} min</td><td>{record.status}</td></tr>
          ))}</tbody>
        </table></div> : null}
      </section>
    </>
  );
}
