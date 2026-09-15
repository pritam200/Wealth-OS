package com.marketai.ai.llm;

/**
 * The model could not be reached or returned nothing usable. Callers must degrade to
 * deterministic behaviour — never substitute a guess for a missing model answer.
 */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message) { super(message); }
    public LlmUnavailableException(String message, Throwable cause) { super(message, cause); }
}
