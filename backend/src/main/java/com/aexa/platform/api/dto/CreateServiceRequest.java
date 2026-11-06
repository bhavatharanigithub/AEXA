package com.aexa.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateServiceRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 128) String namespace,
        @NotBlank @Size(max = 32) String environment,
        @Size(max = 64) String version,
        @NotBlank @Size(max = 128) String displayName,
        @Size(max = 1024) String description,
        @Size(max = 128) String owner) {}
