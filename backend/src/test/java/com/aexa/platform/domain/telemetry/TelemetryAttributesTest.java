package com.aexa.platform.domain.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryAttributesTest {

    @Test
    void identifierContractsMatchOpenTelemetryHexLengths() {
        assertThat(TelemetryAttributes.TRACE_ID_HEX_LENGTH).isEqualTo(32);
        assertThat(TelemetryAttributes.SPAN_ID_HEX_LENGTH).isEqualTo(16);
        assertThat(TelemetryAttributes.SERVICE_NAME).isEqualTo("service.name");
        assertThat(TelemetryAttributes.DEPLOYMENT_ENVIRONMENT).isEqualTo("deployment.environment");
    }
}
