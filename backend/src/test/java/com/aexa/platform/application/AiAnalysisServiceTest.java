package com.aexa.platform.application;

import com.aexa.platform.api.dto.AiAnalysisRequests.MetricObservation;
import com.aexa.platform.api.dto.AiAnalysisRequests.MetricWindowRequest;
import com.aexa.platform.api.dto.AiAnalysisRequests.RootCauseRequest;
import com.aexa.platform.api.dto.AiAnalysisRequests.ServiceMetadata;
import com.aexa.platform.api.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class AiAnalysisServiceTest {

    @Test
    void doesNotSerializeBeanValidationPredicatesToStrictMlSchema() {
        var timestamp = Instant.parse("2026-10-26T12:00:00Z");
        var observation = new MetricObservation(timestamp, "checkout-api", 0.8, null, 0.2);
        var metricRequest = new MetricWindowRequest("checkout-api", List.of(observation));
        var rootCauseRequest = new RootCauseRequest(
                timestamp,
                List.of(observation),
                List.of(),
                List.of(),
                List.of(new ServiceMetadata("checkout-api", "demo", "local", "0.1.0")));
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        var metricJson = mapper.valueToTree(metricRequest);
        var observationJson = metricJson.path("observations").get(0);
        var rootCauseJson = mapper.valueToTree(rootCauseRequest);

        assertThat(metricJson.has("service_consistent")).isFalse();
        assertThat(observationJson.has("metric_value_present")).isFalse();
        assertThat(rootCauseJson.has("evidence_bounded_to_incident")).isFalse();
        assertThat(rootCauseJson.has("service_metadata_complete")).isFalse();
        assertThat(observationJson.has("service_name")).isTrue();
    }

    @Test
    void forwardsValidatedRequestToMlEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        builder.baseUrl("http://ml.test");
        server.expect(requestTo("http://ml.test/api/v1/anomalies/detect"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"model_version\":\"v1\"}", MediaType.APPLICATION_JSON));

        var response = new AiAnalysisService(builder.build()).detectAnomalies(Map.of("samples", 6));

        assertThat(response.path("model_version").asText()).isEqualTo("v1");
        server.verify();
    }

    @Test
    void translatesUnavailableModelResponseToServiceUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        builder.baseUrl("http://ml.test");
        server.expect(requestTo("http://ml.test/api/v1/anomalies/detect"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> new AiAnalysisService(builder.build()).detectAnomalies(Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessage("The ML service or its trained model is unavailable")
                .extracting("status")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }
}
