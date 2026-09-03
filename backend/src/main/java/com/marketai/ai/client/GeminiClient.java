package com.marketai.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    @Value("${app.gemini.api-key}")
    private String apiKey;

    @Value("${app.gemini.base-url}")
    private String baseUrl;

    @Value("${app.gemini.model}")
    private String model;

    public String generateContent(String systemInstruction, String userPrompt) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "AI features require a Gemini API key. Please set GEMINI_API_KEY in your environment.";
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();

            // System instruction
            ObjectNode sysInst = objectMapper.createObjectNode();
            ObjectNode sysPart = objectMapper.createObjectNode();
            sysPart.put("text", systemInstruction);
            ArrayNode sysParts = objectMapper.createArrayNode();
            sysParts.add(sysPart);
            sysInst.set("parts", sysParts);
            body.set("system_instruction", sysInst);

            // User content
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

            // Generation config
            ObjectNode config = objectMapper.createObjectNode();
            config.put("temperature", 0.7);
            config.put("maxOutputTokens", 2048);
            body.set("generationConfig", config);

            JsonNode response = webClientBuilder.build()
                    .post()
                    .uri(baseUrl + "/models/" + model + ":generateContent?key=" + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            return response.at("/candidates/0/content/parts/0/text").asText("No response from AI.");

        } catch (Exception e) {
            log.error("Gemini API error: {}", e.getMessage());
            throw new ExternalApiException("Gemini", "AI analysis failed: " + e.getMessage());
        }
    }
}
