package com.marketai.ai.prompt;

/**
 * A versioned system prompt. {@link #tag()} ("transaction-extraction-v1") is what gets recorded.
 */
public record PromptTemplate(String id, int version, String system) {

    public String tag() { return id + "-v" + version; }
}
