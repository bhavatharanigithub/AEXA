package com.aexa.platform.api.dto;

public record ServiceTelemetryResponse(
        String serviceName, double requestsPerSecond, double errorRate, Double p95LatencySeconds) {}
