package com.marketai.ai.llm;

import lombok.Builder;
import lombok.Data;

/** Raw model output plus the metadata the audit trail needs. */
@Data @Builder
public class LlmCompletion {
    private String text;
    private String model;        // e.g. "qwen2.5:7b"
    private String provider;     // e.g. "ollama"
    private long latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
}
