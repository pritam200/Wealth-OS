package com.marketai.recommendation.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SipDetectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Test
    void monthlyPurchasesUpToNowAreASip() {
        assertThat(SipDetector.looksMonthly(List.of(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 8, 5),
            LocalDate.of(2026, 9, 5), LocalDate.of(2026, 10, 6)), TODAY)).isTrue();
    }

    @Test
    void oneMissedInstalmentIsStillASip() {
        assertThat(SipDetector.looksMonthly(List.of(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5),
            LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 5), LocalDate.of(2026, 10, 5)), TODAY)).isTrue();
    }

    @Test
    void oneOffPurchasesAreNot() {
        assertThat(SipDetector.looksMonthly(List.of(LocalDate.of(2026, 10, 14)), TODAY)).isFalse();
        assertThat(SipDetector.looksMonthly(List.of(LocalDate.of(2025, 1, 10), LocalDate.of(2025, 6, 2),
            LocalDate.of(2026, 9, 20)), TODAY)).isFalse();
    }

    @Test
    void aSipThatStoppedMonthsAgoIsNotRunning() {
        assertThat(SipDetector.looksMonthly(List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 5),
            LocalDate.of(2026, 3, 5), LocalDate.of(2026, 4, 5)), TODAY)).isFalse();
    }
}
