package com.marketai.ai.llm.config;

import com.marketai.ai.llm.LlmProviderId;
import com.marketai.ai.llm.LlmTask;

/**
 * Where documents may be sent.
 *
 * <ul>
 *   <li><b>LOCAL</b> — every task on the local model, and nothing is ever sent to a cloud
 *       provider, fallback included. A task the local model can't do (reading scans without a
 *       vision model) is reported as unavailable rather than sent out.</li>
 *   <li><b>CLOUD</b> — every task on the cloud provider.</li>
 *   <li><b>HYBRID</b> — routine work (classification, email alerts, the advisor's routing, the
 *       assistant) stays local; statements, scans and analysis go to the cloud provider.</li>
 *   <li><b>CUSTOM</b> — each task as configured.</li>
 * </ul>
 */
public enum PrivacyMode {
    LOCAL, CLOUD, HYBRID, CUSTOM;

    /** The preset provider for a task, or null for CUSTOM (the saved route decides). */
    public LlmProviderId presetFor(LlmTask task) {
        return switch (this) {
            case LOCAL -> LlmProviderId.OLLAMA;
            case CLOUD -> LlmProviderId.GEMINI;
            case HYBRID -> switch (task) {
                case DOCUMENT_EXTRACTION, DOCUMENT_TRANSCRIPTION, FINANCIAL_ANALYSIS -> LlmProviderId.GEMINI;
                default -> LlmProviderId.OLLAMA;
            };
            case CUSTOM -> null;
        };
    }

    public boolean allowsCloud() { return this != LOCAL; }
}
