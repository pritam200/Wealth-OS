package com.marketai.gmail.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watermark rules. Each case here maps to a documented way of permanently losing a user's
 * transactions, so these are correctness tests rather than coverage filler.
 */
class GmailIncrementalSyncServiceTest {

    private final GmailIncrementalSyncService service = new GmailIncrementalSyncService();

    @Test
    void watermarkOnlyEverMovesForward() {
        // Gmail push is at-least-once and notifications can arrive late or repeat. Rewinding
        // would re-import an already-processed window and lean entirely on the fingerprint
        // gate to undo it.
        assertThat(service.advanceWatermark("1000", "1500")).isEqualTo("1500");
        assertThat(service.advanceWatermark("1000", "999")).isNull();
        assertThat(service.advanceWatermark("1000", "1000")).isNull();
    }

    @Test
    void firstWatermarkIsAccepted() {
        assertThat(service.advanceWatermark(null, "4242")).isEqualTo("4242");
        assertThat(service.advanceWatermark("", "4242")).isEqualTo("4242");
        assertThat(service.advanceWatermark("   ", "4242")).isEqualTo("4242");
    }

    @Test
    void absentCandidateLeavesTheWatermarkAlone() {
        // An empty delta must not advance anything — Gmail's history index lags notifications,
        // so "no records" is routinely a timing artefact rather than "nothing happened".
        assertThat(service.advanceWatermark("1000", null)).isNull();
        assertThat(service.advanceWatermark("1000", "")).isNull();
    }

    @Test
    void historyIdsBeyondLongRangeAreHandled() {
        // Gmail historyIds are unsigned 64-bit; parsing them as a long would overflow and
        // could silently produce a *lower* value, rewinding the watermark.
        String big      = "18446744073709551615";   // 2^64 - 1
        String bigger   = "18446744073709551616";
        assertThat(service.advanceWatermark(big, bigger)).isEqualTo(bigger);
        assertThat(service.advanceWatermark(bigger, big)).isNull();
    }

    @Test
    void unparseableWatermarkIsNeverGuessedAt() {
        // Refusing to move is the safe failure: the next run re-reads the same window, whereas
        // a guess could skip messages for good.
        assertThat(service.advanceWatermark("not-a-number", "1500")).isNull();
        assertThat(service.advanceWatermark("1000", "not-a-number")).isNull();
    }

    @Test
    void missingWatermarkForcesAFullSync() {
        GmailIncrementalSyncService.Delta d = service.fetchDelta(null, null);
        assertThat(d.isUsable()).isFalse();
        assertThat(d.getReason()).contains("first sync must be full");

        assertThat(service.fetchDelta(null, "   ").isUsable()).isFalse();
    }
}
