package com.aexa.platform.api.error;

public record ProblemResponse(
        String type, String title, int status, String detail, String instance, String correlationId) {}
