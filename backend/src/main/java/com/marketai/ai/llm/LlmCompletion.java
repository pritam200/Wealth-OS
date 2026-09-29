package com.marketai.ai.llm;

import lombok.Builder;
import lombok.Data;

/** Raw model output plus the metadata the audit trail needs. */
@Data @Builder(toBuilder = true)
public class LlmCompletion {
    private String text;
    private String model;        // e.g. "qwen2.5:7b"
    private String provider;     // e.g. "ollama"
    private long latencyMs;
    private Integer promptTokens;
    private Integer completionTokens;
    /** Prompt template and version that produced this, e.g. "transaction-extraction-v1". */
    private String promptVersion;
    /** Served by the fallback provider because the configured one failed. */
    private boolean fallbackUsed;

    /** Which model and prompt read a document — recorded on what it produced, so a re-read by a
     *  different model or prompt can be told apart from a repeat of the same read. */
    public String extractionVersion() {
        return provider + ":" + model + (promptVersion != null ? "/" + promptVersion : "");
    }
}
