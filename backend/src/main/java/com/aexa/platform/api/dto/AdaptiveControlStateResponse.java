package com.aexa.platform.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AdaptiveControlStateResponse(
        boolean enabled,
        boolean actuationAvailable,
        String controllerMode,
        String lastSignalStatus,
        String lastError,
        Map<String, Double> traceSampleRates,
        Map<String, String> riskLevels,
        Instant lastEvaluatedAt,
        Instant lastChangedAt,
        int cooldownSeconds,
        int changesInLastHour,
        int maxChangesPerHour,
        double minimumTraceSampleRate,
        double maximumTraceSampleRate,
        int staleAfterSeconds,
        List<AdaptiveAuditResponse> recentAudit) {}
