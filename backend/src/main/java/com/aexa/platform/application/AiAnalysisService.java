package com.aexa.platform.application;

import com.aexa.platform.api.error.ApiException;
import com.aexa.platform.config.AexaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Service
public class AiAnalysisService {

    private final RestClient mlService;

    @Autowired
    public AiAnalysisService(RestClient.Builder builder, AexaProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getAi().getTimeoutSeconds()));
        this.mlService = builder.clone()
                .baseUrl(properties.getAi().getServiceUrl())
                .requestFactory(requestFactory)
                .build();
    }

    AiAnalysisService(RestClient mlService) {
        this.mlService = mlService;
    }

    public JsonNode detectAnomalies(Object request) {
        return post("/api/v1/anomalies/detect", request);
    }

    public JsonNode forecastDegradation(Object request) {
        return post("/api/v1/forecasts/degradation", request);
    }

    public JsonNode rankRootCauses(Object request) {
        return post("/api/v1/root-causes/rank", request);
    }

    private JsonNode post(String path, Object request) {
        try {
            JsonNode response =
                    mlService.post().uri(path).body(request).retrieve().body(JsonNode.class);
            if (response == null || !response.isObject()) {
                throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "https://aexa.dev/problems/ml-invalid-response",
                        "ML service error",
                        "The ML service returned an invalid response");
            }
            return response;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is5xxServerError()) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "https://aexa.dev/problems/ml-unavailable",
                        "ML service unavailable",
                        "The ML service or its trained model is unavailable");
            }
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "https://aexa.dev/problems/ml-request-rejected",
                    "ML service error",
                    "The ML service rejected the validated analysis request");
        } catch (RestClientException exception) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "https://aexa.dev/problems/ml-unavailable",
                    "ML service unavailable",
                    "The ML service could not be reached before its connection or response timeout");
        }
    }
}
