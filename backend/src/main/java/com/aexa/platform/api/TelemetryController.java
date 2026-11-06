package com.aexa.platform.api;

import com.aexa.platform.api.dto.TelemetryOverviewResponse;
import com.aexa.platform.api.dto.AiAnalysisRequests.MetricObservation;
import com.aexa.platform.application.PrometheusTelemetryService;
import com.aexa.platform.application.TelemetryEvidenceService;
import com.aexa.platform.application.TelemetryEvidenceService.LogEntry;
import com.aexa.platform.application.TelemetryEvidenceService.TraceSpan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final PrometheusTelemetryService telemetryService;
    private final TelemetryEvidenceService evidenceService;

    public TelemetryController(
            PrometheusTelemetryService telemetryService, TelemetryEvidenceService evidenceService) {
        this.telemetryService = telemetryService;
        this.evidenceService = evidenceService;
    }

    @GetMapping("/overview")
    public TelemetryOverviewResponse overview() {
        return telemetryService.getOverview();
    }

    @GetMapping("/metrics")
    public List<MetricObservation> metrics(
            @RequestParam @NotBlank @Size(max = 128) String serviceName,
            @RequestParam(defaultValue = "60") @Min(5) @Max(60) int lookbackMinutes) {
        return telemetryService.getRecentObservations(serviceName, lookbackMinutes);
    }

    @GetMapping("/logs")
    public List<LogEntry> logs(
            @RequestParam @NotBlank @Size(max = 128) String serviceName,
            @RequestParam(defaultValue = "60") @Min(1) @Max(1440) int lookbackMinutes,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return evidenceService.getLogs(serviceName, lookbackMinutes, limit);
    }

    @GetMapping("/traces")
    public List<TraceSpan> traces(
            @RequestParam @NotBlank @Size(max = 128) String serviceName,
            @RequestParam(defaultValue = "60") @Min(1) @Max(1440) int lookbackMinutes,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return evidenceService.getTraces(serviceName, lookbackMinutes, limit);
    }
}
