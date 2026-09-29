package com.marketai.ai.llm;

import com.marketai.ai.client.GeminiClient;
import com.marketai.ai.llm.config.LlmConfigService;
import com.marketai.ai.llm.config.LlmConfigService.Route;
import com.marketai.ai.llm.config.LlmConfigService.Snapshot;
import com.marketai.ai.llm.config.PrivacyMode;
import com.marketai.ai.llm.usage.LlmUsageService;
import com.marketai.ai.prompt.PromptLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmServiceTest {

    private LlmProvider gemini;
    private LlmProvider ollama;
    private LlmConfigService config;
    private LlmUsageService usage;
    private LlmService service;

    private static final ProviderSettings GEMINI = new ProviderSettings(LlmProviderId.GEMINI,
        "https://gemini.example", "test-key-0000000000000000", "gemini-test", 0.7, null, 60);
    private static final ProviderSettings OLLAMA = new ProviderSettings(LlmProviderId.OLLAMA,
        "http://localhost:11434", null, "qwen-test", 0.0, null, 120);

    @BeforeEach
    void setUp() {
        gemini = mock(LlmProvider.class);
        ollama = mock(LlmProvider.class);
        when(gemini.id()).thenReturn(LlmProviderId.GEMINI);
        when(ollama.id()).thenReturn(LlmProviderId.OLLAMA);
        when(gemini.listModels(any())).thenReturn(List.of(new ModelInfo("gemini-test",
            EnumSet.allOf(LlmCapability.class), true)));
        when(ollama.listModels(any())).thenReturn(List.of(new ModelInfo("qwen-test",
            EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON), true)));
        when(gemini.generate(any(), any())).thenReturn(answer("gemini"));
        when(ollama.generate(any(), any())).thenReturn(answer("ollama"));
        config = mock(LlmConfigService.class);
        usage = mock(LlmUsageService.class);
        service = new LlmService(config, new LlmModelCatalog(List.of(gemini, ollama)), usage);
    }

    private static LlmCompletion answer(String provider) {
        return LlmCompletion.builder().text("{}").provider(provider).build();
    }

    /** Every task on {@code provider}; the given overrides on top. */
    private void configure(PrivacyMode mode, boolean fallback, LlmProviderId provider, Map<LlmTask, Route> overrides) {
        Map<LlmTask, Route> routes = new EnumMap<>(LlmTask.class);
        for (LlmTask t : LlmTask.values()) {
            routes.put(t, new Route(t, provider, provider == LlmProviderId.GEMINI ? "gemini-test" : "qwen-test"));
        }
        routes.putAll(overrides);
        when(config.current()).thenReturn(new Snapshot(true, mode, fallback, 1, GEMINI, OLLAMA, routes,
            true, LlmConfigService.KeySource.SAVED, "0000", null));
    }

    @Test
    @DisplayName("each task goes to its own configured provider and model")
    void routesPerTask() {
        configure(PrivacyMode.CUSTOM, false, LlmProviderId.OLLAMA,
            Map.of(LlmTask.DOCUMENT_EXTRACTION, new Route(LlmTask.DOCUMENT_EXTRACTION, LlmProviderId.GEMINI, "gemini-test")));

        assertThat(service.complete(LlmTask.DOCUMENT_EXTRACTION, PromptLibrary.TRANSACTION_EXTRACTION, "p").getProvider()).isEqualTo("gemini");
        assertThat(service.complete(LlmTask.EMAIL_CLASSIFICATION, PromptLibrary.EMAIL_CLASSIFICATION, "p").getProvider()).isEqualTo("ollama");
    }

    @Test
    @DisplayName("the completion records the prompt version, and structured tasks run at temperature 0")
    void recordsPromptVersionAndPinsTemperature() {
        configure(PrivacyMode.CLOUD, false, LlmProviderId.GEMINI, Map.of());

        LlmCompletion c = service.complete(LlmTask.EMAIL_EXTRACTION, PromptLibrary.TRANSACTION_EXTRACTION, "p");

        assertThat(c.getPromptVersion()).isEqualTo("transaction-extraction-v2");
        assertThat(c.isFallbackUsed()).isFalse();
        assertThat(c.extractionVersion()).isEqualTo("gemini:gemini-test/transaction-extraction-v2");
        ArgumentCaptor<LlmRequest> req = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gemini).generate(any(), req.capture());
        assertThat(req.getValue().temperature()).isZero();
        assertThat(req.getValue().json()).isTrue();

        service.complete(LlmTask.GENERAL_ASSISTANT, PromptLibrary.GENERAL_ANALYST, "p");
        verify(gemini, times(2)).generate(any(), req.capture());
        assertThat(req.getValue().temperature()).isEqualTo(0.7);
        assertThat(req.getValue().json()).isFalse();
    }

    @Test
    @DisplayName("with fallback on, a failing provider is retried, then the other one answers — and that is recorded")
    void fallbackWhenEnabled() {
        configure(PrivacyMode.CLOUD, true, LlmProviderId.GEMINI, Map.of());
        when(gemini.generate(any(), any())).thenThrow(new LlmUnavailableException(LlmErrorCategory.TIMEOUT, "timed out"));

        LlmCompletion c = service.complete(LlmTask.EMAIL_EXTRACTION, PromptLibrary.TRANSACTION_EXTRACTION, "p");

        assertThat(c.getProvider()).isEqualTo("ollama");
        assertThat(c.isFallbackUsed()).isTrue();
        verify(gemini, times(2)).generate(any(), any()); // one retry (maxRetries = 1)
        verify(usage).record(eq(LlmTask.EMAIL_EXTRACTION), eq("ollama"), eq("qwen-test"), anyString(), anyLong(),
            eq(true), isNull(), eq(true), any(), any());
    }

    @Test
    @DisplayName("with fallback off, the failure is reported — never silently answered by another provider")
    void noFallbackWhenDisabled() {
        configure(PrivacyMode.CLOUD, false, LlmProviderId.GEMINI, Map.of());
        when(gemini.generate(any(), any())).thenThrow(new LlmUnavailableException(LlmErrorCategory.AUTH_INVALID, "HTTP 401"));

        assertThatThrownBy(() -> service.complete(LlmTask.EMAIL_EXTRACTION, PromptLibrary.TRANSACTION_EXTRACTION, "p"))
            .isInstanceOf(LlmUnavailableException.class)
            .extracting(e -> ((LlmUnavailableException) e).getCategory()).isEqualTo(LlmErrorCategory.AUTH_INVALID);
        verify(ollama, never()).generate(any(), any());
        verify(gemini, times(1)).generate(any(), any()); // an invalid key is not retried
    }

    @Test
    @DisplayName("Local AI never sends anything to the cloud, fallback included")
    void localModeNeverUsesTheCloud() {
        configure(PrivacyMode.LOCAL, true, LlmProviderId.OLLAMA, Map.of());
        when(ollama.generate(any(), any())).thenThrow(new LlmUnavailableException(LlmErrorCategory.NOT_RUNNING, "down"));

        assertThatThrownBy(() -> service.complete(LlmTask.DOCUMENT_EXTRACTION, PromptLibrary.TRANSACTION_EXTRACTION, "p"))
            .isInstanceOf(LlmUnavailableException.class);
        verify(gemini, never()).generate(any(), any());
    }

    @Test
    @DisplayName("a model without vision is never given a scan to read")
    void capabilityIsCheckedBeforeTheCall() {
        configure(PrivacyMode.CUSTOM, false, LlmProviderId.OLLAMA, Map.of());

        assertThatThrownBy(() -> service.transcribe(PromptLibrary.SCAN_TRANSCRIPTION, "p",
                List.of(new GeminiClient.InlineImage("image/png", new byte[]{1}))))
            .isInstanceOf(LlmUnavailableException.class)
            .extracting(e -> ((LlmUnavailableException) e).getCategory()).isEqualTo(LlmErrorCategory.CAPABILITY_MISSING);
        verify(ollama, never()).generate(any(), any());
        assertThat(service.canReadImages()).isFalse();
    }

    @Test
    @DisplayName("a switched-off task is unavailable, not routed somewhere else")
    void disabledTask() {
        configure(PrivacyMode.CUSTOM, false, LlmProviderId.OLLAMA,
            Map.of(LlmTask.AI_ADVISOR, new Route(LlmTask.AI_ADVISOR, null, null)));

        assertThat(service.isAvailable(LlmTask.AI_ADVISOR)).isFalse();
        assertThatThrownBy(() -> service.complete(LlmTask.AI_ADVISOR, PromptLibrary.ADVISOR_ROUTING, "q"))
            .isInstanceOf(LlmUnavailableException.class);
        verify(ollama, never()).generate(any(), any());
    }
}
