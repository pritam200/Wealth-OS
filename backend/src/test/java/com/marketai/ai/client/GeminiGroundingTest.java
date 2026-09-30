package com.marketai.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.llm.LlmProviderId;
import com.marketai.ai.llm.LlmRequest;
import com.marketai.ai.llm.ProviderSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Web research against a local stand-in for the Gemini API: the request asks for Google Search, and grounding is read back. */
class GeminiGroundingTest {

    HttpServer server;
    final ObjectMapper mapper = new ObjectMapper();

    static final String RESPONSE = """
        {"candidates":[{"content":{"parts":[{"text":"- [2026-09-27] Reuters: Order win."},{"text":"\\n- [2026-09-20] Mint: CFO resigns."}]},
          "finishReason":"STOP",
          "groundingMetadata":{"webSearchQueries":["Test Ltd news"],
            "groundingChunks":[{"web":{"uri":"https://vertexaisearch.cloud.google.com/r/1","title":"reuters.com"}},
                               {"web":{"uri":"https://vertexaisearch.cloud.google.com/r/2","title":"livemint.com"}}],
            "groundingSupports":[{"segment":{"text":"- [2026-09-27] Reuters: Order win."},"groundingChunkIndices":[0]},
                                 {"segment":{"text":"- [2026-09-20] Mint: CFO resigns."},"groundingChunkIndices":[1,7]}]}}],
         "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":30}}""";

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    @DisplayName("webSearch sends the google_search tool without JSON mode, and grounding comes back mapped to sources")
    void groundedCall() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = RESPONSE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        GeminiClient client = new GeminiClient(WebClient.builder(), mapper);
        ProviderSettings s = new ProviderSettings(LlmProviderId.GEMINI, "http://127.0.0.1:" + server.getAddress().getPort(),
                "dummy-test-key", "gemini-2.5-flash", 0.0, null, 10);

        GeminiClient.Result r = client.generateContent(s, new LlmRequest("sys", "subject", "gemini-2.5-flash", false, 0.0, null, 10, List.of(), true));

        JsonNode sent = mapper.readTree(body.get());
        assertThat(sent.path("tools").get(0).has("google_search")).isTrue();
        assertThat(sent.path("generationConfig").has("responseMimeType")).isFalse();
        assertThat(r.text()).contains("Order win").contains("CFO resigns");
        assertThat(r.grounding().queries()).containsExactly("Test Ltd news");
        assertThat(r.grounding().sources()).extracting(x -> x.title()).containsExactly("reuters.com", "livemint.com");
        assertThat(r.grounding().supports()).hasSize(2);
        assertThat(r.grounding().supports().get(1).sourceIndexes()).containsExactly(1); // out-of-range index 7 dropped
    }

    @Test
    void webSearchAndJsonCannotBeCombined() {
        assertThatThrownBy(() -> new LlmRequest("s", "u", "m", true, 0.0, null, 10, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
