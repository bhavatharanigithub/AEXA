package com.aexa.platform.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AdaptiveAuditResponse(
        UUID id,
        String actor,
        String action,
        String previousValue,
        String newValue,
        String reason,
        Instant createdAt) {}
