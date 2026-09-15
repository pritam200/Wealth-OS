package com.marketai.common.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SufficiencyTest {

    @Test
    void fullResultsCarryTheirValue() {
        Sufficiency<BigDecimal> r = Sufficiency.full(new BigDecimal("1250.75"));

        assertThat(r.isUsable()).isTrue();
        assertThat(r.quality()).isEqualTo(DataQuality.FULL);
        assertThat(r.asOptional()).contains(new BigDecimal("1250.75"));
        assertThat(r.reason()).isNull();
    }

    @Test
    @DisplayName("an insufficient result carries no value to read by accident")
    void insufficientResultsHoldNoValue() {
        Sufficiency<BigDecimal> r = Sufficiency.insufficient(
                "Only 3 days of price history stored",
                "Fetch history for this symbol, then re-run");

        assertThat(r.isUsable()).isFalse();
        assertThat(r.value()).isNull();
        assertThat(r.asOptional()).isEmpty();
        assertThat(r.whatWouldFixIt()).isEqualTo("Fetch history for this symbol, then re-run");
    }

    @Test
    @DisplayName("declining without saying why, or without a remedy, is rejected at construction")
    void decliningMustBeExplained() {
        // "Insufficient data" on its own is a dead end for the user. Requiring both fields at
        // construction forces the question "what would actually fix this?" to be answered by
        // whoever writes the guard, not left to whoever reads the screen.
        assertThatThrownBy(() -> Sufficiency.insufficient(null, "fetch history"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");

        assertThatThrownBy(() -> Sufficiency.insufficient("no history", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whatWouldFixIt");

        assertThatThrownBy(() -> Sufficiency.partial(BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("map() never runs on a value that was never computed")
    void mapSkipsInsufficientResults() {
        AtomicBoolean mapperRan = new AtomicBoolean(false);

        Sufficiency<BigDecimal> mapped =
                Sufficiency.<BigDecimal>insufficient("no ATR available", "fetch price history")
                        .map(v -> { mapperRan.set(true); return v.multiply(BigDecimal.TEN); });

        // This is the property that stops downstream arithmetic from silently producing a
        // confident-looking number out of an absent input.
        assertThat(mapperRan).isFalse();
        assertThat(mapped.isUsable()).isFalse();
        assertThat(mapped.reason()).isEqualTo("no ATR available");
        assertThat(mapped.whatWouldFixIt()).isEqualTo("fetch price history");
    }

    @Test
    void mapPreservesPartialQualityAndItsReason() {
        Sufficiency<Integer> mapped = Sufficiency.partial(21, "under 200 bars — no 200-DMA")
                .map(v -> v * 2);

        assertThat(mapped.asOptional()).contains(42);
        assertThat(mapped.quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(mapped.reason()).isEqualTo("under 200 bars — no 200-DMA");
    }

    @Test
    @DisplayName("combining takes the weaker quality — a result is only as good as its worst input")
    void combineDegradesToTheWeakerInput() {
        Sufficiency<Integer> full = Sufficiency.full(10);
        Sufficiency<Integer> partial = Sufficiency.partial(5, "under 200 bars");

        assertThat(full.combine(Sufficiency.full(5), Integer::sum).quality())
                .isEqualTo(DataQuality.FULL);

        Sufficiency<Integer> mixed = full.combine(partial, Integer::sum);
        assertThat(mixed.asOptional()).contains(15);
        assertThat(mixed.quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(mixed.reason()).contains("under 200 bars");
    }

    @Test
    void combiningWithAnInsufficientInputYieldsInsufficient() {
        Sufficiency<Integer> bad = Sufficiency.insufficient("no price", "fetch quote");

        assertThat(Sufficiency.full(10).combine(bad, Integer::sum).isUsable()).isFalse();
        assertThat(bad.combine(Sufficiency.full(10), Integer::sum).isUsable()).isFalse();
    }

    @Test
    @DisplayName("both partial reasons survive a combine, so the caveat stays complete")
    void combineMergesBothReasons() {
        Sufficiency<Integer> merged = Sufficiency.partial(1, "stale NAV")
                .combine(Sufficiency.partial(2, "under 200 bars"), Integer::sum);

        assertThat(merged.reason()).contains("stale NAV").contains("under 200 bars");
    }
}
