package com.marketai.ai.llm;

/** What a model can do. Checked before a model is assigned to a task, and again before each call. */
public enum LlmCapability {
    TEXT("Text understanding"),
    JSON("JSON / structured output"),
    VISION("Vision (reads images)"),
    PDF("PDF understanding (native)"),
    TOOLS("Tool calling");

    private final String label;

    LlmCapability(String label) { this.label = label; }

    public String label() { return label; }
}
