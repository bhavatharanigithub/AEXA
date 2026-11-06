package com.aexa.platform.api.dto;

import java.time.Instant;
import java.util.List;

public record TelemetryOverviewResponse(
        boolean available,
        boolean hasData,
        String source,
        Instant queriedAt,
        Double requestsPerSecond,
        Double errorRate,
        Double p95LatencySeconds,
        List<ServiceTelemetryResponse> services) {}
