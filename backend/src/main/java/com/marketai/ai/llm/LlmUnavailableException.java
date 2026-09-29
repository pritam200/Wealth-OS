package com.marketai.ai.llm;

/**
 * The model could not be reached or returned nothing usable. Callers must degrade to
 * deterministic behaviour — never substitute a guess for a missing model answer.
 *
 * <p>The message is internal (logs, audit notes) and never carries a key or a prompt; what is
 * shown to a person is {@link #getCategory()}'s own wording.
 */
public class LlmUnavailableException extends RuntimeException {

    private final LlmErrorCategory category;

    public LlmUnavailableException(String message) { this(LlmErrorCategory.PROVIDER_ERROR, message); }
    public LlmUnavailableException(String message, Throwable cause) { this(LlmErrorCategory.PROVIDER_ERROR, message, cause); }

    public LlmUnavailableException(LlmErrorCategory category, String message) {
        super(message);
        this.category = category;
    }

    public LlmUnavailableException(LlmErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public LlmErrorCategory getCategory() { return category; }
}
