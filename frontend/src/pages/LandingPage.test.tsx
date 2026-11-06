import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AuthProvider } from "../auth/AuthContext";
import { LandingPage } from "./LandingPage";

beforeEach(() => {
  localStorage.clear();
});

test("landing page explains the verified platform without presenting demo data as live", () => {
  render(
    <MemoryRouter>
      <AuthProvider>
        <LandingPage />
      </AuthProvider>
    </MemoryRouter>
  );

  expect(screen.getByRole("heading", { name: /See the signal\./ })).toBeInTheDocument();
  expect(screen.getByText("TECHNOLOGIES CONFIGURED IN THIS PROJECT")).toBeInTheDocument();
  expect(screen.getByText("DEMO DATA · NOT LIVE")).toBeInTheDocument();
  expect(screen.getByText(/does not call APIs or change collector settings/)).toBeInTheDocument();
  const signInLink = screen.getAllByRole("link").find((link) => link.textContent?.trim() === "Sign in");
  expect(signInLink).toHaveAttribute("href", "/login");
});

test("workflow stages update their evidence details", () => {
  render(
    <MemoryRouter>
      <AuthProvider>
        <LandingPage />
      </AuthProvider>
    </MemoryRouter>
  );

  fireEvent.click(screen.getAllByRole("button", { name: /Collection OpenTelemetry/ })[0]);
  expect(screen.getByRole("heading", { name: "Collection" })).toBeInTheDocument();
  expect(screen.getByText("OTLP metrics, traces, and logs")).toBeInTheDocument();
  expect(screen.getByText("Processed and routed telemetry")).toBeInTheDocument();
});

test("showcase tabs and service selection are interactive and clearly illustrative", () => {
  render(
    <MemoryRouter>
      <AuthProvider>
        <LandingPage />
      </AuthProvider>
    </MemoryRouter>
  );

  const showcase = screen.getByRole("heading", { name: "Dependency overview" }).closest(".showcase-window");
  if (!(showcase instanceof HTMLElement)) throw new Error("Showcase container was not rendered.");
  fireEvent.click(within(showcase).getByRole("tab", { name: "Trace" }));
  expect(within(showcase).getByRole("heading", { name: "Distributed trace" })).toBeInTheDocument();
  const serviceSelect = within(showcase).getByLabelText("Service");
  fireEvent.change(serviceSelect, { target: { value: "payment-service" } });
  expect(serviceSelect).toHaveValue("payment-service");
  expect(within(showcase).getByText("DEMO DATA · NOT LIVE")).toBeInTheDocument();
});
