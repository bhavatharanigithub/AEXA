package com.aexa.platform.application;

import com.aexa.platform.api.dto.AiAnalysisRequests.MetricWindowRequest;
import com.aexa.platform.api.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HistoricalAnalysisService {

    private final PrometheusTelemetryService telemetryService;
    private final AiAnalysisService aiAnalysisService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public HistoricalAnalysisService(
            PrometheusTelemetryService telemetryService,
            AiAnalysisService aiAnalysisService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.telemetryService = telemetryService;
        this.aiAnalysisService = aiAnalysisService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public JsonNode detectAnomalies(String serviceName, int lookbackMinutes) {
        List<com.aexa.platform.api.dto.AiAnalysisRequests.MetricObservation> observations =
                telemetryService.getRecentObservations(serviceName, lookbackMinutes);
        if (observations.size() < 6) {
            throw insufficientTelemetry();
        }
        JsonNode modelResponse = aiAnalysisService.detectAnomalies(new MetricWindowRequest(serviceName, observations));
        int saved = transactionTemplate.execute(status -> {
            int count = 0;
            for (JsonNode anomaly : modelResponse.path("results")) {
                if (anomaly.path("anomalous").asBoolean()) {
                    count += persistAnomaly(serviceName, modelResponse, anomaly);
                }
            }
            return count;
        });
        JsonNode result = modelResponse.deepCopy();
        if (result.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("persisted_anomalies", saved);
        }
        return result;
    }

    public JsonNode forecast(String serviceName, int lookbackMinutes) {
        List<com.aexa.platform.api.dto.AiAnalysisRequests.MetricObservation> observations =
                telemetryService.getRecentObservations(serviceName, lookbackMinutes);
        if (observations.size() < 6) {
            throw insufficientTelemetry();
        }
        JsonNode modelResponse = aiAnalysisService.forecastDegradation(new MetricWindowRequest(serviceName, observations));
        transactionTemplate.executeWithoutResult(status -> persistPrediction(serviceName, modelResponse));
        JsonNode result = modelResponse.deepCopy();
        if (result.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("persisted", true);
        }
        return result;
    }

    public List<StoredAnalysis> anomalies(String serviceName, int limit) {
        String sql = """
                SELECT a.id, s.name, a.detected_at, a.detector_name, a.score, a.feature_json
                FROM anomaly a LEFT JOIN registered_service s ON s.id = a.service_id
                WHERE (CAST(? AS VARCHAR) IS NULL OR s.name = ?)
                ORDER BY a.detected_at DESC LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                (row, index) -> new StoredAnalysis(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getTimestamp("detected_at").toInstant(),
                        row.getString("detector_name"),
                        row.getObject("score", Double.class),
                        parseJson(row.getString("feature_json"))),
                serviceName,
                serviceName,
                limit);
    }

    public List<StoredForecast> forecasts(String serviceName, int limit) {
        String sql = """
                SELECT p.id, s.name, p.metric_name, p.horizon_minutes, p.breach_probability,
                       p.threshold_value, p.status, p.predicted_for, p.feature_json
                FROM prediction p LEFT JOIN registered_service s ON s.id = p.service_id
                WHERE (CAST(? AS VARCHAR) IS NULL OR s.name = ?)
                ORDER BY p.created_at DESC LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                (row, index) -> new StoredForecast(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("metric_name"),
                        row.getInt("horizon_minutes"),
                        row.getDouble("breach_probability"),
                        row.getDouble("threshold_value"),
                        row.getString("status"),
                        row.getTimestamp("predicted_for").toInstant(),
                        parseJson(row.getString("feature_json"))),
                serviceName,
                serviceName,
                limit);
    }

    private int persistAnomaly(String serviceName, JsonNode response, JsonNode anomaly) {
        Instant detectedAt = Instant.parse(anomaly.path("timestamp").asText());
        String detectorName = "isolation-forest:" + response.path("model_version").asText("unknown");
        Integer existing = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM anomaly a JOIN registered_service s ON s.id = a.service_id
                WHERE s.name = ? AND a.detector_name = ? AND a.detected_at = ?
                """,
                Integer.class,
                serviceName,
                detectorName,
                Timestamp.from(detectedAt));
        if (existing != null && existing > 0) {
            return 0;
        }
        UUID anomalyId = UUID.randomUUID();
        UUID serviceId = findServiceId(serviceName);
        String features = writeJson(anomaly.path("features"));
        jdbcTemplate.update(
                """
                INSERT INTO anomaly
                    (id, service_id, detector_name, metric_name, score, status, explanation_summary,
                     feature_json, detected_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                anomalyId,
                serviceId,
                detectorName,
                "timestamped-service-metrics",
                anomaly.path("anomaly_score").asDouble(),
                anomaly.path("anomalous").asBoolean() ? "DETECTED" : "NORMAL",
                "Persisted output from the trained anomaly model.",
                features,
                Timestamp.from(detectedAt),
                Timestamp.from(Instant.now()));
        jdbcTemplate.update(
                """
                INSERT INTO explanation (id, anomaly_id, prediction_id, method, summary, limitations,
                                         payload_json, created_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                anomalyId,
                anomaly.path("explanation_method").asText("model output; no local explanation"),
                "Model output and feature contributions are stored as returned by the inference API.",
                String.join("; ", textValues(response.path("limitations"))),
                writeJson(anomaly),
                Timestamp.from(Instant.now()));
        return 1;
    }

    private void persistPrediction(String serviceName, JsonNode result) {
        UUID predictionId = UUID.randomUUID();
        UUID serviceId = findServiceId(serviceName);
        Instant predictedFor = Instant.parse(result.path("as_of").asText());
        double probability = result.path("degradation_probability").asDouble();
        double threshold = result.path("operational_probability_threshold").asDouble();
        jdbcTemplate.update(
                """
                INSERT INTO prediction
                    (id, service_id, metric_name, horizon_minutes, predicted_value, threshold_value,
                     breach_probability, status, feature_json, predicted_for, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                predictionId,
                serviceId,
                "p95_latency_degradation_probability",
                result.path("prediction_horizon_minutes").asInt(),
                new org.springframework.jdbc.core.SqlParameterValue(Types.DOUBLE, null),
                threshold,
                probability,
                result.path("degradation_predicted").asBoolean() ? "DEGRADATION_PREDICTED" : "NORMAL",
                writeJson(result.path("features")),
                Timestamp.from(predictedFor),
                Timestamp.from(Instant.now()));
        jdbcTemplate.update(
                """
                INSERT INTO explanation (id, anomaly_id, prediction_id, method, summary, limitations,
                                         payload_json, created_at)
                VALUES (?, NULL, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                predictionId,
                result.path("explanation_method").asText("model output; no local explanation"),
                "Model output and feature contributions are stored as returned by the inference API.",
                String.join("; ", textValues(result.path("limitations"))),
                writeJson(result),
                Timestamp.from(Instant.now()));
    }

    private UUID findServiceId(String serviceName) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM registered_service WHERE name = ? ORDER BY created_at DESC LIMIT 1",
                (row, index) -> row.getObject("id", UUID.class),
                serviceName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(value -> values.add(value.asText()));
        }
        return values;
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize model result for persistence", exception);
        }
    }

    private JsonNode parseJson(String value) {
        if (value == null) {
            return objectMapper.getNodeFactory().nullNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Persisted analysis payload is invalid JSON", exception);
        }
    }

    private ApiException insufficientTelemetry() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "https://aexa.dev/problems/insufficient-telemetry",
                "Insufficient telemetry",
                "At least six real Prometheus observations are required for this analysis");
    }

    public record StoredAnalysis(
            UUID id, String serviceName, Instant detectedAt, String detector, Double score, JsonNode features) {}

    public record StoredForecast(
            UUID id,
            String serviceName,
            String metricName,
            int horizonMinutes,
            double probability,
            double threshold,
            String status,
            Instant predictedFor,
            JsonNode features) {}
}
