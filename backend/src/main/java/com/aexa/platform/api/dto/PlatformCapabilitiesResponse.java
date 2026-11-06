package com.aexa.platform.api.dto;

public record PlatformCapabilitiesResponse(
        int phase,
        boolean telemetryIngestion,
        boolean anomalyDetection,
        boolean forecasting,
        boolean rootCauseAnalysis,
        boolean explainability,
        boolean adaptiveControlActuation,
        boolean incidentPersistence,
        boolean serviceCatalog) {}
