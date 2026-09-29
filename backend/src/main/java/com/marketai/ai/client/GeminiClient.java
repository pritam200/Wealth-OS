package com.marketai.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketai.ai.llm.LlmErrorCategory;
import com.marketai.ai.llm.LlmRequest;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.ai.llm.ProviderSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for the Gemini API — the only one in the application. Settings (key, model,
 * limits) arrive with each call from {@code LlmConfigService}; nothing is read from the
 * environment here.
 *
 * <p>The key travels in the {@code x-goog-api-key} header, never the URL: a failed call's message
 * includes the request URI, which put the key into the error log when it was a query parameter.
 * Errors are reported as a {@link LlmErrorCategory} plus the HTTP status — never the response
 * body, which can echo the request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    /** An image sent with the prompt — a scanned statement page, or a photographed receipt. */
    public record InlineImage(String mimeType, byte[] data) {}

    public record Result(String text, Integer promptTokens, Integer completionTokens) {}

    private static final int MAX_ATTEMPTS = 4;

    /**
     * @throws LlmUnavailableException when no key is configured, the call fails, or the model
     *         returns nothing usable. A missing model must be an error, never a fact.
     */
    public Result generateContent(ProviderSettings settings, LlmRequest request) {
        requireKey(settings);

        ObjectNode body = objectMapper.createObjectNode();
        if (request.systemInstruction() != null && !request.systemInstruction().isBlank()) {
            ObjectNode sysInst = objectMapper.createObjectNode();
            ArrayNode sysParts = objectMapper.createArrayNode();
            sysParts.add(objectMapper.createObjectNode().put("text", request.systemInstruction()));
            sysInst.set("parts", sysParts);
            body.set("system_instruction", sysInst);
        }

        ObjectNode userContent = objectMapper.createObjectNode();
        userContent.put("role", "user");
        ArrayNode userParts = objectMapper.createArrayNode();
        userParts.add(objectMapper.createObjectNode().put("text", request.userPrompt()));
        for (InlineImage img : request.images()) {
            ObjectNode inline = objectMapper.createObjectNode();
            inline.put("mime_type", img.mimeType());
            inline.put("data", java.util.Base64.getEncoder().encodeToString(img.data()));
            ObjectNode part = objectMapper.createObjectNode();
            part.set("inline_data", inline);
            userParts.add(part);
        }
        userContent.set("parts", userParts);
        ArrayNode contents = objectMapper.createArrayNode();
        contents.add(userContent);
        body.set("contents", contents);

        ObjectNode config = objectMapper.createObjectNode();
        config.put("temperature", request.temperature());
        // A statement chunk can carry dozens of transactions and a transcribed page is long;
        // 2048 tokens cut the JSON off mid-array, which then failed to parse on every retry.
        int defaultCap = request.json() || request.hasImages() ? 8192 : 2048;
        config.put("maxOutputTokens", request.maxTokens() != null ? request.maxTokens() : defaultCap);
        if (request.json()) config.put("responseMimeType", "application/json");
        body.set("generationConfig", config);

        String uri = settings.endpoint() + "/models/" + stripPrefix(request.model()) + ":generateContent";
        JsonNode response = null;
        for (int attempt = 1; ; attempt++) {
            try {
                response = webClientBuilder.build()
                        .post()
                        .uri(uri)
                        .header("x-goog-api-key", settings.apiKey())
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        // Bounded: a bare block() on a stalled socket pins the thread for the
                        // life of the process.
                        .block(Duration.ofSeconds(settings.timeoutSeconds()));
                break;
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                // Rate limits and overload are expected during a full-mailbox backfill: back off
                // and retry a few times before giving up on this call.
                if ((status == 429 || status == 503) && attempt < MAX_ATTEMPTS) {
                    sleep(1000L << attempt);
                    continue;
                }
                throw failure(e);
            } catch (LlmUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw failure(e);
            }
        }

        String finish = response == null ? null : response.at("/candidates/0/finishReason").asText(null);
        String text = response == null ? null : response.at("/candidates/0/content/parts/0/text").asText(null);
        if ("MAX_TOKENS".equals(finish) && request.json()) {
            throw new LlmUnavailableException(LlmErrorCategory.OUTPUT_INVALID, "Gemini output was cut off at the token limit");
        }
        if (text == null || text.isBlank()) {
            throw new LlmUnavailableException(LlmErrorCategory.OUTPUT_INVALID, "Gemini returned no content"
                + (finish != null ? " (finishReason=" + finish + ")" : ""));
        }
        JsonNode usage = response.path("usageMetadata");
        return new Result(text,
            usage.hasNonNull("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null,
            usage.hasNonNull("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null);
    }

    /** Model names (without the "models/" prefix) that support generateContent. */
    public List<String> listModels(ProviderSettings settings) {
        requireKey(settings);
        List<String> names = new ArrayList<>();
        String pageToken = null;
        try {
            do {
                String uri = settings.endpoint() + "/models?pageSize=1000" + (pageToken != null ? "&pageToken=" + pageToken : "");
                JsonNode page = webClientBuilder.build().get().uri(uri)
                    .header("x-goog-api-key", settings.apiKey())
                    .retrieve().bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(Math.min(settings.timeoutSeconds(), 20)));
                if (page == null) break;
                for (JsonNode m : page.path("models")) {
                    boolean generates = false;
                    for (JsonNode method : m.path("supportedGenerationMethods")) {
                        if ("generateContent".equals(method.asText())) generates = true;
                    }
                    if (generates) names.add(stripPrefix(m.path("name").asText()));
                }
                pageToken = page.hasNonNull("nextPageToken") ? page.get("nextPageToken").asText() : null;
            } while (pageToken != null && !pageToken.isBlank());
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw failure(e);
        }
        return names;
    }

    private static void requireKey(ProviderSettings settings) {
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new LlmUnavailableException(LlmErrorCategory.NOT_CONFIGURED, "Gemini API key not configured");
        }
    }

    private static String stripPrefix(String model) {
        return model != null && model.startsWith("models/") ? model.substring(7) : model;
    }

    /** Status and category only — the response body can quote the request. */
    static LlmUnavailableException failure(Exception e) {
        if (e instanceof WebClientResponseException r) {
            int status = r.getStatusCode().value();
            String body = r.getResponseBodyAsString();
            LlmErrorCategory category = switch (status) {
                case 401, 403 -> LlmErrorCategory.AUTH_INVALID;
                case 404 -> LlmErrorCategory.MODEL_NOT_FOUND;
                case 429 -> LlmErrorCategory.RATE_LIMITED;
                case 400 -> body != null && (body.contains("API_KEY_INVALID") || body.contains("API key not valid"))
                    ? LlmErrorCategory.AUTH_INVALID : LlmErrorCategory.PROVIDER_ERROR;
                default -> LlmErrorCategory.PROVIDER_ERROR;
            };
            log.warn("Gemini API error: HTTP {} ({})", status, category);
            return new LlmUnavailableException(category, "Gemini call failed: HTTP " + status);
        }
        if (isTimeout(e)) {
            log.warn("Gemini API timed out");
            return new LlmUnavailableException(LlmErrorCategory.TIMEOUT, "Gemini call timed out");
        }
        if (e instanceof WebClientRequestException) {
            log.warn("Gemini API unreachable: {}", e.getClass().getSimpleName());
            return new LlmUnavailableException(LlmErrorCategory.NOT_RUNNING, "Gemini API unreachable");
        }
        log.warn("Gemini API error: {}", e.getClass().getSimpleName());
        return new LlmUnavailableException(LlmErrorCategory.PROVIDER_ERROR, "Gemini call failed: " + e.getClass().getSimpleName());
    }

    /** block(Duration) throws IllegalStateException("Timeout on blocking read ..."). */
    public static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException) return true;
            if (t instanceof IllegalStateException && t.getMessage() != null && t.getMessage().contains("Timeout")) return true;
            if (t.getClass().getSimpleName().contains("Timeout")) return true;
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException(LlmErrorCategory.PROVIDER_ERROR, "Interrupted while backing off from a Gemini rate limit");
        }
    }
}
