package com.aexa.platform.domain.telemetry;

/**
 * Frozen OpenTelemetry-aligned field names used across ingestion (Phase 2+)
 * and investigation APIs. Do not rename without a documented schema version bump.
 */
public final class TelemetryAttributes {

    public static final String SERVICE_NAME = "service.name";
    public static final String SERVICE_NAMESPACE = "service.namespace";
    public static final String SERVICE_VERSION = "service.version";
    public static final String SERVICE_INSTANCE_ID = "service.instance.id";
    public static final String DEPLOYMENT_ENVIRONMENT = "deployment.environment";
    public static final String TRACE_ID = "trace_id";
    public static final String SPAN_ID = "span_id";
    public static final String TIMESTAMP = "timestamp";

    public static final int TRACE_ID_HEX_LENGTH = 32;
    public static final int SPAN_ID_HEX_LENGTH = 16;

    private TelemetryAttributes() {
    }
}
