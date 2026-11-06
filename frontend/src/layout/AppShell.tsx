import { useMemo, useState, type KeyboardEvent } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";

const sections = [
  { title: "OVERVIEW", links: [{ to: "/", label: "Overview", icon: "O" }] },
  { title: "EXPLORE", links: [{ to: "/services", label: "Services", icon: "S" }, { to: "/telemetry", label: "Metrics · logs · traces", icon: "T" }] },
  { title: "INTELLIGENCE", links: [{ to: "/anomalies", label: "Anomalies", icon: "A" }, { to: "/predictions", label: "Predictions", icon: "P" }, { to: "/investigate", label: "Root-cause analysis", icon: "R" }] },
  { title: "OPERATIONS", links: [{ to: "/incidents", label: "Incidents", icon: "I" }, { to: "/adaptive", label: "Adaptive telemetry", icon: "C" }] },
  { title: "ADMINISTRATION", links: [{ to: "/reports", label: "Reports & evaluation", icon: "E" }, { to: "/settings", label: "System settings", icon: "⚙" }] }
];

export function AppShell() {
  const { user, logout } = useAuth();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [search, setSearch] = useState("");
  const pageLinks = useMemo(() => sections.flatMap((section) => section.links), []);
  const currentPage = pageLinks.find((link) => link.to === pathname)?.label ?? "Overview";

  function navigateFromSearch(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== "Enter") return;
    const query = search.trim().toLowerCase();
    const match = pageLinks.find((link) => link.label.toLowerCase() === query || link.to.toLowerCase() === query);
    if (match) {
      navigate(match.to);
      setSearch("");
      setMobileOpen(false);
    }
  }

  return (
    <div className="app-frame">
      <div className={`shell${collapsed ? " shell-collapsed" : ""}${mobileOpen ? " shell-mobile-open" : ""}`}>
        {mobileOpen ? <button className="mobile-backdrop" type="button" aria-label="Close navigation" onClick={() => setMobileOpen(false)} /> : null}
        <aside className="sidebar" aria-label="Application navigation">
          <div className="sidebar-brand-row">
            <Link className="brand" to="/" aria-label="Aexa home"><span className="brand-mark" aria-hidden="true" />Aexa</Link>
            <button className="collapse-nav" type="button" aria-label={mobileOpen ? "Close navigation" : collapsed ? "Expand sidebar" : "Collapse sidebar"} aria-expanded={mobileOpen || !collapsed} onClick={() => {
              if (window.innerWidth <= 900) setMobileOpen(false);
              else setCollapsed((value) => !value);
            }}>‹</button>
          </div>
          <div className="workspace-switch"><span className="workspace-avatar">A</span><span><strong>Observability</strong><small>Local workspace</small></span><i>⌄</i></div>
          <nav className="sidebar-navigation" aria-label="Workspace pages">
            {sections.map((section) => (
              <div className="nav-section" key={section.title}>
                <div className="nav-label">{section.title}</div>
                {section.links.map((link) => (
                  <NavLink key={link.to} to={link.to} end={link.to === "/"} title={collapsed ? link.label : undefined} onClick={() => setMobileOpen(false)} className={({ isActive }) => `nav-link${isActive ? " active" : ""}`}>
                    <span className="nav-icon" aria-hidden="true">{link.icon}</span><span className="nav-link-label">{link.label}</span>
                  </NavLink>
                ))}
              </div>
            ))}
          </nav>
          <div className="sidebar-bottom"><span className="environment-indicator"><i /> LOCAL ENVIRONMENT</span><small>Aexa local workspace</small></div>
        </aside>
        <section className="main">
          <header className="topbar">
            <button className="mobile-nav-toggle" type="button" aria-label={mobileOpen ? "Close navigation" : "Open navigation"} aria-expanded={mobileOpen} onClick={() => setMobileOpen((value) => !value)}>{mobileOpen ? "×" : "☰"}</button>
            <div className="breadcrumbs"><span>Workspace</span><b>/</b><strong>{currentPage}</strong></div>
            <label className="search-control"><span aria-hidden="true">⌕</span><input aria-label="Search application pages" list="app-page-search" placeholder="Search pages…" value={search} onChange={(event) => setSearch(event.currentTarget.value)} onKeyDown={navigateFromSearch} /><datalist id="app-page-search">{pageLinks.map((link) => <option key={link.to} value={link.label} />)}</datalist><kbd>↵</kbd></label>
            <div className="top-actions">
              <span className="environment-chip"><i />LOCAL</span>
              <div className="user-chip">
                <span className="avatar" aria-hidden="true" />
                <span><strong>{user?.username ?? "Guest"}</strong><small>{user?.role ?? "VIEWER"}</small></span>
              </div>
              <button className="ghost-btn signout-button" type="button" onClick={logout}>Sign out</button>
            </div>
          </header>
          <div className="page-content"><Outlet /></div>
        </section>
      </div>
    </div>
  );
}
