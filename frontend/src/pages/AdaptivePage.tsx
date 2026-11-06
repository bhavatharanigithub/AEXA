import { useCallback, useEffect, useState } from "react";
import { ApiError, api, type AdaptiveControlState } from "../api/client";
import { useAuth } from "../auth/AuthContext";

function formatRate(rate: number): string {
  return `${(rate * 100).toFixed(0)}%`;
}

function formatTimestamp(timestamp: string | null): string {
  return timestamp ? new Date(timestamp).toLocaleString() : "Not yet";
}

export function AdaptivePage() {
  const { user } = useAuth();
  const [state, setState] = useState<AdaptiveControlState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setState(await api.adaptiveState());
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to load adaptive-control state.");
    }
  }, []);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), 15000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  async function runAction(action: () => Promise<AdaptiveControlState>) {
    setBusy(true);
    setError(null);
    try {
      setState(await action());
    } catch (cause) {
      const message =
        cause instanceof ApiError && cause.status === 403
          ? "Administrator role is required for adaptive-control changes."
          : cause instanceof Error
            ? cause.message
            : "Adaptive-control request failed.";
      await refresh();
      setError(message);
    } finally {
      setBusy(false);
    }
  }

  const isAdmin = user?.role === "ADMIN";
  const rates = state?.traceSampleRates ?? {};
  const risks = state?.riskLevels ?? {};

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Adaptive telemetry control</h1>
          <p className="muted">Bounded runtime trace sampling based on fresh telemetry risk signals.</p>
        </div>
        <span className="chip">{state?.controllerMode ?? "Loading"}</span>
      </div>

      {error ? <p className="error" role="alert">{error}</p> : null}
      {!state && !error ? <p className="muted">Loading adaptive-control state…</p> : null}

      {state ? (
        <>
          <div className="adaptive-grid">
            <section className="card adaptive-status">
              <div>
                <p className="muted">Controller</p>
                <strong>{state.enabled ? "Enabled" : "Disabled"}</strong>
              </div>
              <div>
                <p className="muted">Actuator</p>
                <strong>{state.actuationAvailable ? "Available" : "Unavailable"}</strong>
              </div>
              <div>
                <p className="muted">Signal freshness</p>
                <strong>{state.lastSignalStatus}</strong>
              </div>
              <div>
                <p className="muted">Last evaluation</p>
                <strong>{formatTimestamp(state.lastEvaluatedAt)}</strong>
              </div>
              {state.lastError ? <p className="error adaptive-wide">{state.lastError}</p> : null}
            </section>

            <section className="card">
              <h2>Service sampling</h2>
              {Object.keys(rates).length ? (
                <div className="adaptive-services">
                  {Object.entries(rates).map(([service, rate]) => (
                    <div className="adaptive-service" key={service}>
                      <div>
                        <strong>{service}</strong>
                        <span className={`risk-tag risk-${(risks[service] ?? "unknown").toLowerCase()}`}>
                          {risks[service] ?? "UNKNOWN"}
                        </span>
                      </div>
                      <div className="rate-track" aria-label={`${service} trace sample rate`}>
                        <span style={{ width: formatRate(rate) }} />
                      </div>
                      <strong>{formatRate(rate)}</strong>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="muted">No demo-service sampler state has been reported yet.</p>
              )}
            </section>
          </div>

          <section className="card adaptive-controls">
            <div>
              <h2>Controller controls</h2>
              <p className="muted">
                Only administrators can actuate sampling. Disable or rollback requests the safe full-sampling
                baseline. Risk targets are 10% (low), 50% (elevated), and 100% (critical); rate reductions wait
                for three safer evaluations plus cooldown and change-limit checks.
              </p>
            </div>
            {isAdmin ? (
              <div className="adaptive-buttons">
                <label className="adaptive-switch">
                  <input
                    type="checkbox"
                    role="switch"
                    checked={state.enabled}
                    disabled={busy}
                    onChange={(event) =>
                      void runAction(() => api.setAdaptiveEnabled(event.currentTarget.checked))
                    }
                  />
                  <span>{state.enabled ? "Adaptive control on" : "Adaptive control off"}</span>
                </label>
                <button
                  className="primary-btn"
                  disabled={busy || !state.enabled}
                  onClick={() => void runAction(api.evaluateAdaptive)}
                >
                  Evaluate now
                </button>
                <button
                  className="ghost-btn"
                  disabled={busy}
                  onClick={() => void runAction(api.rollbackAdaptive)}
                >
                  Roll back to 100%
                </button>
              </div>
            ) : (
              <p className="muted">Read-only view. Ask an administrator to change collection controls.</p>
            )}
          </section>

          <section className="card adaptive-limits">
            <h2>Safety limits</h2>
            <dl>
              <div><dt>Sampling bounds</dt><dd>{formatRate(state.minimumTraceSampleRate)} – {formatRate(state.maximumTraceSampleRate)}</dd></div>
              <div><dt>Cooldown</dt><dd>{state.cooldownSeconds}s</dd></div>
              <div><dt>Automatic changes this hour</dt><dd>{state.changesInLastHour} / {state.maxChangesPerHour}</dd></div>
              <div><dt>Stale after</dt><dd>{state.staleAfterSeconds}s</dd></div>
              <div><dt>Last change</dt><dd>{formatTimestamp(state.lastChangedAt)}</dd></div>
            </dl>
            <p className="muted">
              This controller adjusts demo-service OpenTelemetry trace sampling only. Prometheus scrape intervals
              remain fixed; sample bounds do not guarantee CPU or storage savings.
            </p>
          </section>

          <section className="card">
            <h2>Recent audit history</h2>
            {state.recentAudit.length ? (
              <div className="table-scroll">
                <table className="adaptive-audit">
                  <thead>
                    <tr><th>Time</th><th>Actor</th><th>Action</th><th>Change</th><th>Reason</th></tr>
                  </thead>
                  <tbody>
                    {state.recentAudit.map((entry) => (
                      <tr key={entry.id}>
                        <td>{formatTimestamp(entry.createdAt)}</td>
                        <td>{entry.actor}</td>
                        <td>{entry.action}</td>
                        <td>{entry.previousValue ?? "—"} → {entry.newValue ?? "—"}</td>
                        <td>{entry.reason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="muted">No adaptive-control actions have been audited yet.</p>
            )}
          </section>
        </>
      ) : null}
    </>
  );
}
