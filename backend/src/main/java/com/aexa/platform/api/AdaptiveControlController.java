package com.aexa.platform.api;

import com.aexa.platform.api.dto.AdaptiveAuditResponse;
import com.aexa.platform.api.dto.AdaptiveControlRequest;
import com.aexa.platform.api.dto.AdaptiveControlStateResponse;
import com.aexa.platform.application.AdaptiveControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/adaptive")
public class AdaptiveControlController {

    private final AdaptiveControlService adaptiveControlService;

    public AdaptiveControlController(AdaptiveControlService adaptiveControlService) {
        this.adaptiveControlService = adaptiveControlService;
    }

    @GetMapping("/state")
    public AdaptiveControlStateResponse state() {
        return adaptiveControlService.getState();
    }

    @GetMapping("/audit")
    public List<AdaptiveAuditResponse> audit(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return adaptiveControlService.getAudit(limit);
    }

    @PutMapping("/enabled")
    public AdaptiveControlStateResponse setEnabled(@Valid @RequestBody AdaptiveControlRequest request) {
        return adaptiveControlService.setEnabled(request.enabled());
    }

    @PostMapping("/evaluate")
    public AdaptiveControlStateResponse evaluate() {
        return adaptiveControlService.evaluateNow();
    }

    @PostMapping("/rollback")
    public AdaptiveControlStateResponse rollback() {
        return adaptiveControlService.rollback();
    }
}
