import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider } from "../auth/AuthContext";
import { AdaptivePage } from "./AdaptivePage";

const state = {
  enabled: false,
  actuationAvailable: true,
  controllerMode: "DETERMINISTIC",
  lastSignalStatus: "FRESH",
  lastError: null,
  traceSampleRates: { "checkout-api": 0.5 },
  riskLevels: { "checkout-api": "ELEVATED" },
  lastEvaluatedAt: "2026-10-26T00:00:00Z",
  lastChangedAt: null,
  cooldownSeconds: 60,
  changesInLastHour: 1,
  maxChangesPerHour: 8,
  minimumTraceSampleRate: 0.05,
  maximumTraceSampleRate: 1,
  staleAfterSeconds: 120,
  recentAudit: [
    {
      id: "audit-1",
      actor: "admin",
      action: "MANUAL_DISABLE",
      previousValue: "true",
      newValue: "false",
      reason: "Safety test",
      createdAt: "2026-10-26T00:00:00Z"
    }
  ]
};

function renderPage(role: string) {
  localStorage.setItem("aexa.token", "test-token");
  localStorage.setItem("aexa.username", "test-user");
  localStorage.setItem("aexa.role", role);
  return render(
    <AuthProvider>
      <AdaptivePage />
    </AuthProvider>
  );
}

test("adaptive page shows measured sampler state and admin audit controls", async () => {
  const fetchMock = vi.fn(async (input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith("/adaptive/enabled") && init?.method === "PUT") {
      return new Response(JSON.stringify({ ...state, enabled: true }), { status: 200 });
    }
    return new Response(JSON.stringify(state), { status: 200 });
  });
  vi.stubGlobal("fetch", fetchMock);

  renderPage("ADMIN");

  expect(await screen.findByText("checkout-api")).toBeInTheDocument();
  expect(screen.getByText("50%")).toBeInTheDocument();
  expect(screen.getByText("Safety test")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("switch"));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
    expect.stringContaining("/adaptive/enabled"),
    expect.objectContaining({ method: "PUT" })
  ));
  expect(await screen.findByText("Adaptive control on")).toBeInTheDocument();
});

test("adaptive page remains read-only for non-admin users", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(state), { status: 200 }))
  );

  renderPage("ANALYST");

  expect(await screen.findByText(/Read-only view/)).toBeInTheDocument();
  expect(screen.queryByRole("switch")).not.toBeInTheDocument();
});
