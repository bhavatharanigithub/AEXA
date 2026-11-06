package com.aexa.platform.api;

import com.aexa.platform.api.dto.CreateServiceRequest;
import com.aexa.platform.api.dto.ServiceListResponse;
import com.aexa.platform.api.dto.ServiceResponse;
import com.aexa.platform.application.ServiceCatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceCatalogController {

    private final ServiceCatalogService catalogService;

    public ServiceCatalogController(ServiceCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public ServiceListResponse list() {
        return new ServiceListResponse(catalogService.list());
    }

    @GetMapping("/{id}")
    public ServiceResponse get(@PathVariable UUID id) {
        return catalogService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceResponse create(@Valid @RequestBody CreateServiceRequest request) {
        return catalogService.create(request);
    }
}
