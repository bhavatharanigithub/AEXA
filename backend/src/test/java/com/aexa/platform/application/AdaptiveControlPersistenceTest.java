package com.aexa.platform.application;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AdaptiveControlPersistenceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdaptiveControlService adaptiveControlService;

    @Test
    void samplerStateIsUpsertedAndRatesOutsideBoundsAreRejected() {
        UUID serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM registered_service WHERE name = ? AND environment = ?",
                UUID.class,
                "checkout-api",
                "demo");
        try {
            adaptiveControlService.persistControlRates(Map.of("checkout-api", 0.5), "TEST_APPLY");
            Double firstRate = jdbcTemplate.queryForObject(
                    "SELECT trace_sample_rate FROM telemetry_control_state WHERE service_id = ?",
                    Double.class,
                    serviceId);
            assertThat(firstRate).isEqualTo(0.5);

            adaptiveControlService.persistControlRates(Map.of("checkout-api", 0.25), "TEST_UPDATE");
            Double updatedRate = jdbcTemplate.queryForObject(
                    "SELECT trace_sample_rate FROM telemetry_control_state WHERE service_id = ?",
                    Double.class,
                    serviceId);
            String source = jdbcTemplate.queryForObject(
                    "SELECT source FROM telemetry_control_state WHERE service_id = ?",
                    String.class,
                    serviceId);
            assertThat(updatedRate).isEqualTo(0.25);
            assertThat(source).isEqualTo("TEST_UPDATE");
            assertThatThrownBy(() ->
                    adaptiveControlService.persistControlRates(Map.of("checkout-api", 1.5), "INVALID"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            jdbcTemplate.update("DELETE FROM telemetry_control_state WHERE service_id = ?", serviceId);
        }
    }
}
