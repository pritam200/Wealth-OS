package com.marketai.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Local Ollama provider — no API key, no per-call cost, nothing leaves the machine.
 *
 * Uses /api/chat with stream=false and format=json. `format: json` is what makes Ollama
 * constrain decoding to valid JSON, which matters far more than prompt wording for getting
 * parseable output out of a small local model.
 *
 * Temperature is pinned to 0 by default: these calls are classification and extraction, where
 * the same email must classify the same way every run. Sampling variety would make financial
 * imports non-reproducible.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OllamaProvider implements LlmProvider {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${app.llm.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${app.llm.ollama.model:qwen2.5:7b}")
    private String model;

    @Value("${app.llm.ollama.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${app.llm.ollama.temperature:0.0}")
    private double temperature;

    @Override
    public String describe() {
        return "ollama:" + model;
    }

    @Override
    public boolean isAvailable() {
        try {
            webClientBuilder.build().get()
                .uri(baseUrl + "/api/tags")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(3));
            return true;
        } catch (Exception e) {
            log.debug("Ollama not reachable at {}: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    @Override
    public LlmCompletion complete(String systemInstruction, String userPrompt) {
        return call(systemInstruction, userPrompt, true);
    }

    /** Prose mode: no `format: json`, or the model would answer with a JSON object instead
     *  of the 2-3 sentences the advisory/copilot callers are asking for. */
    @Override
    public LlmCompletion completeProse(String systemInstruction, String userPrompt) {
        return call(systemInstruction, userPrompt, false);
    }

    private LlmCompletion call(String systemInstruction, String userPrompt, boolean jsonMode) {
        long started = System.currentTimeMillis();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("stream", false);
            // Constrain decoding to JSON — the single most effective guard against a small
            // local model wrapping its answer in prose or markdown fences.
            if (jsonMode) body.put("format", "json");

            ObjectNode options = objectMapper.createObjectNode();
            options.put("temperature", temperature);
            body.set("options", options);

            com.fasterxml.jackson.databind.node.ArrayNode messages = objectMapper.createArrayNode();
            if (systemInstruction != null && !systemInstruction.trim().isEmpty()) {
                ObjectNode sys = objectMapper.createObjectNode();
                sys.put("role", "system");
                sys.put("content", systemInstruction);
                messages.add(sys);
            }
            ObjectNode user = objectMapper.createObjectNode();
            user.put("role", "user");
            user.put("content", userPrompt);
            messages.add(user);
            body.set("messages", messages);

            JsonNode response = webClientBuilder.build()
                .post()
                .uri(baseUrl + "/api/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(timeoutSeconds));

            if (response == null) {
                throw new LlmUnavailableException("Ollama returned an empty response");
            }
            String text = response.at("/message/content").asText(null);
            if (text == null || text.trim().isEmpty()) {
                throw new LlmUnavailableException("Ollama returned no message content");
            }

            return LlmCompletion.builder()
                .text(text)
                .model(model)
                .provider("ollama")
                .latencyMs(System.currentTimeMillis() - started)
                .promptTokens(response.hasNonNull("prompt_eval_count") ? response.get("prompt_eval_count").asInt() : null)
                .completionTokens(response.hasNonNull("eval_count") ? response.get("eval_count").asInt() : null)
                .build();

        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // Message only — a prompt can contain financial detail and must never reach logs.
            log.warn("Ollama call failed ({}): {}", describe(), e.getMessage());
            throw new LlmUnavailableException("Ollama call failed: " + e.getMessage(), e);
        }
    }
}
