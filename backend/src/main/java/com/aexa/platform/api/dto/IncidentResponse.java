package com.aexa.platform.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        String title,
        String status,
        String severity,
        String summary,
        Instant startedAt,
        Instant resolvedAt,
        String createdBy,
        List<String> affectedServices) {}
