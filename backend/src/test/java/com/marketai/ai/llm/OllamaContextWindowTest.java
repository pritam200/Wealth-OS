package com.marketai.ai.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaContextWindowTest {

    private static LlmRequest request(int systemChars, int userChars, Integer maxTokens) {
        return new LlmRequest("s".repeat(systemChars), "u".repeat(userChars), "m", true, 0, maxTokens, 60, null);
    }

    @Test
    @DisplayName("the window always holds the whole extraction prompt plus the answer — never Ollama's 4096 default")
    void windowFitsThePrompt() {
        assertThat(OllamaProvider.contextWindow(request(100, 100, null))).isEqualTo(8192);
        // The extraction prompt (~8.4k chars) plus a full 8,000-char chunk, with room for the JSON.
        int w = OllamaProvider.contextWindow(request(8_400, 8_100, null));
        assertThat(w).isGreaterThanOrEqualTo(8_400 / 3 + 8_100 / 3 + OllamaProvider.ANSWER_RESERVE).isEqualTo(16384);
        assertThat(OllamaProvider.contextWindow(request(200_000, 0, null))).isEqualTo(OllamaProvider.MAX_CONTEXT);
    }
}
