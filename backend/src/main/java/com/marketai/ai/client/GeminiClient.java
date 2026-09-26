package com.marketai.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.common.exception.ExternalApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    // Optional: Gemini is one of two providers behind LlmProviderRouter, and the default
    // (Ollama) needs no key at all. Declared with an empty default so a deployment that
    // never uses Gemini still starts.
    /** Generous — hosted generation is slow — but finite. */
    private static final java.time.Duration RESPONSE_TIMEOUT = java.time.Duration.ofSeconds(60);

    @Value("${app.gemini.api-key:}")
    private String apiKey;

    @Value("${app.gemini.base-url}")
    private String baseUrl;

    @Value("${app.gemini.model}")
    private String model;

    /** Prose call — unchanged behaviour for narrative features. */
    public String generateContent(String systemInstruction, String userPrompt) {
        return generateContent(systemInstruction, userPrompt, false);
    }

    /**
     * @param jsonMode true for extraction: JSON-only output, temperature 0 (the same email must
     *                 read the same way on every sync, or re-sync dedup has nothing stable to
     *                 match), and room for a statement's worth of transactions. Prose keeps the
     *                 previous settings.
     * @throws LlmUnavailableException when no key is configured, the call fails, or the model
     *         returns nothing usable. This used to RETURN an apologetic sentence instead, which
     *         callers had no way to distinguish from a real answer — a missing model must be an
     *         error, never a fact.
     */
    public String generateContent(String systemInstruction, String userPrompt, boolean jsonMode) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new LlmUnavailableException("Gemini API key not configured (app.gemini.api-key)");
        }

        ObjectNode body = objectMapper.createObjectNode();

        ObjectNode sysInst = objectMapper.createObjectNode();
        ObjectNode sysPart = objectMapper.createObjectNode();
        sysPart.put("text", systemInstruction);
        ArrayNode sysParts = objectMapper.createArrayNode();
        sysParts.add(sysPart);
        sysInst.set("parts", sysParts);
        body.set("system_instruction", sysInst);

        ObjectNode userContent = objectMapper.createObjectNode();
        userContent.put("role", "user");
        ObjectNode userPart = objectMapper.createObjectNode();
        userPart.put("text", userPrompt);
        ArrayNode userParts = objectMapper.createArrayNode();
        userParts.add(userPart);
        userContent.set("parts", userParts);
        ArrayNode contents = objectMapper.createArrayNode();
        contents.add(userContent);
        body.set("contents", contents);

        ObjectNode config = objectMapper.createObjectNode();
        if (jsonMode) {
            config.put("temperature", 0.0);
            // A statement chunk can carry dozens of transactions; 2048 tokens cut the JSON off
            // mid-array, which then failed to parse on every retry.
            config.put("maxOutputTokens", 8192);
            config.put("responseMimeType", "application/json");
        } else {
            config.put("temperature", 0.7);
            config.put("maxOutputTokens", 2048);
        }
        body.set("generationConfig", config);

        JsonNode response = null;
        for (int attempt = 1; ; attempt++) {
            try {
                response = webClientBuilder.build()
                        .post()
                        .uri(baseUrl + "/models/" + model + ":generateContent")
                        // Header, not ?key= — a failed call's message includes the request URI,
                        // which put the API key into the error log.
                        .header("x-goog-api-key", apiKey)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        // Bounded: a bare block() on a stalled socket pins the request thread
                        // for the life of the process.
                        .block(RESPONSE_TIMEOUT);
                break;
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                int status = e.getStatusCode().value();
                // Rate limits and overload are expected during a full-mailbox backfill: back off
                // and retry a few times before giving up on this call.
                if ((status == 429 || status == 503) && attempt < MAX_ATTEMPTS) {
                    sleep(1000L << attempt);
                    continue;
                }
                log.warn("Gemini API error: HTTP {}", status);
                throw new LlmUnavailableException("Gemini call failed: HTTP " + status);
            } catch (Exception e) {
                log.warn("Gemini API error: {}", e.getClass().getSimpleName());
                throw new LlmUnavailableException("Gemini call failed: " + e.getClass().getSimpleName());
            }
        }

        String finish = response == null ? null : response.at("/candidates/0/finishReason").asText(null);
        String text = response == null ? null : response.at("/candidates/0/content/parts/0/text").asText(null);
        if ("MAX_TOKENS".equals(finish) && jsonMode) {
            throw new LlmUnavailableException("Gemini output was cut off at the token limit");
        }
        if (text == null || text.isBlank()) {
            throw new LlmUnavailableException("Gemini returned no content"
                + (finish != null ? " (finishReason=" + finish + ")" : ""));
        }
        return text;
    }

    private static final int MAX_ATTEMPTS = 4;

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Interrupted while backing off from a Gemini rate limit");
        }
    }
}
