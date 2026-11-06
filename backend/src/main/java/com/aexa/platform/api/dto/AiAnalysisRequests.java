package com.aexa.platform.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AiAnalysisRequests {

    private AiAnalysisRequests() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetricObservation(
            @NotNull Instant timestamp,
            @NotBlank @Size(max = 128) String serviceName,
            @DecimalMin("0.0") Double requestRate,
            @DecimalMin("0.0") @DecimalMax("1.0") Double errorRate,
            @DecimalMin("0.0") Double p95LatencySeconds) {

        @AssertTrue(message = "at least one metric value must be observed")
        @JsonIgnore
        public boolean isMetricValuePresent() {
            return requestRate != null || errorRate != null || p95LatencySeconds != null;
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetricWindowRequest(
            @NotBlank @Size(max = 128) String serviceName,
            @NotEmpty @Size(min = 6, max = 5000) List<@NotNull @Valid MetricObservation> observations) {

        @AssertTrue(message = "all observations must belong to service_name")
        @JsonIgnore
        public boolean isServiceConsistent() {
            return observations == null
                    || observations.stream().allMatch(
                            row -> row != null && row.serviceName() != null && row.serviceName().equals(serviceName));
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TraceEdge(
            @NotNull Instant timestamp,
            @NotBlank @Size(max = 128) String parentService,
            @NotBlank @Size(max = 128) String childService,
            @NotBlank @Size(max = 128) String traceId,
            @NotNull @Min(1) @Max(1_000_000) Integer callCount) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LogEvidence(
            @NotNull Instant timestamp,
            @NotBlank @Size(max = 128) String serviceName,
            @NotBlank @Size(max = 32) String severity,
            @NotBlank @Size(max = 2000) String message,
            @Size(max = 128) String traceId) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ServiceMetadata(
            @NotBlank @Size(max = 128) String serviceName,
            @Size(max = 128) String namespace,
            @Size(max = 128) String environment,
            @Size(max = 128) String version) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RootCauseRequest(
            @NotNull Instant incidentTimestamp,
            @NotEmpty @Size(max = 5000) List<@NotNull @Valid MetricObservation> observations,
            @NotNull @Size(max = 2000) List<@NotNull @Valid TraceEdge> traceEdges,
            @NotNull @Size(max = 2000) List<@NotNull @Valid LogEvidence> logs,
            @NotEmpty @Size(max = 500) List<@NotNull @Valid ServiceMetadata> serviceMetadata) {

        @AssertTrue(message = "telemetry evidence cannot be later than incident_timestamp")
        @JsonIgnore
        public boolean isEvidenceBoundedToIncident() {
            if (incidentTimestamp == null) {
                return true;
            }
            boolean metricsBounded = observations == null
                    || observations.stream().allMatch(row ->
                            row == null || row.timestamp() == null || !row.timestamp().isAfter(incidentTimestamp));
            boolean tracesBounded = traceEdges == null
                    || traceEdges.stream().allMatch(row -> row == null || row.timestamp() == null
                            || !row.timestamp().isAfter(incidentTimestamp));
            boolean logsBounded = logs == null
                    || logs.stream().allMatch(row -> row == null || row.timestamp() == null
                            || !row.timestamp().isAfter(incidentTimestamp));
            return metricsBounded && tracesBounded && logsBounded;
        }

        @AssertTrue(message = "service_metadata must include every service referenced by telemetry evidence")
        @JsonIgnore
        public boolean isServiceMetadataComplete() {
            if (serviceMetadata == null) {
                return true;
            }
            var knownServices = serviceMetadata.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ServiceMetadata::serviceName)
                    .collect(java.util.stream.Collectors.toSet());
            if (knownServices.size() > 200) {
                return false;
            }
            boolean metricsKnown = observations == null
                    || observations.stream().allMatch(
                            row -> row != null && knownServices.contains(row.serviceName()));
            boolean tracesKnown = traceEdges == null
                    || traceEdges.stream().allMatch(row -> row != null && knownServices.contains(row.parentService())
                            && knownServices.contains(row.childService()));
            boolean logsKnown =
                    logs == null || logs.stream()
                            .allMatch(row -> row != null && knownServices.contains(row.serviceName()));
            return metricsKnown && tracesKnown && logsKnown;
        }
    }
}
