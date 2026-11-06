import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api, type Incident } from "../api/client";

export function IncidentsPage() {
  const [items, setItems] = useState<Incident[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [severity, setSeverity] = useState("all");

  useEffect(() => {
    api.incidents()
      .then((r) => setItems(r.items))
      .catch((cause: unknown) => setError(cause instanceof Error ? cause.message : "Unable to load incidents."));
  }, []);

  const statuses = useMemo(() => [...new Set((items ?? []).map((item) => item.status))].sort(), [items]);
  const severities = useMemo(() => [...new Set((items ?? []).map((item) => item.severity))].sort(), [items]);
  const filteredItems = useMemo(() => {
    const search = query.trim().toLowerCase();
    return (items ?? []).filter((incident) => {
      const matchesSearch = !search || [incident.title, incident.summary ?? "", ...incident.affectedServices].some((value) => value.toLowerCase().includes(search));
      return matchesSearch && (status === "all" || incident.status === status) && (severity === "all" || incident.severity === severity);
    }).sort((left, right) => new Date(right.startedAt).getTime() - new Date(left.startedAt).getTime());
  }, [items, query, severity, status]);

  return (
    <>
      <div className="page-head">
        <div><h1>Incident history</h1><p className="muted">Persisted incidents only. Anomaly detections and forecasts are not automatically incidents.</p></div>
      </div>
      {error ? <p className="error" role="alert">{error}</p> : null}
      <div className="card">
        <div className="explorer-toolbar">
          <label className="explorer-search"><span aria-hidden="true">⌕</span><input aria-label="Search incidents" placeholder="Search title, summary, or service" value={query} onChange={(event) => setQuery(event.currentTarget.value)} /></label>
          <label className="explorer-filter">Status<select value={status} onChange={(event) => setStatus(event.currentTarget.value)}><option value="all">All statuses</option>{statuses.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
          <label className="explorer-filter">Severity<select value={severity} onChange={(event) => setSeverity(event.currentTarget.value)}><option value="all">All severities</option>{severities.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
          <span className="explorer-count">{items === null ? "Loading incidents…" : `${filteredItems.length} of ${items.length} records`}</span>
        </div>
        {items === null && !error ? <p className="muted" role="status">Loading incident history…</p> : null}
        {items && items.length === 0 ? <p className="empty">No incidents have been recorded. Anomaly and forecast records are not automatically promoted to incidents.</p> : null}
        {items?.length && !filteredItems.length ? <p className="empty">No incidents match these filters.</p> : null}
        {!!filteredItems.length ? (
          <div className="table-scroll"><table className="table incident-table">
            <thead><tr><th>Started</th><th>Incident</th><th>Status</th><th>Severity</th><th>Affected services</th><th>Details</th></tr></thead>
            <tbody>{filteredItems.map((incident) => (
              <tr key={incident.id}>
                <td className="incident-time">{new Date(incident.startedAt).toLocaleString()}</td>
                <td><strong>{incident.title}</strong>{incident.summary ? <div className="muted incident-summary">{incident.summary}</div> : null}</td>
                <td><span className={`incident-pill status-${incident.status.toLowerCase()}`}>{incident.status}</span></td>
                <td><span className={`incident-pill severity-${incident.severity.toLowerCase()}`}>{incident.severity}</span></td>
                <td>{incident.affectedServices.length ? <div className="incident-services">{incident.affectedServices.map((service) => <Link key={service} to={`/telemetry?serviceName=${encodeURIComponent(service)}`}>{service}</Link>)}</div> : "—"}</td>
                <td><details className="incident-details"><summary>View</summary><div><strong>Incident record</strong><span>Started {new Date(incident.startedAt).toLocaleString()}</span><span>{incident.resolvedAt ? `Resolved ${new Date(incident.resolvedAt).toLocaleString()}` : "No resolution time recorded"}</span><span>Created by {incident.createdBy ?? "Not recorded"}</span><span>{incident.affectedServices.length ? `Affected: ${incident.affectedServices.join(", ")}` : "Affected services not recorded"}</span></div></details></td>
              </tr>
            ))}</tbody>
          </table></div>
        ) : null}
      </div>
    </>
  );
}
