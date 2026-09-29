package com.marketai.ai.llm;

import com.marketai.ai.client.GeminiClient;

import java.util.List;

/**
 * One call to a provider. The provider applies it exactly; routing, fallback and logging are
 * {@link LlmService}'s job.
 *
 * @param model       the model to use (never null by the time a provider sees it)
 * @param json        constrain output to JSON
 * @param temperature sampling temperature
 * @param maxTokens   output token cap, or null for the provider's default
 * @param images      images to read (only for providers/models with {@link LlmCapability#VISION})
 */
public record LlmRequest(String systemInstruction, String userPrompt, String model, boolean json,
                         double temperature, Integer maxTokens, int timeoutSeconds,
                         List<GeminiClient.InlineImage> images) {

    public LlmRequest {
        images = images == null ? List.of() : List.copyOf(images);
    }

    public boolean hasImages() { return !images.isEmpty(); }
}
