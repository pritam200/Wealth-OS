package com.marketai.ai.llm.config;

import com.marketai.ai.llm.LlmProviderId;
import com.marketai.ai.llm.LlmTask;
import com.marketai.gmail.security.PasswordCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LlmConfigServiceTest {

    private LlmSettingsRepository repo;
    private PasswordCipher cipher;
    private LlmConfigService service;

    private static final String KEY = "AIzaTESTKEY000000000000000abcd";

    @BeforeEach
    void setUp() {
        repo = mock(LlmSettingsRepository.class);
        cipher = mock(PasswordCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(i -> "enc(" + i.getArgument(0) + ")");
        when(cipher.decrypt(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).replaceAll("^enc\\((.*)\\)$", "$1"));
        when(repo.findById(LlmSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new LlmConfigService(repo, cipher);
    }

    @Test
    @DisplayName("until saved, the environment's provider settings apply exactly as before")
    void environmentDefaults() {
        service.envProvider = "ollama";
        service.envEmailProvider = "gemini";
        service.envGeminiKey = KEY;

        var s = service.current();

        assertThat(s.saved()).isFalse();
        assertThat(s.route(LlmTask.EMAIL_EXTRACTION).provider()).isEqualTo(LlmProviderId.GEMINI);
        assertThat(s.route(LlmTask.AI_ADVISOR).provider()).isEqualTo(LlmProviderId.OLLAMA);
        assertThat(s.keySource()).isEqualTo(LlmConfigService.KeySource.ENVIRONMENT);
        assertThat(s.gemini().toString()).doesNotContain(KEY);
    }

    @Test
    @DisplayName("provider=none switches every task off")
    void noneDisablesAi() {
        service.envProvider = "none";
        assertThat(service.current().route(LlmTask.EMAIL_CLASSIFICATION).enabled()).isFalse();
    }

    @Test
    @DisplayName("the API key is stored encrypted, with only its last four characters in the clear")
    void keyIsEncryptedAtRest() {
        LlmSettings incoming = new LlmSettings();
        incoming.setPrivacyMode(PrivacyMode.CLOUD);
        incoming.setAiEnabled(true);

        var s = service.save(incoming, KEY, 1L);

        ArgumentCaptor<LlmSettings> row = ArgumentCaptor.forClass(LlmSettings.class);
        verify(repo).save(row.capture());
        assertThat(row.getValue().getGeminiApiKeyEncrypted()).isEqualTo("enc(" + KEY + ")");
        assertThat(row.getValue().getGeminiApiKeyLast4()).isEqualTo("abcd");
        assertThat(s.gemini().apiKey()).isEqualTo(KEY);
        assertThat(s.keySource()).isEqualTo(LlmConfigService.KeySource.SAVED);
    }

    @Test
    @DisplayName("Local AI routes nothing to the cloud, whatever the saved routes say")
    void localModeHasNoCloudRoutes() {
        LlmSettings row = new LlmSettings();
        row.setId(1L);
        row.setPrivacyMode(PrivacyMode.LOCAL);
        row.setAiEnabled(true);
        row.setFallbackEnabled(true);
        row.getRoutes().put(LlmTask.DOCUMENT_EXTRACTION, new LlmSettings.TaskRouteColumns("GEMINI", "gemini-x"));
        row.setGeminiApiKeyEncrypted("enc(" + KEY + ")");

        var s = service.fromRow(row);

        assertThat(s.route(LlmTask.DOCUMENT_EXTRACTION).enabled()).isFalse();
        assertThat(s.route(LlmTask.EMAIL_CLASSIFICATION).provider()).isEqualTo(LlmProviderId.OLLAMA);
        assertThat(s.fallback(LlmTask.EMAIL_CLASSIFICATION)).isEmpty();
    }

    @Test
    @DisplayName("hybrid keeps routine email local and sends statements and scans to the cloud")
    void hybridPreset() {
        LlmSettings row = new LlmSettings();
        row.setId(1L);
        row.setPrivacyMode(PrivacyMode.HYBRID);
        row.setAiEnabled(true);

        var s = service.fromRow(row);

        assertThat(s.route(LlmTask.EMAIL_CLASSIFICATION).provider()).isEqualTo(LlmProviderId.OLLAMA);
        assertThat(s.route(LlmTask.EMAIL_EXTRACTION).provider()).isEqualTo(LlmProviderId.OLLAMA);
        assertThat(s.route(LlmTask.DOCUMENT_EXTRACTION).provider()).isEqualTo(LlmProviderId.GEMINI);
        assertThat(s.route(LlmTask.DOCUMENT_TRANSCRIPTION).provider()).isEqualTo(LlmProviderId.GEMINI);
    }
}
