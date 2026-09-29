package com.marketai.ai.llm;

import java.util.List;
import java.util.Optional;

/**
 * The one abstraction over whatever model answers a prompt. Nothing outside {@code ai.llm}
 * knows which provider is in use: callers ask {@link LlmService} for a task, and routing,
 * fallback, retries and logging happen there.
 *
 * <p>Contract, deliberately narrow: send the request as given and return the model's raw text.
 * Providers do not parse, validate or interpret — callers own that, which is what keeps the
 * deterministic-safety boundary enforceable (the model supplies semantics; backend code does
 * every calculation and every write, identically whichever provider answered).
 *
 * <p>Stateless: the settings arrive with each call, so a connection test can try values that
 * have not been saved.
 */
public interface LlmProvider {

    LlmProviderId id();

    /**
     * @return raw model output, never null or blank
     * @throws LlmUnavailableException with a {@link LlmErrorCategory} when the call fails or the
     *         output is unusable — never a substitute answer
     */
    LlmCompletion generate(ProviderSettings settings, LlmRequest request);

    /** Models the provider offers, with their capabilities. */
    List<ModelInfo> listModels(ProviderSettings settings);

    /** One model's entry; empty when the provider does not offer it. */
    default Optional<ModelInfo> findModel(ProviderSettings settings, String model) {
        if (model == null) return Optional.empty();
        return listModels(settings).stream().filter(m -> sameModel(m.name(), model)).findFirst();
    }

    /** "llama3" and "llama3:latest" are the same Ollama model; "models/gemini-x" is "gemini-x". */
    static boolean sameModel(String a, String b) {
        return normalise(a).equals(normalise(b));
    }

    private static String normalise(String m) {
        String s = m.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("models/")) s = s.substring(7);
        if (s.endsWith(":latest")) s = s.substring(0, s.length() - 7);
        return s;
    }
}
