package com.aexa.platform.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        String name,
        String namespace,
        String environment,
        String version,
        String displayName,
        String description,
        String owner,
        Instant createdAt,
        Instant updatedAt) {}
