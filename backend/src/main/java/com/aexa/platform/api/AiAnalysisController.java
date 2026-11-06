package com.aexa.platform.api;

import com.aexa.platform.api.dto.AiAnalysisRequests.MetricWindowRequest;
import com.aexa.platform.api.dto.AiAnalysisRequests.RootCauseRequest;
import com.aexa.platform.application.AiAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    public AiAnalysisController(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    @PostMapping("/anomalies/detect")
    public JsonNode detectAnomalies(@Valid @RequestBody MetricWindowRequest request) {
        return aiAnalysisService.detectAnomalies(request);
    }

    @PostMapping("/forecasts/degradation")
    public JsonNode forecastDegradation(@Valid @RequestBody MetricWindowRequest request) {
        return aiAnalysisService.forecastDegradation(request);
    }

    @PostMapping("/root-causes/rank")
    public JsonNode rankRootCauses(@Valid @RequestBody RootCauseRequest request) {
        return aiAnalysisService.rankRootCauses(request);
    }
}
