package com.marketai.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, WebRequest request) {
        return buildError(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex, WebRequest request) {
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, WebRequest request) {
        return buildError(HttpStatus.UNAUTHORIZED, "Invalid email or password", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return buildError(HttpStatus.FORBIDDEN, "Access denied", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            errors.put(field, error.getDefaultMessage());
        });
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message("Input validation failed")
                .path(request.getDescription(false).replace("uri=", ""))
                .validationErrors(errors)
                .build();
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Input rejected by a service's own validation.
     *
     * <p>Without this, an {@code IllegalArgumentException} fell through to {@link #handleGeneral}
     * and surfaced as 500 "An unexpected error occurred" — telling the caller the server broke
     * when in fact their input was invalid, and discarding the one thing that would have let
     * them fix it. Saving a malformed PAN returned a 500 rather than "PAN must be 5 letters,
     * 4 digits, then 1 letter".
     *
     * <p>Surfacing the message is safe here because every such message in this codebase is
     * hand-written and deliberately describes the expected <em>format</em> without echoing the
     * rejected value back — see {@code FinancialIdentityService.save}, where quoting an invalid
     * PAN would put a government identifier into the client's error log.
     *
     * <p>Logged at WARN, not ERROR: bad input is an expected condition, and logging it at
     * ERROR is how genuine faults end up buried.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.warn("Rejected invalid input: {}", ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ErrorResponse> handleExternalApi(ExternalApiException ex, WebRequest request) {
        log.error("External API error: {}", ex.getMessage());
        return buildError(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void handleClientAbort(Exception ex) {
        log.debug("Client disconnected: {}", ex.getMessage());
    }

    /**
     * Spring's own "no route/static resource matched this path" exception. It fires for any
     * unmapped URL — a typo'd endpoint, a stale bookmark, a client hitting a removed route — and
     * previously fell through to {@link #handleGeneral}, which returned 500 "An unexpected error
     * occurred" and logged a full stack trace at ERROR level.
     *
     * That is wrong twice over: the client got the wrong status code for a routine 404 (harder to
     * tell "you made a mistake" from "we broke"), and every one of these routine, expected events
     * polluted production logs at ERROR severity — which is exactly the kind of noise that buries
     * a real error under a pile of harmless ones.
     *
     * Found by starting the rebuilt backend and hitting the actuator health check that
     * SecurityConfig's own permitAll list references — it returned 500, not the 404 a missing
     * route should. Also fixed in this pass: spring-boot-starter-actuator was missing from
     * pom.xml entirely, so the endpoint had never actually existed.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex, WebRequest request) {
        log.debug("No route for request: {}", ex.getMessage());
        return buildError(HttpStatus.NOT_FOUND, "No such endpoint", request);
    }

    /**
     * Status and reason chosen deliberately by the service that threw it — a 404 "not found", a
     * 409 "this bill has no saved card yet — add the card first". This used to fall through to
     * {@link #handleGeneral} and reach the client as 500 "An unexpected error occurred", so every
     * such explanation was discarded and the UI could only say that something broke. Reasons are
     * hand-written in this codebase, so returning them is safe.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) log.error("Request failed: {}", ex.getReason(), ex);
        else log.debug("Request refused ({}): {}", status.value(), ex.getReason());
        return buildError(status, ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, WebRequest request) {
        if (ex.getClass().getName().contains("ClientAbort")) {
            log.debug("Client disconnected: {}", ex.getMessage());
            return null;
        }
        log.error("Unhandled exception", ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> buildError(HttpStatus status, String message, WebRequest request) {
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getDescription(false).replace("uri=", ""))
                .build();
        return ResponseEntity.status(status).body(response);
    }
}
