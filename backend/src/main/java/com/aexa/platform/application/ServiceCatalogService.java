package com.aexa.platform.application;

import com.aexa.platform.api.dto.CreateServiceRequest;
import com.aexa.platform.api.dto.ServiceResponse;
import com.aexa.platform.api.error.ApiException;
import com.aexa.platform.domain.catalog.RegisteredService;
import com.aexa.platform.infrastructure.persistence.RegisteredServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ServiceCatalogService {

    private final RegisteredServiceRepository repository;

    public ServiceCatalogService(RegisteredServiceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> list() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ServiceResponse get(UUID id) {
        return repository
                .findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("Service not found: " + id));
    }

    @Transactional
    public ServiceResponse create(CreateServiceRequest request) {
        if (repository.existsByNameAndEnvironment(request.name(), request.environment())) {
            throw ApiException.conflict(
                    "Service already registered: " + request.name() + " / " + request.environment());
        }
        RegisteredService saved = repository.save(RegisteredService.create(
                request.name(),
                request.namespace(),
                request.environment(),
                request.version(),
                request.displayName(),
                request.description(),
                request.owner()));
        return toResponse(saved);
    }

    private ServiceResponse toResponse(RegisteredService service) {
        return new ServiceResponse(
                service.getId(),
                service.getName(),
                service.getNamespace(),
                service.getEnvironment(),
                service.getVersion(),
                service.getDisplayName(),
                service.getDescription(),
                service.getOwner(),
                service.getCreatedAt(),
                service.getUpdatedAt());
    }
}
