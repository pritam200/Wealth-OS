package com.marketai.ai.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A 429 used to be retried after 2-8 seconds, inside the per-minute window, then dropped to the local model. */
class GeminiRateLimitWaitTest {

    private static final String BODY = "{\"error\":{\"code\":429,\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\","
        + "\"retryDelay\":\"37s\"}]}}";

    @Test
    void waitsAsLongAsGeminiAsks() {
        assertThat(GeminiClient.rateLimitWait(BODY, 1)).isEqualTo(38_000);
    }

    @Test
    void neverLessThanAQuarterMinutePerAttemptAndCapped() {
        assertThat(GeminiClient.rateLimitWait(null, 1)).isEqualTo(15_000);
        assertThat(GeminiClient.rateLimitWait("{}", 2)).isEqualTo(30_000);
        assertThat(GeminiClient.rateLimitWait("{\"retryDelay\":\"600s\"}", 1)).isEqualTo(65_000);
    }
}
