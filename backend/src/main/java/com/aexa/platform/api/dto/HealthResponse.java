package com.aexa.platform.api.dto;

import java.time.Instant;

public record HealthResponse(
        String status, String service, String version, String profile, String database, Instant timestamp) {}
