package com.marketai.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marketai.ai.client.GeminiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Local Ollama behind {@link LlmProvider} — no API key, no per-call cost, nothing leaves the machine.
 *
 * <p>Uses /api/chat with stream=false, and {@code format: json} for structured tasks: that is
 * what makes Ollama constrain decoding to valid JSON, which matters far more than prompt wording
 * for getting parseable output out of a small local model. Images go in the message's
 * {@code images} field, which vision models (llava, gemma3, qwen2.5vl…) read.
 *
 * <p>Capabilities are read from /api/show, never assumed from a model's name.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OllamaProvider implements LlmProvider {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public LlmProviderId id() { return LlmProviderId.OLLAMA; }

    static final int MIN_CONTEXT = 8192;
    static final int MAX_CONTEXT = 32768;
    /** Room for the answer when no output limit is set: a statement's worth of JSON lines. */
    static final int ANSWER_RESERVE = 6144;

    /**
     * A window that holds the whole prompt plus the answer: about 3 characters per token for
     * mixed English, numbers and ₹ (conservative for Indian statements), rounded up to a power
     * of two, between {@value #MIN_CONTEXT} and {@value #MAX_CONTEXT}.
     */
    static int contextWindow(LlmRequest request) {
        long chars = (request.systemInstruction() == null ? 0 : request.systemInstruction().length())
            + (request.userPrompt() == null ? 0 : request.userPrompt().length());
        long needed = chars / 3 + (request.maxTokens() != null ? request.maxTokens() : ANSWER_RESERVE) + 256;
        int window = MIN_CONTEXT;
        while (window < needed && window < MAX_CONTEXT) window *= 2;
        return window;
    }

    @Override
    public LlmCompletion generate(ProviderSettings settings, LlmRequest request) {
        if (request.webSearch()) {
            // A local model has no web access; answering anyway would present recall as research.
            throw new LlmUnavailableException(LlmErrorCategory.CAPABILITY_MISSING, "Ollama models cannot search the web");
        }
        long started = System.currentTimeMillis();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", request.model());
            body.put("stream", false);
            if (request.json()) body.put("format", "json");

            ObjectNode options = objectMapper.createObjectNode();
            options.put("temperature", request.temperature());
            if (request.maxTokens() != null) options.put("num_predict", request.maxTokens());
            // Ollama's default window (often 4096 tokens) silently drops the START of a longer
            // prompt — the instructions — so a long statement came back half-read with no error.
            options.put("num_ctx", contextWindow(request));
            body.set("options", options);

            ArrayNode messages = objectMapper.createArrayNode();
            if (request.systemInstruction() != null && !request.systemInstruction().isBlank()) {
                messages.add(objectMapper.createObjectNode().put("role", "system").put("content", request.systemInstruction()));
            }
            ObjectNode user = objectMapper.createObjectNode().put("role", "user").put("content", request.userPrompt());
            if (request.hasImages()) {
                ArrayNode images = objectMapper.createArrayNode();
                for (GeminiClient.InlineImage img : request.images()) {
                    images.add(java.util.Base64.getEncoder().encodeToString(img.data()));
                }
                user.set("images", images);
            }
            messages.add(user);
            body.set("messages", messages);

            JsonNode response = webClientBuilder.build()
                .post()
                .uri(settings.endpoint() + "/api/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(settings.timeoutSeconds()));

            String text = response == null ? null : response.at("/message/content").asText(null);
            if (text == null || text.isBlank()) {
                throw new LlmUnavailableException(LlmErrorCategory.OUTPUT_INVALID, "Ollama returned no message content");
            }
            return LlmCompletion.builder()
                .text(text)
                .model(request.model())
                .provider(id().key())
                .latencyMs(System.currentTimeMillis() - started)
                .promptTokens(response.hasNonNull("prompt_eval_count") ? response.get("prompt_eval_count").asInt() : null)
                .completionTokens(response.hasNonNull("eval_count") ? response.get("eval_count").asInt() : null)
                .build();
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw failure(e);
        }
    }

    @Override
    public List<ModelInfo> listModels(ProviderSettings settings) {
        JsonNode tags;
        try {
            tags = webClientBuilder.build().get()
                .uri(settings.endpoint() + "/api/tags")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            throw failure(e);
        }
        List<ModelInfo> models = new ArrayList<>();
        if (tags == null) return models;
        for (JsonNode m : tags.path("models")) {
            String name = m.path("name").asText(null);
            if (name != null) models.add(describe(settings, name));
        }
        return models;
    }

    /** Capabilities from /api/show ("completion", "vision", "tools", "embedding"…). */
    private ModelInfo describe(ProviderSettings settings, String name) {
        try {
            JsonNode show = webClientBuilder.build().post()
                .uri(settings.endpoint() + "/api/show")
                .bodyValue(objectMapper.createObjectNode().put("model", name))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(5));
            JsonNode caps = show == null ? null : show.get("capabilities");
            if (caps == null || !caps.isArray()) {
                // An Ollama too old to report capabilities: any chat model can be held to JSON by
                // format=json, but nothing more is assumed.
                return new ModelInfo(name, EnumSet.of(LlmCapability.TEXT, LlmCapability.JSON), false);
            }
            return new ModelInfo(name, capabilitiesOf(caps), true);
        } catch (Exception e) {
            log.debug("Ollama /api/show failed for {}: {}", name, e.getClass().getSimpleName());
            return new ModelInfo(name, EnumSet.noneOf(LlmCapability.class), false);
        }
    }

    static Set<LlmCapability> capabilitiesOf(JsonNode caps) {
        Set<LlmCapability> out = EnumSet.noneOf(LlmCapability.class);
        for (JsonNode c : caps) {
            switch (c.asText()) {
                // format=json constrains any completion model's decoding to valid JSON.
                case "completion" -> { out.add(LlmCapability.TEXT); out.add(LlmCapability.JSON); }
                case "vision" -> out.add(LlmCapability.VISION);
                case "tools" -> out.add(LlmCapability.TOOLS);
                default -> { }
            }
        }
        return out;
    }

    /** Category and status only — a prompt can contain financial detail and must never reach logs. */
    static LlmUnavailableException failure(Exception e) {
        if (e instanceof WebClientResponseException r) {
            int status = r.getStatusCode().value();
            LlmErrorCategory category = status == 404 ? LlmErrorCategory.MODEL_NOT_FOUND : LlmErrorCategory.PROVIDER_ERROR;
            log.warn("Ollama error: HTTP {} ({})", status, category);
            return new LlmUnavailableException(category, "Ollama call failed: HTTP " + status);
        }
        if (GeminiClient.isTimeout(e)) {
            log.warn("Ollama call timed out");
            return new LlmUnavailableException(LlmErrorCategory.TIMEOUT, "Ollama call timed out");
        }
        if (e instanceof WebClientRequestException) {
            log.debug("Ollama not reachable: {}", e.getClass().getSimpleName());
            return new LlmUnavailableException(LlmErrorCategory.NOT_RUNNING, "Ollama is not reachable");
        }
        log.warn("Ollama call failed: {}", e.getClass().getSimpleName());
        return new LlmUnavailableException(LlmErrorCategory.PROVIDER_ERROR, "Ollama call failed: " + e.getClass().getSimpleName());
    }
}
