package com.marketai.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A missing route was returning 500 "An unexpected error occurred" with an ERROR-level stack
 * trace, discovered by hitting /actuator/health on a freshly rebuilt backend (which, separately,
 * was 404ing because spring-boot-starter-actuator had never been added despite SecurityConfig's
 * own permitAll list referencing it — fixed alongside this in the same pass).
 *
 * NoResourceFoundException is Spring's generic "nothing matched this path" signal and fires for
 * any unmapped URL, not just actuator — a typo'd endpoint, a stale bookmark, a removed route. It
 * must surface as 404, and it must not spam production logs at ERROR severity for a routine,
 * expected condition.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private WebRequest request(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(path);
        return new ServletWebRequest(req);
    }

    @Test
    @DisplayName("a missing route returns 404, not the generic 500")
    void missingRouteReturns404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "actuator/health");

        var response = handler.handleNoResourceFound(ex, request("/actuator/health"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getMessage()).isEqualTo("No such endpoint");
        assertThat(response.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("invalid input returns 400 with the actionable message, not an opaque 500")
    void invalidInputReturns400() {
        // Found live: saving a malformed PAN returned 500 "An unexpected error occurred",
        // telling the caller the server broke and discarding the one thing that would let them
        // fix it.
        var response = handler.handleIllegalArgument(
            new IllegalArgumentException("PAN must be 5 letters, 4 digits, then 1 letter"),
            request("/api/identity"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).contains("5 letters, 4 digits");
    }

    @Test
    @DisplayName("a genuinely unhandled exception still returns 500 through the catch-all")
    void trueServerErrorsStillReturn500() {
        var response = handler.handleGeneral(new RuntimeException("boom"), request("/api/whatever"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("a deliberate 409 keeps its status and reason instead of becoming a generic 500")
    void responseStatusKeepsStatusAndReason() {
        var response = handler.handleResponseStatus(
            new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                "No saved credit card matches this bill — add the card."),
            request("/api/review/7/decision"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getMessage()).contains("add the card");
    }
}
