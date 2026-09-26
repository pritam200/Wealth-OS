package com.marketai.ai.llm;

import com.marketai.ai.client.GeminiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adapter over the pre-existing GeminiClient so a hosted model stays available as a
 * hot-swappable alternative (app.llm.provider=gemini) without the reasoning layers knowing.
 * Ollama is the default; this exists so switching providers is a config change, not a rewrite.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiLlmProvider implements LlmProvider {

    private final GeminiClient geminiClient;

    @Value("${app.gemini.api-key:}")
    private String apiKey;

    @Value("${app.gemini.model:gemini-2.0-flash}")
    private String model;

    @Override
    public String describe() { return "gemini:" + model; }

    @Override
    public boolean isAvailable() { return apiKey != null && !apiKey.trim().isEmpty(); }

    /** JSON mode — callers of {@link #complete} parse the result. */
    @Override
    public LlmCompletion complete(String systemInstruction, String userPrompt) {
        return call(systemInstruction, userPrompt, true);
    }

    @Override
    public LlmCompletion completeProse(String systemInstruction, String userPrompt) {
        return call(systemInstruction, userPrompt, false);
    }

    private LlmCompletion call(String systemInstruction, String userPrompt, boolean jsonMode) {
        if (!isAvailable()) throw new LlmUnavailableException("Gemini API key not configured");
        long started = System.currentTimeMillis();
        try {
            String text = geminiClient.generateContent(systemInstruction, userPrompt, jsonMode);
            if (text == null || text.trim().isEmpty()) {
                throw new LlmUnavailableException("Gemini returned no content");
            }
            return LlmCompletion.builder()
                .text(text).model(model).provider("gemini")
                .latencyMs(System.currentTimeMillis() - started)
                .build();
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Gemini call failed: {}", e.getMessage());
            throw new LlmUnavailableException("Gemini call failed: " + e.getMessage(), e);
        }
    }
}
