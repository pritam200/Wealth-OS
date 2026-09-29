package com.marketai.ai.llm;

/**
 * Why a model call failed, in words fit to show a person. The raw exception text can quote a
 * request URL or a provider's error body, so it never reaches the UI — only this does.
 */
public enum LlmErrorCategory {
    NOT_CONFIGURED("The provider is not configured", false),
    NOT_RUNNING("The provider could not be reached (for Ollama: it is not running at the configured address)", true),
    AUTH_INVALID("The API key was rejected", false),
    MODEL_NOT_FOUND("The model was not found", false),
    RATE_LIMITED("The API quota or rate limit was reached", true),
    TIMEOUT("The request timed out", true),
    OUTPUT_INVALID("The model returned no usable output", true),
    CAPABILITY_MISSING("The model cannot do what this task needs", false),
    BLOCKED_BY_PRIVACY("Blocked by the privacy mode (Local AI keeps documents off cloud providers)", false),
    PROVIDER_ERROR("The provider returned an error", true);

    private final String message;
    private final boolean retryable;

    LlmErrorCategory(String message, boolean retryable) {
        this.message = message;
        this.retryable = retryable;
    }

    public String message() { return message; }

    /** Whether trying again (or trying the fallback provider) could succeed. */
    public boolean retryable() { return retryable; }
}
