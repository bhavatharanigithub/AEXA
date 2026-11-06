package com.aexa.platform.api.dto;

import jakarta.validation.constraints.NotNull;

public record AdaptiveControlRequest(@NotNull Boolean enabled) {}
