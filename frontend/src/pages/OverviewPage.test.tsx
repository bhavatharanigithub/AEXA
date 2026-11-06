import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { OverviewPage } from "./OverviewPage";

test("overview displays measured Prometheus telemetry", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo) => {
      const url = String(input);
      if (url.includes("/health")) {
        return new Response(
          JSON.stringify({
            status: "UP",
            service: "aexa-backend",
            version: "0.1.0",
            profile: "local",
            database: "UP",
            timestamp: "2026-10-26T00:00:00Z"
          }),
          { status: 200 }
        );
      }
      if (url.includes("/telemetry/overview")) {
        return new Response(
          JSON.stringify({
            available: true,
            hasData: true,
            source: "prometheus",
            queriedAt: "2026-10-26T00:00:00Z",
            requestsPerSecond: 3.5,
            errorRate: 0.0571,
            p95LatencySeconds: 0.12,
            services: [
              {
                serviceName: "checkout-api",
                requestsPerSecond: 2,
                errorRate: 0.1,
                p95LatencySeconds: 0.15
              }
            ]
          }),
          { status: 200 }
        );
      }
      if (url.includes("/incidents")) {
        return new Response(JSON.stringify({ items: [] }), { status: 200 });
      }
      return new Response(
        JSON.stringify({
          phase: 2,
          telemetryIngestion: true,
          anomalyDetection: false,
          forecasting: false,
          rootCauseAnalysis: false,
          explainability: false,
          adaptiveControlActuation: false,
          incidentPersistence: true,
          serviceCatalog: true
        }),
        { status: 200 }
      );
    })
  );

  render(<MemoryRouter><OverviewPage /></MemoryRouter>);
  expect(screen.getByText("Welcome to Aexa")).toBeInTheDocument();
  expect(await screen.findByText((_, element) => element?.textContent === "Database UP")).toBeInTheDocument();
  expect(await screen.findByText("3.50/s")).toBeInTheDocument();
  expect(screen.getByText("5.71%")).toBeInTheDocument();
  expect(screen.getByText("120.0 ms")).toBeInTheDocument();
  expect(screen.getByText("checkout-api")).toBeInTheDocument();
});

test("overview labels a reachable Prometheus with no samples without inventing values", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo) => {
      const url = String(input);
      if (url.includes("/health")) {
        return new Response(
          JSON.stringify({
            status: "UP",
            service: "aexa-backend",
            version: "0.1.0",
            profile: "local",
            database: "UP",
            timestamp: "2026-10-26T00:00:00Z"
          }),
          { status: 200 }
        );
      }
      if (url.includes("/telemetry/overview")) {
        return new Response(
          JSON.stringify({
            available: true,
            hasData: false,
            source: "prometheus",
            queriedAt: "2026-10-26T00:00:00Z",
            requestsPerSecond: null,
            errorRate: null,
            p95LatencySeconds: null,
            services: []
          }),
          { status: 200 }
        );
      }
      if (url.includes("/incidents")) {
        return new Response(JSON.stringify({ items: [] }), { status: 200 });
      }
      return new Response(
        JSON.stringify({
          phase: 2,
          telemetryIngestion: true,
          anomalyDetection: false,
          forecasting: false,
          rootCauseAnalysis: false,
          explainability: false,
          adaptiveControlActuation: false,
          incidentPersistence: true,
          serviceCatalog: true
        }),
        { status: 200 }
      );
    })
  );

  render(<MemoryRouter><OverviewPage /></MemoryRouter>);
  expect(await screen.findByText("Prometheus is reachable, but no request metrics have arrived yet.")).toBeInTheDocument();
  expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(3);
});

test("overview explains how to start Prometheus when telemetry API returns 503", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo) => {
      const url = String(input);
      if (url.includes("/health")) {
        return new Response(
          JSON.stringify({
            status: "UP",
            service: "aexa-backend",
            version: "0.1.0",
            profile: "local",
            database: "UP",
            timestamp: "2026-10-26T00:00:00Z"
          }),
          { status: 200 }
        );
      }
      if (url.includes("/telemetry/overview")) {
        return new Response(
          JSON.stringify({
            title: "Telemetry unavailable",
            detail: "Prometheus could not be reached",
            status: 503
          }),
          { status: 503 }
        );
      }
      if (url.includes("/incidents")) {
        return new Response(JSON.stringify({ items: [] }), { status: 200 });
      }
      return new Response(
        JSON.stringify({
          phase: 2,
          telemetryIngestion: true,
          anomalyDetection: false,
          forecasting: false,
          rootCauseAnalysis: false,
          explainability: false,
          adaptiveControlActuation: false,
          incidentPersistence: true,
          serviceCatalog: true
        }),
        { status: 200 }
      );
    })
  );

  render(<MemoryRouter><OverviewPage /></MemoryRouter>);
  expect(
    await screen.findByText(/Prometheus is unavailable\. Start Docker Desktop/)
  ).toBeInTheDocument();
  expect(screen.getByText(/docker compose -f infra\\docker-compose\.yml up -d --build/)).toBeInTheDocument();
});
