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
        if (p == null) throw new LlmUnavailableException(NONE_AVAILABLE);
        return p.complete(systemInstruction, userPrompt);
    }

    /**
     * Human-readable answer (advisory narrative, copilot) rather than JSON.
     *
     * @throws LlmUnavailableException when no provider is usable — same contract as
     *         {@link #complete}, so a missing model can never be mistaken for an answer.
     */
    public LlmCompletion completeProse(String systemInstruction, String userPrompt) {
        LlmProvider p = active();
        if (p == null) throw new LlmUnavailableException(NONE_AVAILABLE);
        return p.completeProse(systemInstruction, userPrompt);
    }

    /**
     * JSON completion from a specific provider for one task — e.g. email reading on Gemini while
     * everything else stays on the default. Blank {@code preferred} means the configured default.
     *
     * <p>If the preferred provider is unusable or the call itself fails (a rate limit that outlived
     * its retries, an outage), the other provider is tried when fallback is enabled — so one bad
     * minute on the hosted API doesn't park every email in the backfill.
     *
     * @throws LlmUnavailableException when no provider could answer.
     */
    public LlmCompletion completeWith(String preferred, String systemInstruction, String userPrompt) {
        if ("none".equalsIgnoreCase(configured)) throw new LlmUnavailableException(NONE_AVAILABLE);
        String choice = preferred == null || preferred.isBlank() ? configured : preferred;
        LlmProvider primary = "gemini".equalsIgnoreCase(choice) ? gemini : ollama;
        LlmProvider secondary = primary == ollama ? gemini : ollama;

        LlmUnavailableException primaryFailure = null;
        if (primary.isAvailable()) {
            try {
                return primary.complete(systemInstruction, userPrompt);
            } catch (LlmUnavailableException e) {
                primaryFailure = e;
            }
        }
        if (fallbackEnabled && secondary.isAvailable()) {
            log.info("LLM provider {} unavailable ({}) — using {}", primary.describe(),
                primaryFailure != null ? primaryFailure.getMessage() : "not configured", secondary.describe());
            return secondary.complete(systemInstruction, userPrompt);
        }
        throw primaryFailure != null ? primaryFailure : new LlmUnavailableException(NONE_AVAILABLE);
    }

    /** Surfaced to users when an AI feature is asked for but nothing can serve it. */
    public static final String NONE_AVAILABLE =
        "No AI model is available. Start Ollama locally (app.llm.provider=ollama, default "
        + "http://localhost:11434) or set GEMINI_API_KEY and app.llm.provider=gemini.";

    public String describeActive() {
        LlmProvider p = active();
        return p == null ? "none" : p.describe();
    }
}
