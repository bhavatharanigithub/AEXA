package com.aexa.platform.api;

import com.aexa.platform.api.dto.IncidentListResponse;
import com.aexa.platform.api.dto.IncidentResponse;
import com.aexa.platform.application.IncidentQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentQueryService incidentQueryService;

    public IncidentController(IncidentQueryService incidentQueryService) {
        this.incidentQueryService = incidentQueryService;
    }

    @GetMapping
    public IncidentListResponse list() {
        return new IncidentListResponse(incidentQueryService.list());
    }

    @GetMapping("/{id}")
    public IncidentResponse get(@PathVariable UUID id) {
        return incidentQueryService.get(id);
    }
}
