import { useEffect, useState } from "react";
import { ApiError, api, type RegisteredService, type RootCauseResult } from "../api/client";
import { useAuth } from "../auth/AuthContext";

export function InvestigatePage() {
  const { user } = useAuth();
  const [services, setServices] = useState<RegisteredService[]>([]);
  const [result, setResult] = useState<RootCauseResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const canAnalyze = user?.role === "ADMIN" || user?.role === "ANALYST";

  useEffect(() => {
    api.services().then(({ items }) => setServices(items)).catch((cause: unknown) => {
      setError(cause instanceof Error ? cause.message : "Unable to load service metadata.");
    });
  }, []);

  async function investigate() {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const [metricWindows, logWindows, traceWindows] = await Promise.all([
        Promise.all(services.map((service) => api.telemetryMetrics(service.name, 60))),
        Promise.all(services.map((service) => api.telemetryLogs(service.name, 60, 100))),
        Promise.all(services.map((service) => api.telemetryTraces(service.name, 60, 20)))
      ]);
      const observations = metricWindows.flat();
      if (!observations.length) {
        throw new Error("No Prometheus observations are available for root-cause ranking.");
      }
      const incidentTimestamp = new Date().toISOString();
      const logs = logWindows.flat().map((entry) => ({
        timestamp: entry.timestamp,
        service_name: entry.serviceName,
        severity: entry.severity,
        message: entry.message,
        trace_id: entry.traceId
      }));
      const traceEdges = [...new Map(traceWindows.flat()
        .filter((span) => span.parentService && span.parentService !== span.serviceName)
        .map((span) => [`${span.traceId}:${span.spanId}`, {
          timestamp: span.timestamp,
          parent_service: span.parentService,
          child_service: span.serviceName,
          trace_id: span.traceId,
          call_count: 1
        }])).values()];
      const serviceMetadata = services.map((service) => ({
        service_name: service.name,
        namespace: service.namespace,
        environment: service.environment,
        version: service.version
      }));
      setResult(await api.rootCauseAnalysis({
        incident_timestamp: incidentTimestamp,
        observations,
        trace_edges: traceEdges,
        logs,
        service_metadata: serviceMetadata
      }));
    } catch (cause) {
      const status = cause instanceof ApiError ? ` (HTTP ${cause.status})` : "";
      setError(`${cause instanceof Error ? cause.message : "Root-cause analysis failed."}${status}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-head">
        <div><h1>Root-cause investigation</h1><p className="muted">Rank evidence-backed candidate services from recent metrics, Jaeger dependencies, and Loki logs.</p></div>
      </div>
      <div className="phase-banner">
        Candidate ranking is not confirmed causality. Analysis uses only evidence returned by configured telemetry stores.
      </div>
      {error ? <p className="error" role="alert">{error}</p> : null}
      {canAnalyze ? (
        <section className="card analysis-toolbar">
          <div><strong>Gather a recent evidence window</strong><p className="muted">{services.length} service metadata entries · 60-minute metrics/logs/traces window.</p></div>
          <button className="primary-btn" disabled={busy || services.length === 0} onClick={() => void investigate()}>{busy ? "Gathering evidence…" : "Rank root-cause candidates"}</button>
        </section>
      ) : <p className="phase-banner">Read-only role: contact an analyst or administrator to run an investigation.</p>}
      {busy ? <p className="muted" role="status">Querying telemetry stores and ranking candidates…</p> : null}
      {result ? (
        <section className="card">
          <h2>Candidate ranking · {new Date(result.incident_timestamp).toLocaleString()}</h2>
          <p className="phase-banner">Causality confirmed: {result.causality_confirmed ? "yes" : "no"}. Rankings are evidence scores, not confidence probabilities.</p>
          {!result.candidates.length ? <p className="empty">No candidates met the model’s evidence requirements. This is not evidence that the system is healthy.</p> : (
            <div className="candidate-list">{result.candidates.map((candidate) => (
              <article className="candidate-card" key={candidate.service_name}>
                <div className="candidate-heading"><h3>#{candidate.rank} {candidate.service_name}</h3><strong>Evidence score {candidate.ranking_score.toFixed(3)}</strong></div>
                <p className="muted">{candidate.score_semantics}</p>
                {!candidate.evidence.length ? <p className="empty">No supporting evidence returned.</p> : (
                  <pre className="evidence-json">{JSON.stringify(candidate.evidence, null, 2)}</pre>
                )}
              </article>
            ))}</div>
          )}
          <ul className="limitations">{result.limitations.map((limitation) => <li key={limitation}>{limitation}</li>)}</ul>
        </section>
      ) : null}
      {!services.length && !error ? <p className="muted">Loading registered service metadata…</p> : null}
    </>
  );
}
