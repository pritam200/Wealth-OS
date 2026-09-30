package com.marketai.research;

import com.marketai.research.model.Evidence;
import com.marketai.research.model.Fact;
import com.marketai.research.service.NumberGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NumberGuardTest {

    private final List<Fact> facts = List.of(
            Fact.of("MARKET", "DATA", "Last daily close", 2847.35, "₹", "2026-09-29", "Yahoo").withId("F1"),
            Fact.of("TECHNICAL", "CALCULATION", "RSI(14)", 61.237, null, "2026-09-29", "calc").withId("F2"),
            Fact.of("FUNDAMENTAL", "DATA", "Revenue", 9.87e11, "₹", "2026-06-30", "Yahoo").withId("F3"),
            Fact.of("FORECAST", "MODEL", "20D 90% model range — high", 3012.4, "₹", "2026-09-29", "model").withId("F4"),
            Fact.of("MARKET", "CALCULATION", "Nifty 50 — 20-session change", -3.42, "%", "2026-09-29", "calc").withId("F5"));
    private final List<Evidence> evidence = List.of(new Evidence("E1", "FILING", "PRIMARY", "NSE",
            "Board approves dividend of Rs 10 per share", LocalDateTime.of(2026, 9, 25, 18, 0), null, LocalDateTime.now(), "HIGH", null));

    @Test
    @DisplayName("figures that are in the facts survive, with rounding and Indian units")
    void verifiedFiguresKept() {
        NumberGuard g = new NumberGuard(facts, evidence);
        String t = "Closed at ₹2,847.35 with RSI 61.2; revenue ₹98,700 crore; Nifty fell 3.4% over 20 sessions; dividend of Rs 10 declared.";
        assertThat(g.clean(t)).isEqualTo(t);
        assertThat(g.removed()).isEmpty();
    }

    @Test
    @DisplayName("an invented target, probability or computed difference is removed and recorded")
    void inventedFiguresRemoved() {
        NumberGuard g = new NumberGuard(facts, evidence);
        String out = g.clean("We see a target of ₹3,400 with 72% probability, about 6.1% above SMA50.");
        assertThat(out).doesNotContain("3,400").doesNotContain("72%").doesNotContain("6.1%");
        assertThat(out).contains(NumberGuard.MARKER);
        assertThat(g.removed()).containsExactly("₹3,400", "72%", "6.1%");
        assertThat(new NumberGuard(facts, evidence).clean("Up 47, then 48.")).isEqualTo("Up " + NumberGuard.MARKER + ", then " + NumberGuard.MARKER + ".");
    }

    @Test
    @DisplayName("years, dates, indicator periods and small counts are not treated as figures")
    void exemptions() {
        NumberGuard g = new NumberGuard(facts, evidence);
        String t = "Since 2024 the SMA 200 and RSI 14 held; RSI below 30, ADX above 25; results on 2026-10-15 and 25-Sep-2026 in Q2 FY27; 3 filings.";
        assertThat(g.clean(t)).isEqualTo(t);
    }

    @Test
    @DisplayName("the forecast range is not re-quoted differently")
    void forecastBoundMustMatch() {
        NumberGuard g = new NumberGuard(facts, evidence);
        assertThat(g.clean("The 90% range tops out near ₹3,012.40.")).doesNotContain(NumberGuard.MARKER);
        assertThat(g.clean("The 90% range tops out near ₹3,150.")).contains(NumberGuard.MARKER);
    }
}
