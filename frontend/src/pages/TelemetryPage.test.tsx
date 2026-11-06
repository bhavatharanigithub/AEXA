import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { TelemetryPage } from "./TelemetryPage";

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status });
}

test("telemetry explorer loads measured metrics, Loki logs, and Jaeger traces", async () => {
  const now = new Date().toISOString();
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo) => {
    const url = String(input);
    if (url.endsWith("/services")) {
      return json({ items: [{
        id: "service-1",
        name: "checkout-api",
        namespace: "demo",
        environment: "local",
        version: "0.1.0",
        displayName: "Checkout",
        description: "Demo service",
        owner: "platform",
        createdAt: now,
        updatedAt: now
      }] });
    }
    if (url.includes("/telemetry/metrics")) {
      return json([
        { timestamp: now, service_name: "checkout-api", request_rate: 2, error_rate: 0, p95_latency_seconds: 0.2 },
        { timestamp: now, service_name: "checkout-api", request_rate: 3, error_rate: 0.01, p95_latency_seconds: 0.3 }
      ]);
    }
    if (url.includes("/telemetry/logs")) {
      return json([{ timestamp: now, serviceName: "checkout-api", severity: "INFO", message: "request completed", traceId: "trace-1" }]);
    }
    if (url.includes("/telemetry/traces")) {
      return json([{
        timestamp: now,
        traceId: "trace-1",
        spanId: "span-1",
        serviceName: "checkout-api",
        parentService: null,
        operationName: "GET /work",
        durationMicros: 12000,
        tags: {}
      }]);
    }
    return json({}, 404);
  }));

  render(<MemoryRouter><TelemetryPage /></MemoryRouter>);

  expect(await screen.findByText(/Measured p95 latency/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("tab", { name: "Logs" }));
  expect(await screen.findByText("request completed")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("tab", { name: "Traces" }));
  expect(await screen.findByText("GET /work")).toBeInTheDocument();
  await waitFor(() => expect(screen.getByText("trace-1…")).toBeInTheDocument());
});

test("telemetry explorer reports an empty result without inventing samples", async () => {
  const now = new Date().toISOString();
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo) => {
    const url = String(input);
    if (url.endsWith("/services")) {
      return json({ items: [{
        id: "service-1", name: "checkout-api", namespace: "demo", environment: "local",
        version: "0.1.0", displayName: "Checkout", description: "Demo service",
        owner: "platform", createdAt: now, updatedAt: now
      }] });
    }
    if (url.includes("/telemetry/metrics")) return json([]);
    return json([], 200);
  }));

  render(<MemoryRouter><TelemetryPage /></MemoryRouter>);

  expect(await screen.findByText(/No Prometheus samples are available/)).toBeInTheDocument();
  expect(screen.queryByRole("img", { name: "Measured p95 latency over time" })).not.toBeInTheDocument();
});

test("telemetry explorer identifies stale metric samples", async () => {
  const staleTimestamp = new Date(Date.now() - 5 * 60_000).toISOString();
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo) => {
    const url = String(input);
    if (url.endsWith("/services")) {
      return json({ items: [{
        id: "service-1", name: "checkout-api", namespace: "demo", environment: "local",
        version: "0.1.0", displayName: "Checkout", description: "Demo service",
        owner: "platform", createdAt: staleTimestamp, updatedAt: staleTimestamp
      }] });
    }
    if (url.includes("/telemetry/metrics")) {
      return json([{
        timestamp: staleTimestamp,
        service_name: "checkout-api",
        request_rate: 2,
        error_rate: 0,
        p95_latency_seconds: 0.1
      }]);
    }
    return json([], 200);
  }));

  render(<MemoryRouter><TelemetryPage /></MemoryRouter>);

  expect(await screen.findByText(/Latest metric sample is older than two minutes/)).toBeInTheDocument();
});

test("telemetry explorer surfaces telemetry API failures", async () => {
  const now = new Date().toISOString();
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo) => {
    const url = String(input);
    if (url.endsWith("/services")) {
      return json({ items: [{
        id: "service-1", name: "checkout-api", namespace: "demo", environment: "local",
        version: "0.1.0", displayName: "Checkout", description: "Demo service",
        owner: "platform", createdAt: now, updatedAt: now
      }] });
    }
    if (url.includes("/telemetry/metrics")) {
      return json({ title: "Telemetry unavailable", detail: "Prometheus could not be reached", status: 503 }, 503);
    }
    return json([], 200);
  }));

  render(<MemoryRouter><TelemetryPage /></MemoryRouter>);

  expect(await screen.findByRole("alert")).toHaveTextContent("Telemetry unavailable: Prometheus could not be reached (HTTP 503)");
});
