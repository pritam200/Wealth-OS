package com.marketai.ai.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single entry point the reasoning layers call. Picks the configured provider
 * (app.llm.provider = ollama | gemini | none) and falls back to the other only when the
 * primary is unreachable, so a local-first setup keeps working if Ollama is stopped.
 *
 * app.llm.provider=none disables every LLM path outright — the deterministic parsers and
 * calculations continue to work unchanged, which is the property that makes the AI layer
 * strictly additive rather than load-bearing.
 */
@Component
@Slf4j
public class LlmProviderRouter {

    private final OllamaProvider ollama;
    private final GeminiLlmProvider gemini;

    @Value("${app.llm.provider:ollama}")
    private String configured;

    @Value("${app.llm.fallback-enabled:true}")
    private boolean fallbackEnabled;

    public LlmProviderRouter(OllamaProvider ollama, GeminiLlmProvider gemini) {
        this.ollama = ollama;
        this.gemini = gemini;
    }

    public boolean isEnabled() {
        return !"none".equalsIgnoreCase(configured) && active() != null;
    }

    /** The provider that should serve the next call, or null when none is usable. */
    public LlmProvider active() {
        if ("none".equalsIgnoreCase(configured)) return null;

        LlmProvider primary = "gemini".equalsIgnoreCase(configured) ? gemini : ollama;
        if (primary.isAvailable()) return primary;

        if (fallbackEnabled) {
            LlmProvider secondary = primary == ollama ? gemini : ollama;
            if (secondary.isAvailable()) {
                log.info("Primary LLM provider {} unavailable — using {}", primary.describe(), secondary.describe());
                return secondary;
            }
        }
        return null;
    }

    /**
     * @throws LlmUnavailableException when no provider is usable, so callers are forced to
     *         handle the "no model" case explicitly rather than silently receiving null.
     */
    public LlmCompletion complete(String systemInstruction, String userPrompt) {
        LlmProvider p = active();
        if (p == null) throw new LlmUnavailableException("No LLM provider is available");
        return p.complete(systemInstruction, userPrompt);
    }

    public String describeActive() {
        LlmProvider p = active();
        return p == null ? "none" : p.describe();
    }
}
