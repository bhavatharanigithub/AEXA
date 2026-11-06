import { fireEvent, render, screen } from "@testing-library/react";
import { AuthProvider } from "../auth/AuthContext";
import { AnomaliesPage } from "./AnomaliesPage";
import { InvestigatePage } from "./InvestigatePage";
import { PredictionsPage } from "./PredictionsPage";

const now = new Date().toISOString();
const services = [
  {
    id: "checkout", name: "checkout-api", namespace: "demo", environment: "local",
    version: "0.1.0", displayName: "Checkout", description: "demo", owner: "platform",
    createdAt: now, updatedAt: now
  },
  {
    id: "inventory", name: "inventory-api", namespace: "demo", environment: "local",
    version: "0.1.0", displayName: "Inventory", description: "demo", owner: "platform",
    createdAt: now, updatedAt: now
  }
];

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

function renderAsAdmin(element: React.ReactElement) {
  localStorage.setItem("aexa.token", "test-token");
  localStorage.setItem("aexa.username", "admin");
  localStorage.setItem("aexa.role", "ADMIN");
  return render(<AuthProvider>{element}</AuthProvider>);
}

function renderAsViewer(element: React.ReactElement) {
  localStorage.setItem("aexa.token", "viewer-test-token");
  localStorage.setItem("aexa.username", "viewer");
  localStorage.setItem("aexa.role", "VIEWER");
  return render(<AuthProvider>{element}</AuthProvider>);
}

test("anomaly analysis shows model output and empty persisted history", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo) => {
    const url = String(input);
    if (url.endsWith("/services")) return response({ items: [services[0]] });
    if (url.includes("/analysis/anomalies/run")) {
      return response({
        model_version: "test-model",
        service_name: "checkout-api",
        results: [],
        limitations: ["Synthetic model evaluation only."],
        persisted_anomalies: 0
      });
    }
    if (url.includes("/analysis/anomalies")) return response([]);
    return response({}, 404);
  }));

  renderAsAdmin(<AnomaliesPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Analyze latest window" }));

  expect(await screen.findByText(/Latest model response · test-model/)).toBeInTheDocument();
  expect(screen.getByText(/No anomaly records have been saved/)).toBeInTheDocument();
});

test("forecast panel displays returned horizon and persists the result", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo) => {
    const url = String(input);
    if (url.endsWith("/services")) return response({ items: [services[0]] });
    if (url.includes("/analysis/forecasts/run")) {
      return response({
        model_version: "test-model",
        service_name: "checkout-api",
        as_of: now,
        prediction_horizon_minutes: 5,
        target: "p95 latency >= 1.0 second within five minutes",
        degradation_probability: 0.6,
        operational_probability_threshold: 0.5,
        degradation_predicted: true,
        features: {},
        feature_contributions: [],
        explanation_method: "LIME",
        limitations: [],
        persisted: true
      });
    }
    if (url.includes("/analysis/forecasts")) return response([]);
    return response({}, 404);
  }));

  renderAsAdmin(<PredictionsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Forecast latest window" }));

  expect(await screen.findByText("Degradation predicted")).toBeInTheDocument();
  expect(screen.getByText(/saved to analysis history: yes/)).toBeInTheDocument();
});

test("root-cause investigation deduplicates spans returned in multiple service traces", async () => {
  const requests: Array<{ url: string; body?: string }> = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    requests.push({ url, body: init?.body?.toString() });
    if (url.endsWith("/services")) return response({ items: services });
    if (url.includes("/telemetry/metrics")) {
      const serviceName = url.includes("inventory-api") ? "inventory-api" : "checkout-api";
      return response([{
        timestamp: now, service_name: serviceName, request_rate: 2, error_rate: 0.1,
        p95_latency_seconds: 0.2
      }]);
    }
    if (url.includes("/telemetry/logs")) return response([]);
    if (url.includes("/telemetry/traces")) {
      return response([{
        timestamp: now, traceId: "trace-1", spanId: "span-1",
        serviceName: "inventory-api", parentService: "checkout-api",
        operationName: "GET /work", durationMicros: 10000, tags: {}
      }]);
    }
    if (url.includes("/ai/root-causes/rank")) {
      return response({
        model_version: "test-model",
        incident_timestamp: now,
        candidates: [{
          rank: 1, service_name: "inventory-api", ranking_score: 0.7,
          score_semantics: "Evidence score, not confidence.",
          evidence: [{ type: "trace_dependency", trace_id: "trace-1" }]
        }],
        limitations: ["Ranking does not establish causality."],
        causality_confirmed: false
      });
    }
    return response({}, 404);
  }));

  renderAsAdmin(<InvestigatePage />);
  fireEvent.click(await screen.findByRole("button", { name: "Rank root-cause candidates" }));

  expect(await screen.findByText(/#1 inventory-api/)).toBeInTheDocument();
  const request = requests.find((entry) => entry.url.includes("/ai/root-causes/rank"));
  expect(request).toBeDefined();
  expect(JSON.parse(request?.body ?? "{}").trace_edges).toHaveLength(1);
});

test("viewer can read anomaly history but cannot launch model analysis", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo) => {
    const url = String(input);
    if (url.endsWith("/services")) return response({ items: [services[0]] });
    if (url.includes("/analysis/anomalies")) return response([]);
    return response({}, 404);
  }));

  renderAsViewer(<AnomaliesPage />);

  expect(await screen.findByText(/Read-only role/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Analyze latest window" })).not.toBeInTheDocument();
});
