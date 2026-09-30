package com.marketai.ai.llm;

import java.util.EnumSet;
import java.util.Set;

/**
 * Every kind of work sent to a model. Each is routed to a provider and model on its own (see
 * {@code LlmConfigService}), and declares what the model must be able to do, so a model is never
 * given work it cannot perform.
 *
 * <p>{@link #deterministic()} tasks (everything structured, and everything that reads a document)
 * always run at temperature 0: the same email must read the same way on every sync, or re-sync
 * deduplication has nothing stable to match. The configured temperature applies to the rest.
 */
public enum LlmTask {
    EMAIL_CLASSIFICATION("Email classification", true, EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON), true),
    EMAIL_EXTRACTION("Email transaction extraction", true, EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON), true),
    DOCUMENT_EXTRACTION("Financial document extraction (statements, PDFs)", true, EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON), true),
    DOCUMENT_TRANSCRIPTION("Scanned document reading", false, EnumSet.of(LlmCapability.TEXT, LlmCapability.VISION), true),
    AI_ADVISOR("AI Advisor (question routing)", true, EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON), false),
    FINANCIAL_ANALYSIS("Financial analysis & market research", true, EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON), false),
    GENERAL_ASSISTANT("General assistant", false, EnumSet.of(LlmCapability.TEXT), false);

    private final String label;
    private final boolean structured;
    private final Set<LlmCapability> requires;
    private final boolean readsDocuments;

    LlmTask(String label, boolean structured, Set<LlmCapability> requires, boolean readsDocuments) {
        this.label = label;
        this.structured = structured;
        this.requires = requires;
        this.readsDocuments = readsDocuments;
    }

    public String label() { return label; }

    /** JSON output, parsed and validated by code. */
    public boolean structured() { return structured; }

    public Set<LlmCapability> requires() { return requires; }

    /** Temperature 0 regardless of configuration. */
    public boolean deterministic() { return structured || readsDocuments; }

    /** Sends email or document content to the model (what Local AI keeps on this machine). */
    public boolean readsDocuments() { return readsDocuments; }
}
