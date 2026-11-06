import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "./App";

beforeEach(() => {
  localStorage.clear();
});

test("unauthenticated visitors see the public landing page at the root route", () => {
  render(<MemoryRouter initialEntries={["/"]}><App /></MemoryRouter>);
  expect(screen.getByRole("heading", { name: /See the signal\./ })).toBeInTheDocument();
  const signInLink = screen.getAllByRole("link").find((link) => link.textContent?.trim() === "Sign in");
  expect(signInLink).toHaveAttribute("href", "/login");
});

test("protected operational routes redirect unauthenticated visitors to sign in", async () => {
  render(<MemoryRouter initialEntries={["/services"]}><App /></MemoryRouter>);
  expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
});
