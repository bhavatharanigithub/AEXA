package com.aexa.platform.application;

import com.aexa.platform.config.AexaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PrometheusTelemetryServiceTest {

    @Test
    void summarizesRealPrometheusSeriesAndRanksServicesByName() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prometheus.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String response = """
                {"status":"success","data":{"result":[
                  {"metric":{"service_name":"inventory-api"},"value":[1,"1.5"]},
                  {"metric":{"service_name":"checkout-api"},"value":[1,"2"]}
                ]}}
                """;
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/query?query=")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/query?query=")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"result":[
                          {"metric":{"service_name":"checkout-api"},"value":[1,"0.2"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/query?query=")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"result":[
                          {"metric":{"service_name":"checkout-api"},"value":[1,"0.15"]},
                          {"metric":{"service_name":"inventory-api"},"value":[1,"0.08"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/query?query=")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"result":[
                          {"metric":{},"value":[1,"0.12"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        PrometheusTelemetryService service =
                new PrometheusTelemetryService(builder, new AexaProperties());
        var overview = service.getOverview();

        assertThat(overview.available()).isTrue();
        assertThat(overview.hasData()).isTrue();
        assertThat(overview.requestsPerSecond()).isEqualTo(3.5);
        assertThat(overview.errorRate()).isCloseTo(0.2 / 3.5, org.assertj.core.data.Offset.offset(0.00001));
        assertThat(overview.p95LatencySeconds()).isEqualTo(0.12);
        assertThat(overview.services()).hasSize(2);
        assertThat(overview.services().get(0).serviceName()).isEqualTo("checkout-api");
        assertThat(overview.services().get(0).errorRate()).isEqualTo(0.1);
        server.verify();
    }

    @Test
    void returnsExplicitEmptyStateWhenPrometheusHasNoSamples() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prometheus.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (int index = 0; index < 4; index++) {
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/query?query=")))
                    .andRespond(withSuccess(
                            "{\"status\":\"success\",\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        }

        var overview = new PrometheusTelemetryService(builder, new AexaProperties()).getOverview();

        assertThat(overview.available()).isTrue();
        assertThat(overview.hasData()).isFalse();
        assertThat(overview.requestsPerSecond()).isNull();
        assertThat(overview.services()).isEmpty();
        server.verify();
    }

    @Test
    void reportsPrometheusServerFailureInsteadOfReturningSyntheticTelemetry() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prometheus.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/query?query=")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> new PrometheusTelemetryService(builder, new AexaProperties()).getOverview())
                .hasMessage("Prometheus could not be reached");
        server.verify();
    }

    @Test
    void encodesPromqlTemplateBracesInAdaptiveRangeQueries() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prometheus.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String sampleTime = Long.toString(Instant.now().minusSeconds(60).getEpochSecond());
        String response = """
                {"status":"success","data":{"result":[
                  {"metric":{"service_name":"checkout-api"},"values":[[%s,"0.2"]]}
                ]}}
                """.formatted(sampleTime);
        for (int index = 0; index < 3; index++) {
            int queryIndex = index;
            server.expect(request -> {
                        assertThat(request.getURI().getPath()).isEqualTo("/api/v1/query_range");
                        if (queryIndex == 1) {
                            assertThat(request.getURI().getRawQuery()).contains("%7B", "%7D", "status_code");
                        }
                    })
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }

        var observations = new PrometheusTelemetryService(builder, new AexaProperties())
                .getRecentObservations("checkout-api", 15);

        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).requestRate()).isEqualTo(0.2);
        server.verify();
    }
}
