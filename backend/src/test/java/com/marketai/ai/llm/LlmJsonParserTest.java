package com.marketai.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic-safety gate. Every case here is a way a small local model can go wrong;
 * in all of them the required outcome is "no value", never a coerced or guessed one.
 */
class LlmJsonParserTest {

    private final LlmJsonParser parser = new LlmJsonParser(new ObjectMapper());

    @Test
    void parsesCleanJsonObject() {
        Optional<JsonNode> n = parser.parse("{\"classification\":\"EXPENSE\",\"confidence\":0.91}");
        assertThat(n).isPresent();
        assertThat(parser.str(n.get(), "classification")).isEqualTo("EXPENSE");
        assertThat(parser.confidence(n.get(), "confidence")).isEqualTo(0.91);
    }

    @Test
    void stripsMarkdownFencesTheModelAddedAnyway() {
        Optional<JsonNode> n = parser.parse("```json\n{\"a\":1}\n```");
        assertThat(n).isPresent();
        assertThat(parser.integer(n.get(), "a")).isEqualTo(1);
    }

    @Test
    void refusesProseAndTruncatedOutput() {
        // A model that explains itself instead of answering must fail, not be salvaged.
        assertThat(parser.parse("Sure! Here is the JSON you asked for: {\"a\":1}")).isEmpty();
        assertThat(parser.parse("{\"amount\": 500, \"date\":")).isEmpty();   // truncated
        assertThat(parser.parse("I could not determine the transaction.")).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void refusesToCoerceANonNumericAmount() {
        JsonNode n = parser.parse("{\"amount\":\"₹5,000 approx\",\"units\":\"about 12\"}").orElseThrow(AssertionError::new);
        // Reading 5000 out of "₹5,000 approx" would silently invent a financial figure.
        assertThat(parser.decimal(n, "amount")).isNull();
        assertThat(parser.decimal(n, "units")).isNull();
    }

    @Test
    void acceptsNumbersWhetherQuotedOrNot() {
        JsonNode n = parser.parse("{\"a\":1500.50,\"b\":\"1500.50\"}").orElseThrow(AssertionError::new);
        assertThat(parser.decimal(n, "a")).isEqualByComparingTo("1500.50");
        assertThat(parser.decimal(n, "b")).isEqualByComparingTo("1500.50");
    }

    @Test
    void missingFieldsYieldNullNotZero() {
        JsonNode n = parser.parse("{}").orElseThrow(AssertionError::new);
        assertThat(parser.decimal(n, "amount")).isNull();
        assertThat(parser.integer(n, "quantity")).isNull();
        assertThat(parser.str(n, "symbol")).isNull();
        assertThat(parser.confidence(n, "confidence")).isNull();
    }

    @Test
    void treatsModelFillerAsAbsent() {
        JsonNode n = parser.parse("{\"symbol\":\"unknown\",\"folio\":\"N/A\",\"bank\":\"null\",\"name\":\"  \"}")
            .orElseThrow(AssertionError::new);
        assertThat(parser.str(n, "symbol")).isNull();
        assertThat(parser.str(n, "folio")).isNull();
        assertThat(parser.str(n, "bank")).isNull();
        assertThat(parser.str(n, "name")).isNull();
    }

    @Test
    void confidenceOutsideZeroToOneIsRejected() {
        JsonNode n = parser.parse("{\"high\":95,\"neg\":-0.5,\"ok\":0.85}").orElseThrow(AssertionError::new);
        // A model answering "95" (percent) must not be read as 95.0 confidence — unknown
        // confidence has to route to review rather than auto-accept.
        assertThat(parser.confidence(n, "high")).isNull();
        assertThat(parser.confidence(n, "neg")).isNull();
        assertThat(parser.confidence(n, "ok")).isEqualTo(0.85);
    }

    @Test
    void fractionalValueWhereACountIsRequiredIsRefused() {
        JsonNode n = parser.parse("{\"quantity\":10.5}").orElseThrow(AssertionError::new);
        // Rounding a share count is a real money error — refuse instead.
        assertThat(parser.integer(n, "quantity")).isNull();
    }
}
