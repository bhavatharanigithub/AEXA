import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ServicesPage } from "./ServicesPage";

test("service explorer searches registered metadata and links to the selected service telemetry", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
    items: [
      { id: "1", name: "checkout-api", displayName: "Checkout API", environment: "local", version: "1.2.0", description: "Order checkout", owner: "commerce", namespace: "demo", createdAt: "", updatedAt: "" },
      { id: "2", name: "catalog-api", displayName: "Catalog API", environment: "staging", version: "2.0.0", description: "Product catalog", owner: "store", namespace: "demo", createdAt: "", updatedAt: "" }
    ]
  }), { status: 200 })));

  render(<MemoryRouter><ServicesPage /></MemoryRouter>);
  expect(await screen.findByText("Checkout API")).toBeInTheDocument();
  expect(screen.getByText("Catalog API")).toBeInTheDocument();

  fireEvent.change(screen.getByLabelText("Search services"), { target: { value: "commerce" } });
  expect(screen.getByText("Checkout API")).toBeInTheDocument();
  expect(screen.queryByText("Catalog API")).not.toBeInTheDocument();
  expect(within(screen.getByRole("table")).getByRole("link", { name: /Telemetry/ })).toHaveAttribute("href", "/telemetry?serviceName=checkout-api");
});

test("service explorer filters by environment", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
    items: [
      { id: "1", name: "checkout-api", displayName: "Checkout API", environment: "local", version: "1.2.0", description: "Order checkout", owner: "commerce", namespace: "demo", createdAt: "", updatedAt: "" },
      { id: "2", name: "catalog-api", displayName: "Catalog API", environment: "staging", version: "2.0.0", description: "Product catalog", owner: "store", namespace: "demo", createdAt: "", updatedAt: "" }
    ]
  }), { status: 200 })));

  render(<MemoryRouter><ServicesPage /></MemoryRouter>);
  await screen.findByText("Checkout API");
  fireEvent.change(screen.getByLabelText("Environment"), { target: { value: "staging" } });
  expect(screen.queryByText("Checkout API")).not.toBeInTheDocument();
  expect(screen.getByText("Catalog API")).toBeInTheDocument();
});
