package com.marketai.ai.llm;

import com.marketai.ai.client.GeminiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Gemini behind {@link LlmProvider}, over the application's single {@link GeminiClient}. */
@Component
@RequiredArgsConstructor
public class GeminiLlmProvider implements LlmProvider {

    private final GeminiClient geminiClient;

    @Override
    public LlmProviderId id() { return LlmProviderId.GEMINI; }

    @Override
    public LlmCompletion generate(ProviderSettings settings, LlmRequest request) {
        long started = System.currentTimeMillis();
        GeminiClient.Result result = geminiClient.generateContent(settings, request);
        return LlmCompletion.builder()
            .text(result.text()).model(request.model()).provider(id().key())
            .latencyMs(System.currentTimeMillis() - started)
            .promptTokens(result.promptTokens()).completionTokens(result.completionTokens())
            .grounding(result.grounding())
            .build();
    }

    /**
     * The API lists which models can generate content but not their other capabilities, so
     * those come from the model family: Gemini chat models read images and PDFs, return JSON
     * and call tools. Speech, embedding and image-generation variants are given text only.
     */
    @Override
    public List<ModelInfo> listModels(ProviderSettings settings) {
        return geminiClient.listModels(settings).stream()
            .map(name -> new ModelInfo(name, capabilitiesOf(name), true))
            .toList();
    }

    static Set<LlmCapability> capabilitiesOf(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        boolean chatModel = m.startsWith("gemini-")
            && !(m.contains("tts") || m.contains("embedding") || m.contains("image-generation")
                 || m.contains("-image") || m.contains("live") || m.contains("audio"));
        return chatModel
            ? EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON, LlmCapability.VISION, LlmCapability.PDF, LlmCapability.TOOLS)
            : EnumSet.of(LlmCapability.TEXT);
    }
}
