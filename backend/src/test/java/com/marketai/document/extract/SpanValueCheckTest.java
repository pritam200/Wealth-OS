package com.marketai.document.extract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The extracted figures must actually be written in the line the model cited. */
class SpanValueCheckTest {

    private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);

    @Test
    @DisplayName("amounts are found however Indian statements write them")
    void amountFormats() {
        assertThat(SpanVerifier.containsAmount("Rs.1,23,456.00 debited from a/c XX1234", new BigDecimal("123456"))).isTrue();
        assertThat(SpanVerifier.containsAmount("₹499.98 spent on card", new BigDecimal("499.98"))).isTrue();
        assertThat(SpanVerifier.containsAmount("INR 250 at SWIGGY", new BigDecimal("250.00"))).isTrue();
    }

    @Test
    @DisplayName("a real line quoted with a different amount is caught")
    void wrongAmountIsCaught() {
        assertThat(SpanVerifier.containsAmount("Rs 2,500.00 debited on 10-09-2026", new BigDecimal("25000"))).isFalse();
        assertThat(SpanVerifier.containsAmount("Rs 2,500.00 debited", new BigDecimal("250"))).isFalse();
    }

    @Test
    @DisplayName("dates are found in the usual formats, including year-less statement rows")
    void dateFormats() {
        assertThat(SpanVerifier.containsDate("debited on 10-09-2026", SEP_10)).isTrue();
        assertThat(SpanVerifier.containsDate("on 10/09/26 at 14:02", SEP_10)).isTrue();
        assertThat(SpanVerifier.containsDate("2026-09-10 | SIP | 5000", SEP_10)).isTrue();
        assertThat(SpanVerifier.containsDate("10 Sep 2026 UPI/SWIGGY", SEP_10)).isTrue();
        assertThat(SpanVerifier.containsDate("Sep 10, 2026", SEP_10)).isTrue();
        assertThat(SpanVerifier.containsDate("10-SEP | AMAZON | 999.00", SEP_10)).isTrue();
        assertThat(SpanVerifier.containsDate("10th September 2026", SEP_10)).isTrue();
    }

    @Test
    @DisplayName("a different day, month or year is not accepted")
    void wrongDateIsCaught() {
        assertThat(SpanVerifier.containsDate("debited on 11-09-2026", SEP_10)).isFalse();
        assertThat(SpanVerifier.containsDate("10 Oct 2026", SEP_10)).isFalse();
        assertThat(SpanVerifier.containsDate("10-09-2025", SEP_10)).isFalse();
        assertThat(SpanVerifier.containsDate("Rs 10.09 cashback", SEP_10)).isFalse();
    }
}
