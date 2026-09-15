package com.marketai.document.extract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanVerifierTest {

    private static final String CONTRACT_NOTE = """
        Contract Note cum Tax Invoice
        Trade Date: 12-Sep-2026
        Symbol: RELIANCE   Qty: 25   Rate: 1,412.50
        Brokerage: 20.00   Net Amount: 35,332.50
        """;

    @Test
    void groundedFieldsVerify() {
        SpanVerifier.Result r = SpanVerifier.verify(CONTRACT_NOTE, List.of(
            ExtractedField.ofEmail("symbol", "RELIANCE", "Symbol: RELIANCE"),
            ExtractedField.ofEmail("quantity", "25", "Qty: 25"),
            ExtractedField.ofEmail("price", "1412.50", "Rate: 1,412.50")));

        assertThat(r.allFound()).isTrue();
        assertThat(r.score()).isEqualTo(1.0);
        assertThat(r.unfoundFields()).isEmpty();
    }

    @Test
    @DisplayName("a value that is not in the document is caught, however plausible it looks")
    void fabricatedValuesAreCaught() {
        // The shape is right, the format is right, the number is invented. Nothing about the
        // JSON would reveal that — only checking it against the source does.
        SpanVerifier.Result r = SpanVerifier.verify(CONTRACT_NOTE, List.of(
            ExtractedField.ofEmail("symbol", "RELIANCE", "Symbol: RELIANCE"),
            ExtractedField.ofEmail("stt", "42.15", "STT: 42.15")));

        assertThat(r.allFound()).isFalse();
        assertThat(r.unfoundFields()).containsExactly("stt");
        assertThat(r.score()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("line wrapping from PDF extraction does not cause a false negative")
    void whitespaceIsNormalised() {
        String wrapped = "Net\n  Amount:\t35,332.50";
        SpanVerifier.Result r = SpanVerifier.verify(wrapped, List.of(
            ExtractedField.ofEmail("netAmount", "35332.50", "Net Amount: 35,332.50")));

        assertThat(r.allFound()).isTrue();
    }

    @Test
    @DisplayName("extracting nothing is not the same as verifying everything")
    void emptyExtractionScoresZero() {
        // Scoring an empty extraction 1.0 would read as perfect confidence about nothing.
        SpanVerifier.Result r = SpanVerifier.verify(CONTRACT_NOTE, List.of());

        assertThat(r.allFound()).isFalse();
        assertThat(r.score()).isZero();
        assertThat(r.checked()).isZero();
    }

    @Test
    void nullSourceGroundsNothing() {
        SpanVerifier.Result r = SpanVerifier.verify(null, List.of(
            ExtractedField.ofEmail("symbol", "RELIANCE", "Symbol: RELIANCE")));

        assertThat(r.allFound()).isFalse();
        assertThat(r.unfoundFields()).containsExactly("symbol");
    }

    @Test
    @DisplayName("a field cannot be constructed without a span")
    void spansAreMandatoryAtConstruction() {
        // Enforced at the type level rather than by a later check, so the guarantee cannot
        // erode one extractor at a time.
        assertThatThrownBy(() -> ExtractedField.ofEmail("quantity", "25", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no source span");

        assertThatThrownBy(() -> ExtractedField.ofEmail("quantity", "25", "   "))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ExtractedField.ofEmail("", "25", "Qty: 25"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("named");
    }
}
