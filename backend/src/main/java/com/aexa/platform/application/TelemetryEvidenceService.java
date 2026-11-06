package com.aexa.platform.application;

import com.aexa.platform.api.error.ApiException;
import com.aexa.platform.config.AexaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class TelemetryEvidenceService {

    private static final Pattern SERVICE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}");
    private final RestClient loki;
    private final RestClient jaeger;

    public TelemetryEvidenceService(RestClient.Builder builder, AexaProperties properties) {
        SimpleClientHttpRequestFactory lokiRequestFactory = new SimpleClientHttpRequestFactory();
        lokiRequestFactory.setConnectTimeout(Duration.ofSeconds(2));
        lokiRequestFactory.setReadTimeout(Duration.ofSeconds(5));
        SimpleClientHttpRequestFactory jaegerRequestFactory = new SimpleClientHttpRequestFactory();
        jaegerRequestFactory.setConnectTimeout(Duration.ofSeconds(2));
        jaegerRequestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.loki = builder.clone()
                .baseUrl(properties.getLogs().getLokiUrl())
                .requestFactory(lokiRequestFactory)
                .build();
        this.jaeger = builder.clone()
                .baseUrl(properties.getTraces().getJaegerUrl())
                .requestFactory(jaegerRequestFactory)
                .build();
    }

    public List<LogEntry> getLogs(String serviceName, int lookbackMinutes, int limit) {
        validateQuery(serviceName, lookbackMinutes, limit);
        Instant end = Instant.now();
        Instant start = end.minusSeconds(lookbackMinutes * 60L);
        String logQl = "{service_name=\"" + serviceName + "\"}";
        URI uri = UriComponentsBuilder.fromPath("/loki/api/v1/query_range")
                .queryParam("query", "{query}")
                .queryParam("start", "{start}")
                .queryParam("end", "{end}")
                .queryParam("limit", "{limit}")
                .queryParam("direction", "backward")
                .encode()
                .buildAndExpand(Map.of(
                        "query",
                        logQl,
                        "start",
                        Long.toString(start.toEpochMilli() * 1_000_000L),
                        "end",
                        Long.toString(end.toEpochMilli() * 1_000_000L),
                        "limit",
                        Integer.toString(limit)))
                .toUri();
        try {
            JsonNode result = loki.get().uri(uri).retrieve().body(JsonNode.class);
            if (result == null || !"success".equals(result.path("status").asText())
                    || !result.path("data").path("result").isArray()) {
                throw unavailable("Loki returned an invalid log response");
            }
            List<LogEntry> entries = new ArrayList<>();
            for (JsonNode stream : result.path("data").path("result")) {
                JsonNode labels = stream.path("stream");
                for (JsonNode value : stream.path("values")) {
                    if (!value.isArray() || value.size() < 2) {
                        throw unavailable("Loki returned a malformed log entry");
                    }
                    try {
                        Instant timestamp = Instant.ofEpochSecond(
                                0, Long.parseLong(value.get(0).asText()));
                        entries.add(new LogEntry(
                                timestamp,
                                labels.path("service_name").asText(serviceName),
                                labels.path("severity_text").asText(
                                        labels.path("detected_level").asText("UNKNOWN")),
                                value.get(1).asText(),
                                nullableText(labels, "trace_id")));
                    } catch (NumberFormatException exception) {
                        throw unavailable("Loki returned an invalid log timestamp");
                    }
                }
            }
            return entries.stream()
                    .sorted(Comparator.comparing(LogEntry::timestamp).reversed())
                    .limit(limit)
                    .toList();
        } catch (RestClientException exception) {
            throw unavailable("Loki could not be reached");
        }
    }

    public List<TraceSpan> getTraces(String serviceName, int lookbackMinutes, int limit) {
        validateQuery(serviceName, lookbackMinutes, limit);
        try {
            JsonNode response = jaeger.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/traces")
                            .queryParam("service", serviceName)
                            .queryParam("lookback", lookbackMinutes + "m")
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("data").isArray()) {
                throw unavailable("Jaeger returned an invalid trace response");
            }
            List<TraceSpan> spans = new ArrayList<>();
            for (JsonNode trace : response.path("data")) {
                Map<String, String> serviceBySpan = new HashMap<>();
                JsonNode processes = trace.path("processes");
                for (JsonNode span : trace.path("spans")) {
                    String processId = span.path("processID").asText();
                    String spanService = processes.path(processId).path("serviceName").asText(serviceName);
                    serviceBySpan.put(span.path("spanID").asText(), spanService);
                }
                for (JsonNode span : trace.path("spans")) {
                    String spanService = serviceBySpan.get(span.path("spanID").asText());
                    String parentService = null;
                    for (JsonNode reference : span.path("references")) {
                        if ("CHILD_OF".equals(reference.path("refType").asText())) {
                            parentService = serviceBySpan.get(reference.path("spanID").asText());
                            if (parentService != null && parentService.equals(spanService)) {
                                parentService = null;
                            }
                            break;
                        }
                    }
                    long startMicros = span.path("startTime").asLong();
                    Map<String, String> tags = new LinkedHashMap<>();
                    for (JsonNode tag : span.path("tags")) {
                        String key = tag.path("key").asText();
                        if (!key.isBlank()) {
                            tags.put(key, tag.path("value").asText());
                        }
                    }
                    spans.add(new TraceSpan(
                            Instant.ofEpochSecond(startMicros / 1_000_000L, (startMicros % 1_000_000L) * 1_000L),
                            trace.path("traceID").asText(),
                            span.path("spanID").asText(),
                            spanService,
                            parentService,
                            span.path("operationName").asText(),
                            span.path("duration").asLong(),
                            tags));
                }
            }
            return spans.stream()
                    .sorted(Comparator.comparing(TraceSpan::timestamp).reversed())
                    .limit((long) limit * 20)
                    .toList();
        } catch (RestClientException exception) {
            throw unavailable("Jaeger could not be reached");
        }
    }

    private void validateQuery(String serviceName, int lookbackMinutes, int limit) {
        if (serviceName == null || !SERVICE_NAME.matcher(serviceName).matches()) {
            throw new IllegalArgumentException("serviceName must be a valid service identifier");
        }
        if (lookbackMinutes < 1 || lookbackMinutes > 1440 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("lookbackMinutes must be 1-1440 and limit must be 1-200");
        }
    }

    private String nullableText(JsonNode node, String key) {
        String text = node.path(key).asText();
        return text.isBlank() ? null : text;
    }

    private ApiException unavailable(String detail) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "https://aexa.dev/problems/telemetry-unavailable",
                "Telemetry unavailable",
                detail);
    }

    public record LogEntry(Instant timestamp, String serviceName, String severity, String message, String traceId) {}

    public record TraceSpan(
            Instant timestamp,
            String traceId,
            String spanId,
            String serviceName,
            String parentService,
            String operationName,
            long durationMicros,
            Map<String, String> tags) {}
}
