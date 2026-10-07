package com.marketai.dataplatform.provider.setu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;

/**
 * Thin HTTP client for Setu's Account Aggregator gateway (v2). Owns the access token: it is
 * fetched with the product's client id and secret, cached until shortly before it expires, and
 * renewed once automatically if a call answers 401.
 *
 * <p>The client secret and tokens are never logged, and an error carries only the HTTP status and
 * Setu's short error text, never the request or the full response body.
 */
public class SetuClient {

    public static class SetuException extends RuntimeException {
        private final int status;
        public SetuException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    private final WebClient http;
    private final ObjectMapper mapper;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String productInstanceId;
    private final Duration timeout;

    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public SetuClient(WebClient.Builder builder, ObjectMapper mapper, String baseUrl, String tokenUrl, String clientId,
                      String clientSecret, String productInstanceId, Duration timeout) {
        this.http = builder.baseUrl(stripSlash(baseUrl)).build();
        this.mapper = mapper;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.productInstanceId = productInstanceId;
        this.timeout = timeout;
    }

    public JsonNode post(String path, JsonNode body) { return call("POST", path, body); }

    public JsonNode get(String path) { return call("GET", path, null); }

    private JsonNode call(String method, String path, JsonNode body) {
        try {
            return exchange(method, path, body, false);
        } catch (SetuException e) {
            if (e.status() != 401) throw e;
            return exchange(method, path, body, true); // the token may have just expired
        }
    }

    private JsonNode exchange(String method, String path, JsonNode body, boolean forceNewToken) {
        String bearer = accessToken(forceNewToken);
        try {
            WebClient.RequestBodySpec spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .header("x-product-instance-id", productInstanceId)
                .accept(MediaType.APPLICATION_JSON);
            WebClient.RequestHeadersSpec<?> req = body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            String text = req.retrieve().bodyToMono(String.class).block(timeout);
            return text == null || text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        } catch (WebClientResponseException e) {
            throw new SetuException(e.getStatusCode().value(), describe(e));
        } catch (WebClientRequestException e) {
            throw new SetuException(0, "Could not reach Setu");
        } catch (SetuException e) {
            throw e;
        } catch (Exception e) {
            throw new SetuException(0, "Unreadable response from Setu");
        }
    }

    private synchronized String accessToken(boolean forceNew) {
        if (!forceNew && token != null && Instant.now().isBefore(tokenExpiresAt)) return token;
        ObjectNode body = mapper.createObjectNode().put("clientID", clientId).put("secret", clientSecret);
        try {
            String text = WebClient.create().post().uri(tokenUrl).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(String.class).block(timeout);
            JsonNode data = mapper.readTree(text).path("data");
            String t = data.path("token").asText(null);
            if (t == null || t.isBlank()) throw new SetuException(0, "Setu did not return an access token");
            long ttl = data.path("expiresIn").asLong(1800);
            token = t;
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(30, ttl - 60));
            return t;
        } catch (WebClientResponseException e) {
            throw new SetuException(e.getStatusCode().value(), "Setu rejected the client credentials (HTTP " + e.getStatusCode().value() + ")");
        } catch (SetuException e) {
            throw e;
        } catch (Exception e) {
            throw new SetuException(0, "Could not get an access token from Setu");
        }
    }

    private String describe(WebClientResponseException e) {
        String msg = "Setu returned HTTP " + e.getStatusCode().value();
        try {
            JsonNode n = mapper.readTree(e.getResponseBodyAsString());
            String detail = n.path("error").path("message").asText(n.path("message").asText(null));
            if (detail != null && !detail.isBlank()) msg += ": " + (detail.length() > 160 ? detail.substring(0, 160) : detail);
        } catch (Exception ignored) { /* not JSON: status alone */ }
        return msg;
    }

    private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
}
