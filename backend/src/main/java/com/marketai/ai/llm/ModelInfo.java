package com.marketai.ai.llm;

import java.util.Set;

/**
 * A model a provider offers and what it can do.
 *
 * @param capabilitiesKnown false when the provider did not report capabilities (an older Ollama,
 *                          or no API access) — only the capabilities in {@code capabilities} are
 *                          then relied on, and a task needing more is refused.
 */
public record ModelInfo(String name, Set<LlmCapability> capabilities, boolean capabilitiesKnown) {

    public boolean supports(Set<LlmCapability> required) {
        return capabilities.containsAll(required);
    }
}
