package com.marketai.ai.llm;

/**
 * Abstraction over whatever model actually answers a prompt, so the reasoning layers
 * (email classification, ambiguity resolution, advisory narrative) never bind to a vendor.
 *
 * Contract, deliberately narrow: given a system instruction and a user prompt, return the
 * model's raw text. Providers do not parse, validate, or interpret — callers own that, which
 * is what keeps the deterministic-safety boundary enforceable (the LLM supplies semantics;
 * backend code does every calculation and DB write).
 */
public interface LlmProvider {

    /**
     * @return raw model output, never null. Throws {@link LlmUnavailableException} when the
     *         model can't be reached — callers must treat that as "no answer" and fall back to
     *         deterministic behaviour rather than guessing.
     */
    LlmCompletion complete(String systemInstruction, String userPrompt);

    /** Identifier recorded in the audit trail, e.g. "ollama:qwen2.5:7b". */
    String describe();

    /** Whether this provider is currently usable — checked before routing work to it. */
    boolean isAvailable();
}
