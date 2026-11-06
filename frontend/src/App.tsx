import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { AppShell } from "./layout/AppShell";
import { AdaptivePage } from "./pages/AdaptivePage";
import { IncidentsPage } from "./pages/IncidentsPage";
import { AnomaliesPage } from "./pages/AnomaliesPage";
import { InvestigatePage } from "./pages/InvestigatePage";
import { LoginPage } from "./pages/LoginPage";
import { LandingPage } from "./pages/LandingPage";
import { OverviewPage } from "./pages/OverviewPage";
import { PredictionsPage } from "./pages/PredictionsPage";
import { ReportsPage } from "./pages/ReportsPage";
import { ServicesPage } from "./pages/ServicesPage";
import { SettingsPage } from "./pages/SettingsPage";
import { TelemetryPage } from "./pages/TelemetryPage";

function RootLayout() {
  const { user } = useAuth();
  const { pathname } = useLocation();

  if (pathname === "/" && !user) return <LandingPage />;
  if (!user) return <Navigate to="/login" replace />;
  return <AppShell />;
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<RootLayout />}>
        <Route index element={<OverviewPage />} />
        <Route path="services" element={<ServicesPage />} />
        <Route path="telemetry" element={<TelemetryPage />} />
        <Route path="incidents" element={<IncidentsPage />} />
        <Route path="anomalies" element={<AnomaliesPage />} />
        <Route path="predictions" element={<PredictionsPage />} />
        <Route path="investigate" element={<InvestigatePage />} />
        <Route path="adaptive" element={<AdaptivePage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="settings" element={<SettingsPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <AppRoutes />
    </AuthProvider>
  );
}
