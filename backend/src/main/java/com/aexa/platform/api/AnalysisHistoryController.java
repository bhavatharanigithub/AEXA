package com.aexa.platform.api;

import com.aexa.platform.application.HistoricalAnalysisService;
import com.aexa.platform.application.HistoricalAnalysisService.StoredAnalysis;
import com.aexa.platform.application.HistoricalAnalysisService.StoredForecast;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisHistoryController {

    private final HistoricalAnalysisService analysisService;

    public AnalysisHistoryController(HistoricalAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/anomalies/run")
    public JsonNode runAnomalies(
            @RequestParam @NotBlank @Size(max = 128) String serviceName,
            @RequestParam(defaultValue = "60") @Min(5) @Max(60) int lookbackMinutes) {
        return analysisService.detectAnomalies(serviceName, lookbackMinutes);
    }

    @GetMapping("/anomalies")
    public List<StoredAnalysis> anomalies(
            @RequestParam(required = false) @Size(max = 128) String serviceName,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return analysisService.anomalies(serviceName, limit);
    }

    @PostMapping("/forecasts/run")
    public JsonNode runForecast(
            @RequestParam @NotBlank @Size(max = 128) String serviceName,
            @RequestParam(defaultValue = "60") @Min(5) @Max(60) int lookbackMinutes) {
        return analysisService.forecast(serviceName, lookbackMinutes);
    }

    @GetMapping("/forecasts")
    public List<StoredForecast> forecasts(
            @RequestParam(required = false) @Size(max = 128) String serviceName,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return analysisService.forecasts(serviceName, limit);
    }
}
