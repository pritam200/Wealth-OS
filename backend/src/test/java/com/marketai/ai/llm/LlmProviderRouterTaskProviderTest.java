package com.marketai.ai.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Email reading can use Gemini while app.llm.provider (everything else) stays on Ollama. */
class LlmProviderRouterTaskProviderTest {

    private OllamaProvider ollama;
    private GeminiLlmProvider gemini;
    private LlmProviderRouter router;

    private static LlmCompletion answer(String provider) {
        return LlmCompletion.builder().text("{}").provider(provider).model(provider).build();
    }

    @BeforeEach
    void setUp() {
        ollama = mock(OllamaProvider.class);
        gemini = mock(GeminiLlmProvider.class);
        router = new LlmProviderRouter(ollama, gemini);
        ReflectionTestUtils.setField(router, "configured", "ollama");
        ReflectionTestUtils.setField(router, "fallbackEnabled", true);
        when(ollama.complete(anyString(), anyString())).thenReturn(answer("ollama"));
        when(gemini.complete(anyString(), anyString())).thenReturn(answer("gemini"));
        when(ollama.isAvailable()).thenReturn(true);
        when(gemini.isAvailable()).thenReturn(true);
    }

    @Test
    @DisplayName("the task's preferred provider is used even though the default is Ollama")
    void preferredProviderWins() {
        assertThat(router.completeWith("gemini", "s", "p").getProvider()).isEqualTo("gemini");
        verify(ollama, never()).complete(anyString(), anyString());
    }

    @Test
    void blankPreferenceUsesTheDefault() {
        assertThat(router.completeWith("", "s", "p").getProvider()).isEqualTo("ollama");
    }

    @Test
    @DisplayName("a failing Gemini call (e.g. rate limit) falls back to Ollama for that call")
    void failedCallFallsBack() {
        when(gemini.complete(anyString(), anyString())).thenThrow(new LlmUnavailableException("HTTP 429"));
        assertThat(router.completeWith("gemini", "s", "p").getProvider()).isEqualTo("ollama");
    }

    @Test
    void noKeyFallsBackToOllama() {
        when(gemini.isAvailable()).thenReturn(false);
        assertThat(router.completeWith("gemini", "s", "p").getProvider()).isEqualTo("ollama");
    }

    @Test
    @DisplayName("with fallback off, a Gemini failure is reported, never answered by another model")
    void fallbackOffReportsTheFailure() {
        ReflectionTestUtils.setField(router, "fallbackEnabled", false);
        when(gemini.complete(anyString(), anyString())).thenThrow(new LlmUnavailableException("HTTP 429"));
        assertThatThrownBy(() -> router.completeWith("gemini", "s", "p"))
            .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("429");
        verify(ollama, never()).complete(anyString(), anyString());
    }
}
