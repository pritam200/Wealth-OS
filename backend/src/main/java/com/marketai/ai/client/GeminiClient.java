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

    public record Result(String text, Integer promptTokens, Integer completionTokens,
                         com.marketai.ai.llm.LlmCompletion.Grounding grounding) {
        public Result(String text, Integer promptTokens, Integer completionTokens) {
            this(text, promptTokens, completionTokens, null);
        }
    }

    private static final int MAX_ATTEMPTS = 4;
    /** A per-minute quota needs waits measured in tens of seconds, not the 2-8s of a 503 backoff. */
    private static final int RATE_LIMIT_ATTEMPTS = 6;
    private static final long MIN_RATE_LIMIT_WAIT_MS = 15_000;
    private static final long MAX_RATE_LIMIT_WAIT_MS = 65_000;
    /** After a 429, calls are spaced this far apart for {@link #PACING_WINDOW_MS}. */
    private static final long PACED_INTERVAL_MS = 6_000;
    private static final long PACING_WINDOW_MS = 10 * 60_000;
    private static final java.util.regex.Pattern RETRY_DELAY =
        java.util.regex.Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"");

    // Shared across sync workers: one 429 means the key's quota is spent for everyone, so every
    // caller waits rather than each hammering the API and falling back to the local model.
    private final Object pacingLock = new Object();
    private long notBefore;          // epoch ms before which no call is sent
    private long pacedUntil;         // epoch ms until which calls are spaced PACED_INTERVAL_MS apart
    private long lastCallAt;

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
        if (request.webSearch()) {
            ArrayNode tools = objectMapper.createArrayNode();
            tools.add(objectMapper.createObjectNode().set("google_search", objectMapper.createObjectNode()));
            body.set("tools", tools);
        }

        String uri = settings.endpoint() + "/models/" + stripPrefix(request.model()) + ":generateContent";
        JsonNode response = null;
        for (int attempt = 1; ; attempt++) {
            awaitTurn();
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
                if (status == 429 && attempt < RATE_LIMIT_ATTEMPTS) {
                    String detail = e.getResponseBodyAsString();
                    // A daily quota does not come back within minutes: fail now and let the
                    // configured fallback take the call, instead of stalling the sync for hours.
                    if (detail != null && detail.contains("PerDay")) throw failure(e);
                    long wait = rateLimitWait(detail, attempt);
                    log.info("Gemini rate limited — waiting {}s before retry {} of {}", wait / 1000, attempt, RATE_LIMIT_ATTEMPTS - 1);
                    backOff(wait);
                    continue;
                }
                if (status == 503 && attempt < MAX_ATTEMPTS) {
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
        String text = response == null ? null : joinParts(response.at("/candidates/0/content/parts"));
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
            usage.hasNonNull("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null,
            request.webSearch() ? grounding(response.at("/candidates/0/groundingMetadata")) : null);
    }

    /** A grounded answer can arrive split across several text parts. */
    static String joinParts(JsonNode parts) {
        if (parts == null || !parts.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) if (p.hasNonNull("text")) sb.append(p.get("text").asText());
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Google Search grounding metadata: the pages used ({@code groundingChunks[].web}) and which
     * answer segments each supports ({@code groundingSupports}). An answer with no metadata is
     * returned with empty lists, so the caller can tell "searched, nothing cited" apart.
     */
    static com.marketai.ai.llm.LlmCompletion.Grounding grounding(JsonNode meta) {
        List<String> queries = new ArrayList<>();
        List<com.marketai.ai.llm.LlmCompletion.Source> sources = new ArrayList<>();
        List<com.marketai.ai.llm.LlmCompletion.Support> supports = new ArrayList<>();
        if (meta != null && !meta.isMissingNode()) {
            for (JsonNode q : meta.path("webSearchQueries")) queries.add(q.asText());
            for (JsonNode c : meta.path("groundingChunks")) {
                JsonNode web = c.path("web");
                sources.add(new com.marketai.ai.llm.LlmCompletion.Source(web.path("title").asText(null), web.path("uri").asText(null)));
            }
            for (JsonNode s : meta.path("groundingSupports")) {
                String segment = s.path("segment").path("text").asText(null);
                List<Integer> idx = new ArrayList<>();
                for (JsonNode i : s.path("groundingChunkIndices")) if (i.asInt() >= 0 && i.asInt() < sources.size()) idx.add(i.asInt());
                if (segment != null && !segment.isBlank() && !idx.isEmpty()) supports.add(new com.marketai.ai.llm.LlmCompletion.Support(segment.trim(), idx));
            }
        }
        return new com.marketai.ai.llm.LlmCompletion.Grounding(queries, sources, supports);
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

    /** How long to wait after a 429: what Gemini asked for, but never less than a quarter minute. */
    static long rateLimitWait(String body, int attempt) {
        long asked = 0;
        if (body != null) {
            java.util.regex.Matcher m = RETRY_DELAY.matcher(body);
            if (m.find()) asked = Long.parseLong(m.group(1)) * 1000 + 1000;
        }
        long floor = MIN_RATE_LIMIT_WAIT_MS * attempt;
        return Math.min(MAX_RATE_LIMIT_WAIT_MS, Math.max(asked, floor));
    }

    private void backOff(long waitMs) {
        long now = System.currentTimeMillis();
        synchronized (pacingLock) {
            notBefore = Math.max(notBefore, now + waitMs);
            pacedUntil = Math.max(pacedUntil, now + waitMs + PACING_WINDOW_MS);
        }
    }

    /** Blocks until this caller may send: past any shared back-off, and spaced out while pacing. */
    private void awaitTurn() {
        while (true) {
            long wait;
            synchronized (pacingLock) {
                long now = System.currentTimeMillis();
                long earliest = notBefore;
                if (now < pacedUntil) earliest = Math.max(earliest, lastCallAt + PACED_INTERVAL_MS);
                wait = earliest - now;
                if (wait <= 0) {
                    lastCallAt = now;
                    return;
                }
            }
            sleep(Math.min(wait, 5_000));
            if (Thread.currentThread().isInterrupted()) return;
        }
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
