package com.aexa.platform.application;

import com.aexa.platform.api.dto.AdaptiveAuditResponse;
import com.aexa.platform.api.dto.AdaptiveControlStateResponse;
import com.aexa.platform.api.dto.AiAnalysisRequests.MetricObservation;
import com.aexa.platform.api.dto.AiAnalysisRequests.MetricWindowRequest;
import com.aexa.platform.api.error.ApiException;
import com.aexa.platform.config.AexaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static com.aexa.platform.application.AdaptivePolicyEngine.MAX_TRACE_SAMPLE_RATE;
import static com.aexa.platform.application.AdaptivePolicyEngine.MIN_TRACE_SAMPLE_RATE;

@Service
public class AdaptiveControlService {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveControlService.class);
    private static final UUID POLICY_ID = UUID.fromString("c51f07da-0bbd-4c41-8fc3-cdfcc0b6768b");
    private static final String SAFE_FALLBACK_REASON =
            "Telemetry is stale or unavailable, or the forecast model is unavailable; full trace sampling was requested.";

    private final JdbcTemplate jdbcTemplate;
    private final PrometheusTelemetryService telemetryService;
    private final AiAnalysisService aiAnalysisService;
    private final AexaProperties.Adaptive policyProperties;
    private final Map<String, RestClient> actuators;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, Double> sampleRates = new ConcurrentHashMap<>();
    private final Map<String, String> riskLevels = new ConcurrentHashMap<>();
    private final Map<String, Integer> saferEvaluations = new ConcurrentHashMap<>();
    private volatile Instant lastEvaluatedAt;
    private volatile String lastSignalStatus = "NOT_EVALUATED";
    private volatile String lastError;
    private volatile boolean actuationAvailable;

    @Autowired
    public AdaptiveControlService(
            JdbcTemplate jdbcTemplate,
            PrometheusTelemetryService telemetryService,
            AiAnalysisService aiAnalysisService,
            AexaProperties properties,
            RestClient.Builder restClientBuilder,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.telemetryService = telemetryService;
        this.aiAnalysisService = aiAnalysisService;
        this.policyProperties = properties.getAdaptive();
        this.actuators = new LinkedHashMap<>();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        JdkClientHttpRequestFactory actuatorRequestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        actuatorRequestFactory.setReadTimeout(Duration.ofSeconds(2));
        properties.getAdaptive().getDemoServiceUrls().forEach((service, url) -> {
            if (service == null || service.isBlank() || url == null || url.isBlank()) {
                throw new IllegalArgumentException("demo service names and sampler URLs must not be blank");
            }
            actuators.put(
                    service,
                    restClientBuilder.clone()
                            .baseUrl(url)
                            .requestFactory(actuatorRequestFactory)
                            .build());
            sampleRates.put(service, MAX_TRACE_SAMPLE_RATE);
            riskLevels.put(service, "UNKNOWN");
            saferEvaluations.put(service, 0);
        });
    }

    @PostConstruct
    void initializePolicy() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM adaptive_policy WHERE id = ?", Integer.class, POLICY_ID);
        if (count != null && count > 0) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO adaptive_policy
                    (id, service_id, min_trace_sample_rate, max_trace_sample_rate,
                     min_scrape_interval_ms, max_scrape_interval_ms, cooldown_seconds,
                     enabled, created_at, updated_at)
                VALUES (?, NULL, ?, ?, 10000, 60000, ?, FALSE, ?, ?)
                """,
                POLICY_ID,
                MIN_TRACE_SAMPLE_RATE,
                MAX_TRACE_SAMPLE_RATE,
                policyProperties.getCooldownSeconds(),
                now,
                now);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reconcilePersistedSamplerState() {
        Map<String, Double> persistedRates = jdbcTemplate.query(
                """
                SELECT s.name, state.trace_sample_rate
                FROM telemetry_control_state state
                JOIN registered_service s ON s.id = state.service_id
                """,
                (result, row) -> Map.entry(result.getString("name"), result.getDouble("trace_sample_rate")))
                .stream()
                .filter(row -> actuators.containsKey(row.getKey()))
                .collect(LinkedHashMap::new, (rates, row) -> rates.put(row.getKey(), row.getValue()), Map::putAll);
        for (Map.Entry<String, Double> entry : persistedRates.entrySet()) {
            String service = entry.getKey();
            try {
                double actualRate = readRate(actuators.get(service));
                sampleRates.put(service, actualRate);
                if (Double.compare(actualRate, entry.getValue()) != 0) {
                    persistControlRates(Map.of(service, actualRate), "STARTUP_RECONCILE");
                }
            } catch (RestClientException | IllegalStateException | DataAccessException exception) {
                actuationAvailable = false;
                lastSignalStatus = "ACTUATION_UNAVAILABLE";
                lastError = "Could not reconcile persisted sampler state for " + service + ": "
                        + exception.getMessage();
                log.error(lastError, exception);
            }
        }
    }

    public AdaptiveControlStateResponse getState() {
        boolean enabled = isEnabled();
        return new AdaptiveControlStateResponse(
                enabled,
                actuationAvailable,
                enabled ? "DETERMINISTIC_BASELINE" : "DISABLED",
                lastSignalStatus,
                lastError,
                orderedRates(),
                orderedRiskLevels(),
                lastEvaluatedAt,
                lastChangedAt(),
                policyProperties.getCooldownSeconds(),
                countChangesSince(Instant.now().minusSeconds(3600)),
                policyProperties.getMaxChangesPerHour(),
                MIN_TRACE_SAMPLE_RATE,
                MAX_TRACE_SAMPLE_RATE,
                policyProperties.getStaleAfterSeconds(),
                recentAudit(20));
    }

    public List<AdaptiveAuditResponse> getAudit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("audit limit must be between 1 and 100");
        }
        return recentAudit(limit);
    }

    public synchronized AdaptiveControlStateResponse setEnabled(boolean enabled) {
        String actor = currentActor();
        boolean previous = isEnabled();
        if (previous == enabled) {
            return getState();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "UPDATE adaptive_policy SET enabled = ?, updated_at = ? WHERE id = ?",
                enabled,
                now,
                POLICY_ID);
        saferEvaluations.replaceAll((service, count) -> 0);
        writeAudit(actor, enabled ? "ENABLE" : "DISABLE", Boolean.toString(previous), Boolean.toString(enabled),
                enabled ? "Administrator enabled the bounded deterministic controller."
                        : "Administrator disabled automatic changes.");
        if (enabled) {
            return evaluate(actor);
        }
        applySafeFallback(actor, "Manual disable requested a rollback to full trace sampling.");
        return getState();
    }

    public synchronized AdaptiveControlStateResponse rollback() {
        String actor = currentActor();
        jdbcTemplate.update(
                "UPDATE adaptive_policy SET enabled = FALSE, updated_at = ? WHERE id = ?",
                OffsetDateTime.now(ZoneOffset.UTC),
                POLICY_ID);
        saferEvaluations.replaceAll((service, count) -> 0);
        writeAudit(actor, "ROLLBACK", null, "all services=1.0",
                "Administrator disabled adaptive control and requested the safe full-sampling baseline.");
        applySafeFallback(actor, "Manual rollback requested full trace sampling.");
        return getState();
    }

    public synchronized AdaptiveControlStateResponse evaluateNow() {
        if (!isEnabled()) {
            lastSignalStatus = "DISABLED";
            return getState();
        }
        return evaluate(currentActor());
    }

    @Scheduled(fixedDelayString = "${aexa.adaptive.evaluation-interval-millis:15000}")
    public void scheduledEvaluation() {
        if (isEnabled()) {
            evaluate("SYSTEM");
        }
    }

    private AdaptiveControlStateResponse evaluate(String actor) {
        lastEvaluatedAt = Instant.now();
        Map<String, Double> actualRates = new LinkedHashMap<>();
        Map<String, Double> targets = new LinkedHashMap<>();
        Map<String, String> nextRisks = new LinkedHashMap<>();
        Map<String, Integer> nextSaferCounts = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, RestClient> actuator : actuators.entrySet()) {
                actualRates.put(actuator.getKey(), readRate(actuator.getValue()));
            }
            for (String service : actuators.keySet()) {
                List<MetricObservation> observations = telemetryService.getRecentObservations(service, 15);
                if (observations.size() < 6) {
                    throw new IllegalStateException("Recent telemetry is insufficient for " + service);
                }
                MetricObservation latest = observations.get(observations.size() - 1);
                long ageSeconds = Instant.now().getEpochSecond() - latest.timestamp().getEpochSecond();
                if (ageSeconds > policyProperties.getStaleAfterSeconds() || ageSeconds < -15) {
                    throw new IllegalStateException("Recent telemetry is stale for " + service);
                }
                JsonNode forecast = aiAnalysisService.forecastDegradation(
                        new MetricWindowRequest(service, observations));
                double probability = forecast.path("degradation_probability").asDouble(Double.NaN);
                AdaptivePolicyEngine.Signals signals = new AdaptivePolicyEngine.Signals(
                        latest.errorRate(), latest.p95LatencySeconds(), probability);
                if (!signals.isValid() || !Double.isFinite(probability)) {
                    throw new IllegalStateException("The forecast model returned invalid risk signals for " + service);
                }
                AdaptivePolicyEngine.RiskBand risk = AdaptivePolicyEngine.classify(signals);
                AdaptivePolicyEngine.Transition transition = AdaptivePolicyEngine.transition(
                        actualRates.get(service),
                        risk,
                        saferEvaluations.getOrDefault(service, 0));
                targets.put(service, transition.targetSampleRate());
                nextRisks.put(service, risk.name());
                nextSaferCounts.put(service, transition.saferEvaluations());
            }
        } catch (ApiException | RestClientException | IllegalStateException exception) {
            log.warn("Adaptive evaluation failed; requesting full trace sampling: {}", exception.getMessage());
            applySafeFallback(actor, SAFE_FALLBACK_REASON);
            lastSignalStatus = "SAFE_FALLBACK";
            lastError = exception instanceof ApiException apiException
                    ? apiException.getTitle() + ": " + apiException.getMessage()
                    : exception.getMessage();
            return getState();
        }

        riskLevels.putAll(nextRisks);
        saferEvaluations.putAll(nextSaferCounts);
        Map<String, Double> decreases = new LinkedHashMap<>();
        targets.forEach((service, rate) -> {
            if (rate < actualRates.get(service)) {
                decreases.put(service, rate);
            }
        });
        if (!decreases.isEmpty() && isRateLimitedOrCoolingDown()) {
            lastSignalStatus = "REDUCTION_DEFERRED";
            lastError = null;
            return getState();
        }

        Map<String, Double> previousRates = actualRates;
        boolean changed = !targets.equals(previousRates);
        if (changed) {
            try {
                applyRatesAtomically(targets);
            } catch (RestClientException | IllegalStateException | DataAccessException exception) {
                log.error("Adaptive sampling actuation failed; rollback attempted", exception);
                applySafeFallback(actor, "Actuation failed; rollback to full trace sampling was requested.");
                lastSignalStatus = "SAFE_FALLBACK";
                lastError = "Sampler update failed; full-sampling rollback was requested.";
                return getState();
            }
            writeAudit(actor, "AUTO_APPLY", previousRates.toString(), targets.toString(),
                    "Deterministic risk policy adjusted trace sampling from fresh metrics and model forecasts.");
        } else {
            persistControlRates(targets, "OBSERVED");
            sampleRates.putAll(targets);
        }
        actuationAvailable = true;
        lastSignalStatus = "FRESH";
        lastError = null;
        return getState();
    }

    private void applySafeFallback(String actor, String reason) {
        Map<String, Double> previous = currentTargets();
        Map<String, Double> accepted = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, RestClient> actuator : actuators.entrySet()) {
            try {
                writeRate(actuator.getValue(), MAX_TRACE_SAMPLE_RATE);
                accepted.put(actuator.getKey(), MAX_TRACE_SAMPLE_RATE);
            } catch (RestClientException | IllegalStateException exception) {
                failures.add(actuator.getKey());
                log.error("Could not restore full sampling for {}", actuator.getKey(), exception);
            }
        }
        sampleRates.putAll(accepted);
        try {
            persistControlRates(accepted, "SAFE_FALLBACK");
        } catch (DataAccessException exception) {
            failures.add("control-state persistence");
            log.error("Could not persist safe-fallback sampling state", exception);
        }
        boolean changed = accepted.entrySet().stream()
                .anyMatch(entry -> !Double.valueOf(MAX_TRACE_SAMPLE_RATE).equals(previous.get(entry.getKey())));
        if (changed) {
            writeAudit(actor, "SAFE_FALLBACK", previous.toString(), accepted.toString(), reason);
        }
        actuationAvailable = failures.isEmpty();
        if (!failures.isEmpty()) {
            lastError = "Sampler control or state persistence unavailable for: " + String.join(", ", failures);
        } else {
            lastError = null;
        }
        lastSignalStatus = failures.isEmpty() ? "SAFE_FALLBACK" : "ACTUATION_UNAVAILABLE";
    }

    private void applyRatesAtomically(Map<String, Double> targets) {
        Map<String, Double> previous = new LinkedHashMap<>();
        List<String> changed = new ArrayList<>();
        try {
            for (Map.Entry<String, Double> target : targets.entrySet()) {
                RestClient actuator = actuators.get(target.getKey());
                double current = readRate(actuator);
                previous.put(target.getKey(), current);
                if (Double.compare(current, target.getValue()) != 0) {
                    writeRate(actuator, target.getValue());
                    changed.add(target.getKey());
                }
            }
        } catch (RestClientException | IllegalStateException exception) {
            for (int index = changed.size() - 1; index >= 0; index--) {
                String service = changed.get(index);
                try {
                    writeRate(actuators.get(service), previous.get(service));
                } catch (RestClientException | IllegalStateException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
            }
            throw exception;
        }
        try {
            persistControlRates(targets, "AUTO_APPLY");
        } catch (DataAccessException exception) {
            for (int index = changed.size() - 1; index >= 0; index--) {
                String service = changed.get(index);
                try {
                    writeRate(actuators.get(service), previous.get(service));
                } catch (RestClientException | IllegalStateException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
            }
            throw exception;
        }
        sampleRates.putAll(targets);
    }

    void persistControlRates(Map<String, Double> rates, String source) {
        if (source == null || source.isBlank() || source.length() > 32) {
            throw new IllegalArgumentException("control-state source must be 1 to 32 characters");
        }
        transactionTemplate.executeWithoutResult(status -> {
            for (Map.Entry<String, Double> entry : rates.entrySet()) {
                double rate = entry.getValue();
                if (!Double.isFinite(rate) || rate < MIN_TRACE_SAMPLE_RATE || rate > MAX_TRACE_SAMPLE_RATE) {
                    throw new IllegalArgumentException("trace sample rate is outside configured bounds");
                }
                UUID serviceId = findServiceId(entry.getKey());
                if (serviceId == null) {
                    throw new IllegalStateException("Cannot persist sampler state for unknown service " + entry.getKey());
                }
                OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
                int updated = jdbcTemplate.update(
                        """
                        UPDATE telemetry_control_state
                        SET trace_sample_rate = ?, scrape_interval_ms = 10000, source = ?, updated_at = ?
                        WHERE service_id = ?
                        """,
                        rate,
                        source,
                        updatedAt,
                        serviceId);
                if (updated == 0) {
                    jdbcTemplate.update(
                            """
                            INSERT INTO telemetry_control_state
                                (id, service_id, trace_sample_rate, scrape_interval_ms, source, updated_at)
                            VALUES (?, ?, ?, 10000, ?, ?)
                            """,
                            UUID.randomUUID(),
                            serviceId,
                            rate,
                            source,
                            updatedAt);
                }
            }
        });
    }

    private UUID findServiceId(String serviceName) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM registered_service WHERE name = ? ORDER BY created_at DESC LIMIT 1",
                (result, row) -> result.getObject("id", UUID.class),
                serviceName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private double readRate(RestClient actuator) {
        JsonNode body = actuator.get().uri("/internal/telemetry/sampling").retrieve().body(JsonNode.class);
        double rate = body == null ? Double.NaN : body.path("trace_sample_rate").asDouble(Double.NaN);
        if (!Double.isFinite(rate) || rate < MIN_TRACE_SAMPLE_RATE || rate > MAX_TRACE_SAMPLE_RATE) {
            throw new IllegalStateException("Demo service returned a trace sample rate outside configured bounds");
        }
        return rate;
    }

    private void writeRate(RestClient actuator, double sampleRate) {
        JsonNode body = actuator.put()
                .uri("/internal/telemetry/sampling")
                .body(Map.of("trace_sample_rate", sampleRate))
                .retrieve()
                .body(JsonNode.class);
        double acceptedRate = body == null ? Double.NaN : body.path("trace_sample_rate").asDouble(Double.NaN);
        if (Double.compare(acceptedRate, sampleRate) != 0) {
            throw new IllegalStateException("Demo service did not confirm the requested trace sample rate");
        }
    }

    private boolean isRateLimitedOrCoolingDown() {
        Instant changedAt = lastChangedAt();
        if (changedAt != null && changedAt.plusSeconds(policyProperties.getCooldownSeconds()).isAfter(Instant.now())) {
            return true;
        }
        return countChangesSince(Instant.now().minusSeconds(3600)) >= policyProperties.getMaxChangesPerHour();
    }

    private boolean isEnabled() {
        Boolean enabled = jdbcTemplate.queryForObject(
                "SELECT enabled FROM adaptive_policy WHERE id = ?", Boolean.class, POLICY_ID);
        return Boolean.TRUE.equals(enabled);
    }

    private Map<String, Double> currentTargets() {
        Map<String, Double> current = new LinkedHashMap<>();
        actuators.keySet().forEach(service ->
                current.put(service, sampleRates.getOrDefault(service, MAX_TRACE_SAMPLE_RATE)));
        return current;
    }

    private Map<String, Double> orderedRates() {
        return currentTargets();
    }

    private Map<String, String> orderedRiskLevels() {
        Map<String, String> current = new LinkedHashMap<>();
        actuators.keySet().forEach(service -> current.put(service, riskLevels.getOrDefault(service, "UNKNOWN")));
        return current;
    }

    private int countChangesSince(Instant since) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adaptive_audit WHERE policy_id = ? AND action = 'AUTO_APPLY' AND created_at >= ?",
                Integer.class,
                POLICY_ID,
                Timestamp.from(since));
        return count == null ? 0 : count;
    }

    private Instant lastChangedAt() {
        List<Instant> values = jdbcTemplate.query(
                """
                SELECT created_at FROM adaptive_audit
                WHERE policy_id = ? AND action IN ('AUTO_APPLY', 'SAFE_FALLBACK', 'ROLLBACK')
                ORDER BY created_at DESC LIMIT 1
                """,
                (result, row) -> readInstant(result, "created_at"),
                POLICY_ID);
        return values.isEmpty() ? null : values.get(0);
    }

    private List<AdaptiveAuditResponse> recentAudit(int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, actor, action, previous_value, new_value, reason, created_at
                FROM adaptive_audit WHERE policy_id = ?
                ORDER BY created_at DESC LIMIT ?
                """,
                (result, row) -> new AdaptiveAuditResponse(
                        result.getObject("id", UUID.class),
                        result.getString("actor"),
                        result.getString("action"),
                        result.getString("previous_value"),
                        result.getString("new_value"),
                        result.getString("reason"),
                        readInstant(result, "created_at")),
                POLICY_ID,
                limit);
    }

    private void writeAudit(String actor, String action, String previous, String next, String reason) {
        jdbcTemplate.update(
                """
                INSERT INTO adaptive_audit
                    (id, policy_id, actor, action, previous_value, new_value, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                POLICY_ID,
                actor.length() > 64 ? actor.substring(0, 64) : actor,
                action,
                previous,
                next,
                reason,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "SYSTEM" : authentication.getName();
    }

    private Instant readInstant(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new DataAccessException("Unsupported timestamp type in adaptive control audit") {};
    }
}
