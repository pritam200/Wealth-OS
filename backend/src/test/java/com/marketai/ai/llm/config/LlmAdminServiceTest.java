package com.marketai.ai.llm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.llm.*;
import com.marketai.ai.llm.usage.LlmUsageService;
import com.marketai.gmail.security.PasswordCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LlmAdminServiceTest {

    private LlmProvider ollama;
    private LlmProvider gemini;
    private LlmSettingsRepository repo;
    private LlmAdminService admin;

    private static final String KEY = "AIzaTESTKEY000000000000000wxyz";

    @BeforeEach
    void setUp() {
        ollama = mock(LlmProvider.class);
        gemini = mock(LlmProvider.class);
        when(ollama.id()).thenReturn(LlmProviderId.OLLAMA);
        when(gemini.id()).thenReturn(LlmProviderId.GEMINI);
        when(ollama.listModels(any())).thenReturn(List.of(
            new ModelInfo("qwen2.5:14b", EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON, LlmCapability.TOOLS), true),
            new ModelInfo("gemma3:4b", EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON, LlmCapability.VISION), true)));
        when(gemini.listModels(any())).thenReturn(List.of(new ModelInfo("gemini-test", EnumSet.allOf(LlmCapability.class), true)));
        repo = mock(LlmSettingsRepository.class);
        when(repo.findById(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        PasswordCipher cipher = mock(PasswordCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        when(cipher.decrypt(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).substring(4));
        LlmConfigService config = new LlmConfigService(repo, cipher);
        config.envOllamaModel = "qwen2.5:14b";
        admin = new LlmAdminService(config, new LlmModelCatalog(List.of(ollama, gemini)), mock(LlmUsageService.class),
            new LlmJsonParser(new ObjectMapper()));
    }

    private static LlmAdminService.ConfigUpdate update(String mode, List<LlmAdminService.RouteUpdate> routes, String key) {
        return new LlmAdminService.ConfigUpdate(true, mode, false, 1,
            new LlmAdminService.ProviderUpdate("gemini-test", 0.5, null, 60, null),
            new LlmAdminService.ProviderUpdate("qwen2.5:14b", 0.0, null, 120, "http://localhost:11434"),
            routes, key, false);
    }

    @Test
    @DisplayName("a model without vision can't be assigned to reading scans")
    void refusesAModelLackingACapability() {
        var u = update("CUSTOM", List.of(new LlmAdminService.RouteUpdate("DOCUMENT_TRANSCRIPTION", "OLLAMA", "qwen2.5:14b")), null);
        assertThatThrownBy(() -> admin.save(u, 1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Vision");
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("Local AI gives scans to an installed vision model instead of sending them out")
    void localModePicksAnInstalledVisionModel() {
        var view = admin.save(update("LOCAL", List.of(), null), 1L);
        var scan = view.getTasks().stream().filter(t -> t.getTask().equals("DOCUMENT_TRANSCRIPTION")).findFirst().orElseThrow();
        assertThat(scan.getProvider()).isEqualTo("OLLAMA");
        assertThat(scan.getModel()).isEqualTo("gemma3:4b");
    }

    @Test
    @DisplayName("the saved key is never returned — only a hint of its last four characters")
    void keyIsNeverReturned() {
        var view = admin.save(update("CLOUD", List.of(), KEY), 1L);
        var g = view.getProviders().stream().filter(p -> p.getId().equals("GEMINI")).findFirst().orElseThrow();
        assertThat(g.isApiKeySet()).isTrue();
        assertThat(g.getApiKeyHint()).isEqualTo("••••wxyz");
        assertThat(view.toString()).doesNotContain(KEY);
    }

    @Test
    void rejectsSomethingThatIsNotAKey() {
        assertThatThrownBy(() -> admin.save(update("CLOUD", List.of(), "not a key"), 1L))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("test connection reports a missing local model with the command to install it")
    void testConnectionReportsMissingModel() {
        var r = admin.test(new LlmAdminService.TestRequest("OLLAMA", "llama3", null, null));
        assertThat(r.isOk()).isFalse();
        assertThat(r.getSteps()).extracting(LlmAdminService.Step::ok).containsExactly(true, false);
        assertThat(r.getSteps().get(1).detail()).contains("ollama pull llama3");
    }

    @Test
    @DisplayName("an unreachable Ollama is reported as not running, without the raw error")
    void testConnectionReportsOllamaDown() {
        when(ollama.listModels(any())).thenThrow(new LlmUnavailableException(LlmErrorCategory.NOT_RUNNING, "Connection refused: localhost/127.0.0.1:11434"));
        var r = admin.test(new LlmAdminService.TestRequest("OLLAMA", null, null, null));
        assertThat(r.getSteps().get(0).detail()).isEqualTo("Ollama is not running at the configured address");
    }

    @Test
    @DisplayName("a full pass: reachable, model present, valid JSON")
    void testConnectionPasses() {
        when(ollama.generate(any(), any())).thenReturn(LlmCompletion.builder().text("{\"ok\": true}").build());
        var r = admin.test(new LlmAdminService.TestRequest("OLLAMA", "qwen2.5:14b", null, null));
        assertThat(r.isOk()).isTrue();
        assertThat(r.getCapabilities()).contains("JSON", "TOOLS").doesNotContain("VISION");
    }
}
