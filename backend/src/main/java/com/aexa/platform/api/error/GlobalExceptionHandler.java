package com.aexa.platform.api.error;

import com.aexa.platform.infrastructure.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemResponse> handleApi(ApiException ex, HttpServletRequest request) {
        return problem(ex.getStatus(), ex.getType(), ex.getTitle(), ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::formatField)
                .orElse("Request is invalid");
        return problem(
                HttpStatus.BAD_REQUEST,
                "https://aexa.dev/problems/validation",
                "Validation failed",
                detail,
                request.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "https://aexa.dev/problems/validation",
                "Validation failed",
                "Request parameters failed validation",
                request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "https://aexa.dev/problems/validation",
                "Invalid request",
                ex.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ProblemResponse> handleAuth(RuntimeException ex, HttpServletRequest request) {
        return problem(
                HttpStatus.UNAUTHORIZED,
                "https://aexa.dev/problems/unauthorized",
                "Unauthorized",
                "Invalid username or password",
                request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> handleUnknown(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error", ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "https://aexa.dev/problems/internal",
                "Internal error",
                "An unexpected error occurred",
                request.getRequestURI());
    }

    private String formatField(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private ResponseEntity<ProblemResponse> problem(
            HttpStatus status, String type, String title, String detail, String instance) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        ProblemResponse body = new ProblemResponse(type, title, status.value(), detail, instance, correlationId);
        return ResponseEntity.status(status).body(body);
    }
}
