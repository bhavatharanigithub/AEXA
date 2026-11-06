package com.aexa.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void healthReportsDatabaseUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.service").value("aexa-backend"));
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void loginValidationReturnsProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void loginRejectsBadPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void catalogRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/services")).andExpect(status().isUnauthorized());
    }

    @Test
    void telemetryOverviewRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/telemetry/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void analystCanReadCatalogAndEmptyIncidents() throws Exception {
        String token = login("analyst", "analyst-change-me");
        mockMvc.perform(get("/api/v1/services").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].description").value("Instrumented demo workload. Traffic is synthetic."));

        mockMvc.perform(get("/api/v1/incidents").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void unknownIncidentIsNotFound() throws Exception {
        String token = login("admin", "admin-change-me");
        mockMvc.perform(get("/api/v1/incidents/" + UUID.randomUUID()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void adminCanCreateServiceAndAnalystCannot() throws Exception {
        String analyst = login("analyst", "analyst-change-me");
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + analyst)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"name":"billing-api","namespace":"demo","environment":"local","version":"0.1.0","displayName":"Billing","description":"test","owner":"platform"}
                                """))
                .andExpect(status().isForbidden());

        String admin = login("admin", "admin-change-me");
        mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"name":"billing-api","namespace":"demo","environment":"local","version":"0.1.0","displayName":"Billing","description":"test","owner":"platform"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("billing-api"));
    }

    @Test
    void capabilitiesDeclareImplementedPhaseFiveIntegration() throws Exception {
        String token = login("admin", "admin-change-me");
        mockMvc.perform(get("/api/v1/platform/capabilities").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value(5))
                .andExpect(jsonPath("$.telemetryIngestion").value(true))
                .andExpect(jsonPath("$.anomalyDetection").value(true))
                .andExpect(jsonPath("$.forecasting").value(true))
                .andExpect(jsonPath("$.rootCauseAnalysis").value(true))
                .andExpect(jsonPath("$.explainability").value(true))
                .andExpect(jsonPath("$.adaptiveControlActuation").value(true))
                .andExpect(jsonPath("$.serviceCatalog").value(true));
    }

    @Test
    void phaseFiveTelemetryAndAnalysisRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/telemetry/metrics").param("serviceName", "checkout-api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/telemetry/logs").param("serviceName", "checkout-api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/telemetry/traces").param("serviceName", "checkout-api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/analysis/anomalies")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/analysis/forecasts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/analysis/anomalies/run").param("serviceName", "checkout-api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/analysis/forecasts/run").param("serviceName", "checkout-api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystCanReadEmptyAnalysisHistoryAndInvalidWindowsAreRejected() throws Exception {
        String token = login("analyst", "analyst-change-me");
        mockMvc.perform(get("/api/v1/analysis/anomalies").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/analysis/forecasts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/v1/telemetry/metrics")
                        .header("Authorization", "Bearer " + token)
                        .param("serviceName", "checkout-api")
                        .param("lookbackMinutes", "61"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/telemetry/logs")
                        .header("Authorization", "Bearer " + token)
                        .param("serviceName", "checkout-api")
                        .param("limit", "201"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/telemetry/traces")
                        .header("Authorization", "Bearer " + token)
                        .param("serviceName", "checkout-api")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void committedAnalysisRowsRemainAvailableThroughHistoryEndpoints() throws Exception {
        String token = login("analyst", "analyst-change-me");
        UUID serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM registered_service WHERE name = ? AND environment = ?",
                UUID.class,
                "checkout-api",
                "demo");
        UUID anomalyId = UUID.randomUUID();
        UUID predictionId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO anomaly
                        (id, service_id, detector_name, metric_name, score, status, detected_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    anomalyId,
                    serviceId,
                    "persistence-regression-test",
                    "p95_latency_seconds",
                    1.25,
                    "DETECTED",
                    now,
                    now);
            jdbcTemplate.update(
                    """
                    INSERT INTO prediction
                        (id, service_id, metric_name, horizon_minutes, threshold_value,
                         breach_probability, status, predicted_for, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    predictionId,
                    serviceId,
                    "p95_latency_seconds",
                    5,
                    1.0,
                    0.75,
                    "DEGRADATION_PREDICTED",
                    now,
                    now);

            mockMvc.perform(get("/api/v1/analysis/anomalies")
                            .header("Authorization", "Bearer " + token)
                            .param("serviceName", "checkout-api")
                            .param("limit", "500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '" + anomalyId + "')]").isNotEmpty());
            mockMvc.perform(get("/api/v1/analysis/forecasts")
                            .header("Authorization", "Bearer " + token)
                            .param("serviceName", "checkout-api")
                            .param("limit", "500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.id == '" + predictionId + "')]").isNotEmpty());
        } finally {
            jdbcTemplate.update("DELETE FROM explanation WHERE anomaly_id = ?", anomalyId);
            jdbcTemplate.update("DELETE FROM explanation WHERE prediction_id = ?", predictionId);
            jdbcTemplate.update("DELETE FROM anomaly WHERE id = ?", anomalyId);
            jdbcTemplate.update("DELETE FROM prediction WHERE id = ?", predictionId);
        }
    }

    @Test
    void adaptiveControlsAreAdminOnlyAndExposeSafeDefaults() throws Exception {
        String analyst = login("analyst", "analyst-change-me");
        mockMvc.perform(get("/api/v1/adaptive/state").header("Authorization", "Bearer " + analyst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.actuationAvailable").value(false))
                .andExpect(jsonPath("$.traceSampleRates['checkout-api']").value(1.0))
                .andExpect(jsonPath("$.minimumTraceSampleRate").value(0.05))
                .andExpect(jsonPath("$.maximumTraceSampleRate").value(1.0));

        mockMvc.perform(post("/api/v1/adaptive/evaluate").header("Authorization", "Bearer " + analyst))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/adaptive/rollback").header("Authorization", "Bearer " + analyst))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/adaptive/enabled")
                        .header("Authorization", "Bearer " + analyst)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/adaptive/audit?limit=0").header("Authorization", "Bearer " + analyst))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void aiAnalysisRequiresAuthenticationAndValidTelemetry() throws Exception {
        mockMvc.perform(post("/api/v1/ai/anomalies/detect"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/ai/root-causes/rank"))
                .andExpect(status().isUnauthorized());

        String token = login("admin", "admin-change-me");
        mockMvc.perform(post("/api/v1/ai/anomalies/detect")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service_name\":\"checkout-api\",\"observations\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));

        String analyst = login("analyst", "analyst-change-me");
        mockMvc.perform(post("/api/v1/ai/root-causes/rank")
                        .header("Authorization", "Bearer " + analyst)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observations\":[],\"trace_edges\":[],\"logs\":[],\"service_metadata\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.get("accessToken").asText()).isNotBlank();
        return node.get("accessToken").asText();
    }
}
