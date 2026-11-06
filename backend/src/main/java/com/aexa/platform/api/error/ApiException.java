package com.aexa.platform.api.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String title;

    public ApiException(HttpStatus status, String type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "https://aexa.dev/problems/not-found", "Not found", detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "https://aexa.dev/problems/conflict", "Conflict", detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(
                HttpStatus.UNAUTHORIZED, "https://aexa.dev/problems/unauthorized", "Unauthorized", detail);
    }

    public static ApiException serviceUnavailable(String detail) {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "https://aexa.dev/problems/service-unavailable",
                "Service unavailable",
                detail);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }
}
