package com.marketai.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.llm.LlmErrorCategory;
import com.marketai.ai.llm.LlmProviderId;
import com.marketai.ai.llm.LlmRequest;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.ai.llm.ProviderSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Research is interactive: someone is waiting on the screen. A rate limit must fail it at once
 * with when to try again, not hold the request through minutes of back-off meant for the sync.
 */
class GeminiInteractiveRateLimitTest {

    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private GeminiClient client(String body429) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] out = body429.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(429, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return new GeminiClient(WebClient.builder(), new ObjectMapper());
    }

    private ProviderSettings settings() {
        return new ProviderSettings(LlmProviderId.GEMINI, "http://127.0.0.1:" + server.getAddress().getPort(),
                "dummy-test-key", "gemini-2.5-flash", 0.0, null, 10);
    }

    private static LlmRequest interactive() {
        return new LlmRequest("sys", "subject", "gemini-2.5-flash", true, 0.0, null, 10, List.of(), false, true);
    }

    @Test
    @DisplayName("a per-minute 429 fails an interactive call at once, saying when to retry, and later calls don't queue behind it")
    void perMinuteLimitFailsFast() throws Exception {
        GeminiClient c = client("{\"error\":{\"code\":429,\"details\":[{\"retryDelay\":\"20s\"}]}}");
        long started = System.currentTimeMillis();

        LlmUnavailableException e = catchThrowableOfType(LlmUnavailableException.class,
                () -> c.generateContent(settings(), interactive()));

        assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
        assertThat(e.getCategory()).isEqualTo(LlmErrorCategory.RATE_LIMITED);
        assertThat(e.getRetryAt()).isBetween(Instant.now().plusSeconds(10), Instant.now().plus(Duration.ofSeconds(30)));
        assertThat(calls.get()).isEqualTo(1);

        // The back-off it set applies to everyone; another interactive call is refused without a request.
        LlmUnavailableException again = catchThrowableOfType(LlmUnavailableException.class,
                () -> c.generateContent(settings(), interactive()));
        assertThat(again.getCategory()).isEqualTo(LlmErrorCategory.RATE_LIMITED);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a spent daily quota is reported with its reset time, and the model is not called again")
    void dailyQuotaCarriesItsReset() throws Exception {
        GeminiClient c = client("{\"error\":{\"code\":429,\"details\":[{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}}");

        LlmUnavailableException e = catchThrowableOfType(LlmUnavailableException.class,
                () -> c.generateContent(settings(), interactive()));

        assertThat(e.getRetryAt()).isAfter(Instant.now());
        assertThat(c.quotaResetsAt("gemini-2.5-flash")).isEqualTo(e.getRetryAt());
        assertThat(c.dailyQuotaSpent("gemini-2.5-flash")).isTrue();
        catchThrowableOfType(LlmUnavailableException.class, () -> c.generateContent(settings(), interactive()));
        assertThat(calls.get()).isEqualTo(1);
    }
}
