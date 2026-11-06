const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "/api/v1";

export type HealthResponse = {
  status: string;
  service: string;
  version: string;
  profile: string;
  database: string;
  timestamp: string;
};

export type LoginResponse = {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  username: string;
  role: string;
};

export type RegisteredService = {
  id: string;
  name: string;
  namespace: string;
  environment: string;
  version: string;
  displayName: string;
  description: string;
  owner: string;
  createdAt: string;
  updatedAt: string;
};

export type Capabilities = {
  phase: number;
  telemetryIngestion: boolean;
  anomalyDetection: boolean;
  forecasting: boolean;
  rootCauseAnalysis: boolean;
  explainability: boolean;
  adaptiveControlActuation: boolean;
  incidentPersistence: boolean;
  serviceCatalog: boolean;
};

export type ServiceTelemetry = {
  serviceName: string;
  requestsPerSecond: number;
  errorRate: number;
  p95LatencySeconds: number | null;
};

export type TelemetryOverview = {
  available: boolean;
  hasData: boolean;
  source: string;
  queriedAt: string;
  requestsPerSecond: number | null;
  errorRate: number | null;
  p95LatencySeconds: number | null;
  services: ServiceTelemetry[];
};

export type MetricObservation = {
  timestamp: string;
  service_name: string;
  request_rate: number | null;
  error_rate: number | null;
  p95_latency_seconds: number | null;
};

export type LogEntry = {
  timestamp: string;
  serviceName: string;
  severity: string;
  message: string;
  traceId: string | null;
};

export type TraceSpan = {
  timestamp: string;
  traceId: string;
  spanId: string;
  serviceName: string;
  parentService: string | null;
  operationName: string;
  durationMicros: number;
  tags: Record<string, string>;
};

export type StoredAnomaly = {
  id: string;
  serviceName: string | null;
  detectedAt: string;
  detector: string;
  score: number | null;
  features: Record<string, number | null>;
};

export type StoredForecast = {
  id: string;
  serviceName: string | null;
  metricName: string;
  horizonMinutes: number;
  probability: number;
  threshold: number;
  status: string;
  predictedFor: string;
  features: Record<string, number | null>;
};

export type AnomalyAnalysisResult = {
  model_version: string;
  service_name: string;
  results: Array<{
    timestamp: string;
    service_name: string;
    anomalous: boolean;
    anomaly_score: number;
    score_semantics: string;
    detection_method: string;
    features: Record<string, number | null>;
    observed_metrics: Record<string, number | null>;
    feature_contributions: Array<{ feature_condition: string; contribution: number; method: string }>;
    explanation_method: string | null;
  }>;
  limitations: string[];
  persisted_anomalies: number;
};

export type ForecastAnalysisResult = {
  model_version: string;
  service_name: string;
  as_of: string;
  prediction_horizon_minutes: number;
  target: string;
  degradation_probability: number;
  operational_probability_threshold: number;
  degradation_predicted: boolean;
  features: Record<string, number | null>;
  feature_contributions: Array<{ feature_condition: string; contribution: number; method: string }>;
  explanation_method: string;
  limitations: string[];
  persisted: boolean;
};

export type Incident = {
  id: string;
  title: string;
  status: string;
  severity: string;
  summary: string | null;
  startedAt: string;
  resolvedAt: string | null;
  createdBy: string | null;
  affectedServices: string[];
};

export type RootCauseResult = {
  model_version: string | null;
  incident_timestamp: string;
  candidates: Array<{
    rank: number;
    service_name: string;
    ranking_score: number;
    score_semantics: string;
    evidence: Array<Record<string, unknown>>;
  }>;
  limitations: string[];
  causality_confirmed: boolean;
};

export type AdaptiveAuditEvent = {
  id: string;
  actor: string;
  action: string;
  previousValue: string | null;
  newValue: string | null;
  reason: string;
  createdAt: string;
};

export type AdaptiveControlState = {
  enabled: boolean;
  actuationAvailable: boolean;
  controllerMode: string;
  lastSignalStatus: string;
  lastError: string | null;
  traceSampleRates: Record<string, number>;
  riskLevels: Record<string, string>;
  lastEvaluatedAt: string | null;
  lastChangedAt: string | null;
  cooldownSeconds: number;
  changesInLastHour: number;
  maxChangesPerHour: number;
  minimumTraceSampleRate: number;
  maximumTraceSampleRate: number;
  staleAfterSeconds: number;
  recentAudit: AdaptiveAuditEvent[];
};

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function authHeader(): Record<string, string> {
  const token = localStorage.getItem("aexa.token");
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const text = await response.text();
    let message = text || `Request failed (${response.status})`;
    try {
      const problem = JSON.parse(text) as { title?: string; detail?: string };
      message = [problem.title, problem.detail].filter(Boolean).join(": ") || message;
    } catch {
      // Keep the response text for non-JSON errors.
    }
    throw new ApiError(message, response.status);
  }
  return response.json() as Promise<T>;
}

export const api = {
  health: () => fetch(`${API_BASE}/health`).then((r) => parse<HealthResponse>(r)),
  login: (username: string, password: string) =>
    fetch(`${API_BASE}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password })
    }).then((r) => parse<LoginResponse>(r)),
  services: () =>
    fetch(`${API_BASE}/services`, { headers: authHeader() }).then((r) =>
      parse<{ items: RegisteredService[] }>(r)
    ),
  incidents: () =>
    fetch(`${API_BASE}/incidents`, { headers: authHeader() }).then((r) =>
      parse<{ items: Incident[] }>(r)
    ),
  capabilities: () =>
    fetch(`${API_BASE}/platform/capabilities`, { headers: authHeader() }).then((r) =>
      parse<Capabilities>(r)
    ),
  telemetryOverview: () =>
    fetch(`${API_BASE}/telemetry/overview`, { headers: authHeader() }).then((r) =>
      parse<TelemetryOverview>(r)
    ),
  telemetryMetrics: (serviceName: string, lookbackMinutes = 60) =>
    fetch(
      `${API_BASE}/telemetry/metrics?serviceName=${encodeURIComponent(serviceName)}&lookbackMinutes=${lookbackMinutes}`,
      { headers: authHeader() }
    ).then((r) => parse<MetricObservation[]>(r)),
  telemetryLogs: (serviceName: string, lookbackMinutes = 60, limit = 50) =>
    fetch(
      `${API_BASE}/telemetry/logs?serviceName=${encodeURIComponent(serviceName)}&lookbackMinutes=${lookbackMinutes}&limit=${limit}`,
      { headers: authHeader() }
    ).then((r) => parse<LogEntry[]>(r)),
  telemetryTraces: (serviceName: string, lookbackMinutes = 60, limit = 20) =>
    fetch(
      `${API_BASE}/telemetry/traces?serviceName=${encodeURIComponent(serviceName)}&lookbackMinutes=${lookbackMinutes}&limit=${limit}`,
      { headers: authHeader() }
    ).then((r) => parse<TraceSpan[]>(r)),
  runAnomalyAnalysis: (serviceName: string, lookbackMinutes = 60) =>
    fetch(
      `${API_BASE}/analysis/anomalies/run?serviceName=${encodeURIComponent(serviceName)}&lookbackMinutes=${lookbackMinutes}`,
      { method: "POST", headers: authHeader() }
    ).then((r) => parse<AnomalyAnalysisResult>(r)),
  storedAnomalies: (serviceName?: string) =>
    fetch(
      `${API_BASE}/analysis/anomalies${serviceName ? `?serviceName=${encodeURIComponent(serviceName)}` : ""}`,
      { headers: authHeader() }
    ).then((r) => parse<StoredAnomaly[]>(r)),
  runForecast: (serviceName: string, lookbackMinutes = 60) =>
    fetch(
      `${API_BASE}/analysis/forecasts/run?serviceName=${encodeURIComponent(serviceName)}&lookbackMinutes=${lookbackMinutes}`,
      { method: "POST", headers: authHeader() }
    ).then((r) => parse<ForecastAnalysisResult>(r)),
  storedForecasts: (serviceName?: string) =>
    fetch(
      `${API_BASE}/analysis/forecasts${serviceName ? `?serviceName=${encodeURIComponent(serviceName)}` : ""}`,
      { headers: authHeader() }
    ).then((r) => parse<StoredForecast[]>(r)),
  rootCauseAnalysis: (body: Record<string, unknown>) =>
    fetch(`${API_BASE}/ai/root-causes/rank`, {
      method: "POST",
      headers: { ...authHeader(), "Content-Type": "application/json" },
      body: JSON.stringify(body)
    }).then((r) => parse<RootCauseResult>(r)),
  adaptiveState: () =>
    fetch(`${API_BASE}/adaptive/state`, { headers: authHeader() }).then((r) =>
      parse<AdaptiveControlState>(r)
    ),
  setAdaptiveEnabled: (enabled: boolean) =>
    fetch(`${API_BASE}/adaptive/enabled`, {
      method: "PUT",
      headers: { ...authHeader(), "Content-Type": "application/json" },
      body: JSON.stringify({ enabled })
    }).then((r) => parse<AdaptiveControlState>(r)),
  evaluateAdaptive: () =>
    fetch(`${API_BASE}/adaptive/evaluate`, {
      method: "POST",
      headers: authHeader()
    }).then((r) => parse<AdaptiveControlState>(r)),
  rollbackAdaptive: () =>
    fetch(`${API_BASE}/adaptive/rollback`, {
      method: "POST",
      headers: authHeader()
    }).then((r) => parse<AdaptiveControlState>(r))
};
