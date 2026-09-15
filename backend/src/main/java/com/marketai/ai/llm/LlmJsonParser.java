package com.marketai.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Turns raw model text into JSON, or into nothing at all.
 *
 * This is the deterministic-safety gate: a model that returns prose, truncated JSON, markdown
 * fences, or a number as "approximately 5000" must produce a *parse failure*, never a coerced
 * value. Every accessor here returns null/empty rather than a default, so a missing field can
 * never silently become 0 in a financial record.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmJsonParser {

    private final ObjectMapper objectMapper;

    /** Parsed JSON, or empty when the model didn't return usable JSON. */
    public Optional<JsonNode> parse(String raw) {
        if (raw == null) return Optional.empty();
        String json = stripFences(raw.trim());
        if (json.isEmpty()) return Optional.empty();

        // Must actually start as a JSON value — a prose preamble means the model ignored the
        // schema, and salvaging text out of it is exactly the kind of guessing to avoid.
        char first = json.charAt(0);
        if (first != '{' && first != '[') {
            log.debug("LLM output was not JSON (starts with '{}')", first);
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(json));
        } catch (Exception e) {
            log.debug("LLM output failed JSON parse: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String stripFences(String s) {
        if (s.startsWith("```")) {
            return s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
        }
        return s;
    }

    /** Trimmed string, or null — never "" and never the literal "null"/"unknown"/"n/a". */
    public String str(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        if (s.equalsIgnoreCase("null") || s.equalsIgnoreCase("unknown") || s.equalsIgnoreCase("n/a")) return null;
        return s;
    }

    /**
     * Strict numeric read. Rejects anything that isn't cleanly numeric — a model writing
     * "₹5,000 approx" yields null, not 5000, because a mis-read amount is worse than a
     * skipped import.
     */
    public BigDecimal decimal(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        String s = v.asText(null);
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            log.debug("Refusing non-numeric value for '{}': {}", field, s);
            return null;
        }
    }

    public Integer integer(JsonNode node, String field) {
        BigDecimal d = decimal(node, field);
        if (d == null) return null;
        try {
            return d.intValueExact();
        } catch (ArithmeticException e) {
            return null;   // fractional where a count was required — refuse rather than round
        }
    }

    /**
     * Confidence in [0,1]. Returns null when absent or out of range, which callers must treat
     * as "unknown confidence" — i.e. route to review, not auto-accept.
     */
    public Double confidence(JsonNode node, String field) {
        BigDecimal d = decimal(node, field);
        if (d == null) return null;
        double val = d.doubleValue();
        if (val < 0 || val > 1) {
            log.debug("Confidence out of [0,1] range: {}", val);
            return null;
        }
        return val;
    }
}
