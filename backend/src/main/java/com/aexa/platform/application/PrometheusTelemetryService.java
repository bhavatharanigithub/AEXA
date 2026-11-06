package com.aexa.platform.application;

import com.aexa.platform.api.dto.ServiceTelemetryResponse;
import com.aexa.platform.api.dto.TelemetryOverviewResponse;
import com.aexa.platform.api.dto.AiAnalysisRequests.MetricObservation;
import com.aexa.platform.api.error.ApiException;
import com.aexa.platform.config.AexaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.net.URI;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class PrometheusTelemetryService {

    private static final String REQUEST_RATE_QUERY =
            "sum by (service_name) (rate(aexa_service_requests_total[5m]))";
    private static final String ERROR_RATE_QUERY =
            "sum by (service_name) (rate(aexa_service_requests_total{status_code=~\"5..\"}[5m]))";
    private static final String ERROR_RATIO_QUERY =
            "sum by (service_name) (rate(aexa_service_requests_total{status_code=~\"5..\"}[5m])) "
                    + "/ clamp_min(sum by (service_name) (rate(aexa_service_requests_total[5m])), 0.000001)";
    private static final String SERVICE_P95_QUERY =
            "histogram_quantile(0.95, sum by (service_name, le) (rate(aexa_request_duration_seconds_bucket[5m])))";
    private static final String OVERALL_P95_QUERY =
            "histogram_quantile(0.95, sum by (le) (rate(aexa_request_duration_seconds_bucket[5m])))";

    private final RestClient prometheus;

    public PrometheusTelemetryService(RestClient.Builder builder, AexaProperties properties) {
        this.prometheus = builder.baseUrl(properties.getTelemetry().getPrometheusUrl()).build();
    }

    public TelemetryOverviewResponse getOverview() {
        Map<String, Double> requestRates = readByService(query(REQUEST_RATE_QUERY));
        Map<String, Double> errorRates = readByService(query(ERROR_RATE_QUERY));
        Map<String, Double> serviceP95 = readByService(query(SERVICE_P95_QUERY));
        Double overallP95 = readSingleValue(query(OVERALL_P95_QUERY));

        if (requestRates.isEmpty()) {
            return new TelemetryOverviewResponse(
                    true, false, "prometheus", Instant.now(), null, null, null, List.of());
        }

        double totalRate = requestRates.values().stream().mapToDouble(Double::doubleValue).sum();
        double totalErrorRate = errorRates.values().stream().mapToDouble(Double::doubleValue).sum();
        List<ServiceTelemetryResponse> services = new ArrayList<>();
        requestRates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    double serviceErrorRate = errorRates.getOrDefault(entry.getKey(), 0.0);
                    double requests = entry.getValue();
                    services.add(new ServiceTelemetryResponse(
                            entry.getKey(),
                            requests,
                            requests == 0.0 ? 0.0 : serviceErrorRate / requests,
                            serviceP95.get(entry.getKey())));
                });

        return new TelemetryOverviewResponse(
                true,
                true,
                "prometheus",
                Instant.now(),
                totalRate,
                totalRate == 0.0 ? 0.0 : totalErrorRate / totalRate,
                overallP95,
                List.copyOf(services));
    }

    public List<MetricObservation> getRecentObservations(String serviceName, int lookbackMinutes) {
        if (serviceName == null || serviceName.isBlank() || lookbackMinutes < 5 || lookbackMinutes > 60) {
            throw new IllegalArgumentException("service name and a 5-to-60-minute lookback are required");
        }
        Instant end = Instant.now();
        Instant start = end.minusSeconds(lookbackMinutes * 60L);
        Map<Instant, MutableMetrics> samples = new TreeMap<>();
        readRangeByTime(queryRange(REQUEST_RATE_QUERY, start, end), serviceName)
                .forEach((timestamp, value) -> samples.computeIfAbsent(timestamp, ignored -> new MutableMetrics())
                        .requestRate = value);
        readRangeByTime(queryRange(ERROR_RATIO_QUERY, start, end), serviceName)
                .forEach((timestamp, value) -> samples.computeIfAbsent(timestamp, ignored -> new MutableMetrics())
                        .errorRate = value);
        readRangeByTime(queryRange(SERVICE_P95_QUERY, start, end), serviceName)
                .forEach((timestamp, value) -> samples.computeIfAbsent(timestamp, ignored -> new MutableMetrics())
                        .p95LatencySeconds = value);

        return samples.entrySet().stream()
                .filter(entry -> entry.getValue().hasAnyValue())
                .map(entry -> new MetricObservation(
                        entry.getKey(),
                        serviceName,
                        entry.getValue().requestRate,
                        entry.getValue().errorRate,
                        entry.getValue().p95LatencySeconds))
                .sorted(Comparator.comparing(MetricObservation::timestamp))
                .toList();
    }

    private JsonNode query(String promql) {
        try {
            URI queryUri = UriComponentsBuilder.fromPath("/api/v1/query")
                    .queryParam("query", "{promql}")
                    .encode()
                    .buildAndExpand(Map.of("promql", promql))
                    .toUri();
            JsonNode response = prometheus.get()
                    .uri(queryUri)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !"success".equals(response.path("status").asText())) {
                throw telemetryUnavailable("Prometheus returned an invalid query response");
            }
            JsonNode result = response.path("data").path("result");
            if (!result.isArray()) {
                throw telemetryUnavailable("Prometheus response did not include a result array");
            }
            return result;
        } catch (RestClientException exception) {
            throw telemetryUnavailable("Prometheus could not be reached");
        }
    }

    private JsonNode queryRange(String promql, Instant start, Instant end) {
        try {
            URI queryUri = UriComponentsBuilder.fromPath("/api/v1/query_range")
                    .queryParam("query", "{promql}")
                    .queryParam("start", "{start}")
                    .queryParam("end", "{end}")
                    .queryParam("step", "{step}")
                    .encode()
                    .buildAndExpand(Map.of(
                            "promql",
                            promql,
                            "start",
                            Long.toString(start.getEpochSecond()),
                            "end",
                            Long.toString(end.getEpochSecond()),
                            "step",
                            "60s"))
                    .toUri();
            JsonNode response = prometheus.get()
                    .uri(queryUri)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !"success".equals(response.path("status").asText())) {
                throw telemetryUnavailable("Prometheus returned an invalid query_range response");
            }
            JsonNode result = response.path("data").path("result");
            if (!result.isArray()) {
                throw telemetryUnavailable("Prometheus range response did not include a result array");
            }
            return result;
        } catch (RestClientException exception) {
            throw telemetryUnavailable("Prometheus could not be reached for adaptive telemetry");
        }
    }

    private Map<Instant, Double> readRangeByTime(JsonNode result, String serviceName) {
        Map<Instant, Double> values = new HashMap<>();
        for (JsonNode series : result) {
            if (!serviceName.equals(series.path("metric").path("service_name").asText())) {
                continue;
            }
            JsonNode samples = series.path("values");
            if (!samples.isArray()) {
                throw telemetryUnavailable("Prometheus returned a malformed range series");
            }
            for (JsonNode sample : samples) {
                if (!sample.isArray() || sample.size() < 2) {
                    throw telemetryUnavailable("Prometheus returned a malformed range sample");
                }
                try {
                    double epochSeconds = Double.parseDouble(sample.get(0).asText());
                    double value = Double.parseDouble(sample.get(1).asText());
                    if (!Double.isFinite(epochSeconds) || !Double.isFinite(value) || value < 0.0) {
                        throw telemetryUnavailable("Prometheus returned an invalid range value");
                    }
                    values.put(Instant.ofEpochMilli(Math.round(epochSeconds * 1000.0)), value);
                } catch (NumberFormatException exception) {
                    throw telemetryUnavailable("Prometheus returned a non-numeric range value");
                }
            }
        }
        return values;
    }

    private Map<String, Double> readByService(JsonNode result) {
        Map<String, Double> values = new HashMap<>();
        for (JsonNode row : result) {
            String serviceName = row.path("metric").path("service_name").asText();
            if (serviceName.isBlank()) {
                throw telemetryUnavailable("Prometheus returned a series without service_name");
            }
            values.put(serviceName, readSampleValue(row));
        }
        return values;
    }

    private Double readSingleValue(JsonNode result) {
        return result.isEmpty() ? null : readSampleValue(result.get(0));
    }

    private double readSampleValue(JsonNode row) {
        JsonNode sample = row.path("value");
        if (!sample.isArray() || sample.size() < 2) {
            throw telemetryUnavailable("Prometheus returned a malformed sample");
        }
        try {
            double value = Double.parseDouble(sample.get(1).asText());
            if (!Double.isFinite(value) || value < 0) {
                throw telemetryUnavailable("Prometheus returned an invalid metric value");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw telemetryUnavailable("Prometheus returned a non-numeric metric value");
        }
    }

    private ApiException telemetryUnavailable(String detail) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "https://aexa.dev/problems/telemetry-unavailable",
                "Telemetry unavailable",
                detail);
    }

    private static final class MutableMetrics {
        private Double requestRate;
        private Double errorRate;
        private Double p95LatencySeconds;

        private boolean hasAnyValue() {
            return requestRate != null || errorRate != null || p95LatencySeconds != null;
        }
    }
}
