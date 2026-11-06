from datetime import datetime, timezone
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class StrictRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MetricObservation(StrictRequest):
    timestamp: datetime
    service_name: Annotated[str, Field(min_length=1, max_length=128)]
    request_rate: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None
    error_rate: Annotated[float | None, Field(ge=0, le=1, allow_inf_nan=False)] = None
    p95_latency_seconds: Annotated[float | None, Field(ge=0, allow_inf_nan=False)] = None

    @field_validator("timestamp")
    @classmethod
    def timestamp_must_be_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("timestamp must include a timezone")
        return value.astimezone(timezone.utc)

    @model_validator(mode="after")
    def require_observed_metric(self) -> "MetricObservation":
        if all(value is None for value in (self.request_rate, self.error_rate, self.p95_latency_seconds)):
            raise ValueError("at least one metric value must be observed")
        return self


class MetricWindowRequest(StrictRequest):
    observations: Annotated[list[MetricObservation], Field(min_length=6, max_length=5000)]


class TraceEdge(StrictRequest):
    timestamp: datetime
    parent_service: Annotated[str, Field(min_length=1, max_length=128)]
    child_service: Annotated[str, Field(min_length=1, max_length=128)]
    trace_id: Annotated[str, Field(min_length=1, max_length=128)]
    call_count: Annotated[int, Field(ge=1, le=1_000_000)] = 1

    @field_validator("timestamp")
    @classmethod
    def timestamp_must_be_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("timestamp must include a timezone")
        return value.astimezone(timezone.utc)


class LogEvidence(StrictRequest):
    timestamp: datetime
    service_name: Annotated[str, Field(min_length=1, max_length=128)]
    severity: Annotated[str, Field(min_length=1, max_length=32)]
    message: Annotated[str, Field(min_length=1, max_length=2000)]
    trace_id: Annotated[str | None, Field(max_length=128)] = None

    @field_validator("timestamp")
    @classmethod
    def timestamp_must_be_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("timestamp must include a timezone")
        return value.astimezone(timezone.utc)


class ServiceMetadata(StrictRequest):
    service_name: Annotated[str, Field(min_length=1, max_length=128)]
    namespace: Annotated[str | None, Field(max_length=128)] = None
    environment: Annotated[str | None, Field(max_length=128)] = None
    version: Annotated[str | None, Field(max_length=128)] = None


class RootCauseRequest(StrictRequest):
    incident_timestamp: datetime
    observations: Annotated[list[MetricObservation], Field(min_length=1, max_length=5000)]
    trace_edges: Annotated[list[TraceEdge], Field(max_length=2000)]
    logs: Annotated[list[LogEvidence], Field(max_length=2000)]
    service_metadata: Annotated[list[ServiceMetadata], Field(min_length=1, max_length=500)]

    @field_validator("incident_timestamp")
    @classmethod
    def incident_timestamp_must_be_timezone_aware(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("incident_timestamp must include a timezone")
        return value.astimezone(timezone.utc)

    @model_validator(mode="after")
    def require_observations_not_after_incident(self) -> "RootCauseRequest":
        if any(row.timestamp > self.incident_timestamp for row in self.observations):
            raise ValueError("observations cannot be later than incident_timestamp")
        if any(edge.timestamp > self.incident_timestamp for edge in self.trace_edges):
            raise ValueError("trace_edges cannot be later than incident_timestamp")
        if any(log.timestamp > self.incident_timestamp for log in self.logs):
            raise ValueError("logs cannot be later than incident_timestamp")
        known_services = {service.service_name for service in self.service_metadata}
        evidence_services = {row.service_name for row in self.observations}
        evidence_services.update(log.service_name for log in self.logs)
        evidence_services.update(edge.parent_service for edge in self.trace_edges)
        evidence_services.update(edge.child_service for edge in self.trace_edges)
        if not evidence_services.issubset(known_services):
            raise ValueError("service_metadata must include every service referenced by telemetry evidence")
        if len(known_services) > 200:
            raise ValueError("service_metadata cannot contain more than 200 unique services")
        return self


class AnomalyRequest(MetricWindowRequest):
    service_name: Annotated[str, Field(min_length=1, max_length=128)]

    @model_validator(mode="after")
    def observations_must_match_service(self) -> "AnomalyRequest":
        if any(row.service_name != self.service_name for row in self.observations):
            raise ValueError("all observations must belong to service_name")
        return self


class ForecastRequest(AnomalyRequest):
    pass


class StrictResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")


class FeatureContribution(StrictResponse):
    feature_condition: str
    contribution: float
    method: str


class AnomalyResult(StrictResponse):
    timestamp: datetime
    service_name: str
    anomalous: bool
    anomaly_score: float
    score_semantics: str
    threshold: float
    detection_method: str
    features: dict[str, float | None]
    observed_metrics: dict[str, float | None]
    imputed_metrics: list[str]
    model_output: dict[str, float]
    feature_contributions: list[FeatureContribution]
    explanation_method: str | None


class AnomalyResponse(StrictResponse):
    model_version: str
    service_name: str
    results: list[AnomalyResult]
    limitations: list[str]


class ForecastResponse(StrictResponse):
    model_version: str
    service_name: str
    as_of: datetime
    prediction_horizon_minutes: int
    target: str
    degradation_probability: Annotated[float, Field(ge=0, le=1)]
    operational_probability_threshold: Annotated[float, Field(ge=0, le=1)]
    degradation_predicted: bool
    features: dict[str, float | None]
    observed_metrics: dict[str, float | None]
    imputed_metrics: list[str]
    feature_contributions: list[FeatureContribution]
    explanation_method: str
    limitations: list[str]


class MetricDeviation(StrictResponse):
    feature: str
    baseline_median: float
    observed_median: float
    relative_deviation: float
    direction: str
    first_event_sample: datetime | None


class TracePathEdge(StrictResponse):
    parent_service: str
    child_service: str
    trace_id: str
    timestamp: datetime
    call_count: int


class CorrelatedLog(StrictResponse):
    timestamp: datetime
    severity: str
    message: str
    trace_id: str | None


class RootCauseEvidence(StrictResponse):
    type: str
    features: list[MetricDeviation] | None = None
    path_length: int | None = None
    impacted_service: str | None = None
    edges: list[TracePathEdge] | None = None
    entries: list[CorrelatedLog] | None = None
    candidate_metric_deviation_at: datetime | None = None
    affected_service_deviation_at: datetime | None = None
    candidate_precedes_affected: bool | None = None


class RootCauseCandidate(StrictResponse):
    service_name: str
    service_metadata: ServiceMetadata
    ranking_score: Annotated[float, Field(ge=0)]
    score_semantics: str
    rank: Annotated[int, Field(ge=1)]
    evidence: list[RootCauseEvidence]


class RootCauseResponse(StrictResponse):
    incident_timestamp: datetime
    affected_service: str | None = None
    candidates: list[RootCauseCandidate]
    causality_confirmed: bool
    method: str | None = None
    limitations: list[str]
    model_version: str | None = None
