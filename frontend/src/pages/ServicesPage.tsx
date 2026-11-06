import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api, type RegisteredService } from "../api/client";

type SortKey = "displayName" | "environment" | "version" | "owner";

export function ServicesPage() {
  const [items, setItems] = useState<RegisteredService[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [environment, setEnvironment] = useState("all");
  const [sortKey, setSortKey] = useState<SortKey>("displayName");
  const [ascending, setAscending] = useState(true);

  useEffect(() => {
    api.services()
      .then((r) => setItems(r.items))
      .catch(() => setError("Unable to load the service catalog."));
  }, []);

  const environments = useMemo(() => [...new Set((items ?? []).map((item) => item.environment))].sort(), [items]);
  const filteredItems = useMemo(() => {
    const search = query.trim().toLowerCase();
    return (items ?? [])
      .filter((service) => environment === "all" || service.environment === environment)
      .filter((service) => !search || [service.displayName, service.name, service.description, service.owner].some((value) => value.toLowerCase().includes(search)))
      .sort((left, right) => {
        const order = left[sortKey].localeCompare(right[sortKey], undefined, { sensitivity: "base" });
        return ascending ? order : -order;
      });
  }, [ascending, environment, items, query, sortKey]);

  function sortBy(key: SortKey) {
    if (sortKey === key) setAscending((value) => !value);
    else {
      setSortKey(key);
      setAscending(true);
    }
  }

  function sortHeader(key: SortKey, label: string) {
    const ariaSort = sortKey === key ? ascending ? "ascending" : "descending" : "none";
    return <th aria-sort={ariaSort}><button className="table-sort-button" type="button" onClick={() => sortBy(key)}>{label}<span aria-hidden="true">{sortKey === key ? ascending ? " ↑" : " ↓" : " ↕"}</span></button></th>;
  }

  return (
    <>
      <div className="page-head">
        <div><h1>Service explorer</h1><p className="muted">Registered service identities and metadata. Catalog presence does not indicate live health.</p></div>
      </div>
      {error ? <p className="error" role="alert">{error}</p> : null}
      <div className="card">
        <div className="explorer-toolbar">
          <label className="explorer-search"><span aria-hidden="true">⌕</span><input aria-label="Search services" placeholder="Search name, owner, or description" value={query} onChange={(event) => setQuery(event.currentTarget.value)} /></label>
          <label className="explorer-filter">Environment<select value={environment} onChange={(event) => setEnvironment(event.currentTarget.value)}><option value="all">All environments</option>{environments.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
          <span className="explorer-count">{items === null ? "Loading services…" : `${filteredItems.length} of ${items.length} services`}</span>
        </div>
        {items === null && !error ? <p className="muted" role="status">Loading registered service identities…</p> : null}
        {items?.length === 0 && !error ? <p className="empty">No services are registered in the catalog.</p> : null}
        {items?.length && !filteredItems.length ? <p className="empty">No services match these search and environment filters.</p> : null}
        {!!filteredItems.length ? <div className="table-scroll"><table className="table services-table">
          <thead><tr>{sortHeader("displayName", "Service")}{sortHeader("environment", "Environment")}{sortHeader("version", "Version")}{sortHeader("owner", "Owner")}<th>Description</th><th>Explore</th></tr></thead>
          <tbody>{filteredItems.map((service: RegisteredService) => (
            <tr key={service.id}>
              <td><span className="service-name-mark">{service.displayName.slice(0, 1).toUpperCase()}</span><strong>{service.displayName}<small>{service.name}</small></strong></td>
              <td><span className="environment-label">{service.environment}</span></td>
              <td>{service.version || "—"}</td><td>{service.owner || "—"}</td><td>{service.description || "—"}</td>
              <td><Link className="table-action-link" to={`/telemetry?serviceName=${encodeURIComponent(service.name)}`}>Telemetry ↗</Link></td>
            </tr>
          ))}</tbody>
        </table></div> : null}
      </div>
    </>
  );
}
