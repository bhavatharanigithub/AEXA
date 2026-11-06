package com.aexa.platform.application;

import com.aexa.platform.api.dto.IncidentResponse;
import com.aexa.platform.api.error.ApiException;
import com.aexa.platform.domain.incident.Incident;
import com.aexa.platform.infrastructure.persistence.IncidentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class IncidentQueryService {

    private final IncidentRepository repository;

    public IncidentQueryService(IncidentRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> list() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public IncidentResponse get(UUID id) {
        return repository
                .findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("Incident not found: " + id));
    }

    private IncidentResponse toResponse(Incident incident) {
        List<String> services = incident.getAffectedServices().stream()
                .map(service -> service.getName())
                .sorted()
                .toList();
        return new IncidentResponse(
                incident.getId(),
                incident.getTitle(),
                incident.getStatus().name(),
                incident.getSeverity().name(),
                incident.getSummary(),
                incident.getStartedAt(),
                incident.getResolvedAt(),
                incident.getCreatedBy(),
                services);
    }
}
