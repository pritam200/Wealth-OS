package com.marketai.mf.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The confidence gate exists so a holding is left "not linked" rather than showing a
 * similarly-named fund's performance as its own.
 */
class MfSchemeLinkServiceTest {

    private MfSchemeLinkService service;

    @BeforeEach
    void setup() {
        service = new MfSchemeLinkService(null, null); // confidence() does no I/O
    }

    @Test
    @DisplayName("Identical names score a perfect match")
    void identicalNames() {
        String n = "SBI Blue Chip Fund - Direct Plan - Growth";
        assertThat(service.confidence(n, n)).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("Punctuation and spacing differences still score a perfect match")
    void punctuationIgnored() {
        double c = service.confidence(
                "HDFC Mid Cap Opportunities Fund - Direct Plan - Growth",
                "HDFC Mid Cap Opportunities Fund-Direct Plan-Growth");
        assertThat(c).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("Direct vs Regular is rejected outright, however similar the rest of the name")
    void directVsRegularRejected() {
        double c = service.confidence(
                "SBI Bluechip Fund - Direct Plan - Growth",
                "SBI Bluechip Fund - Regular Plan - Growth");

        assertThat(c).isZero();
        assertThat(c).isLessThan(MfSchemeLinkService.MIN_CONFIDENCE);
    }

    @Test
    @DisplayName("Growth vs IDCW is rejected outright")
    void growthVsIdcwRejected() {
        assertThat(service.confidence(
                "SBI Bluechip Fund - Direct Plan - Growth",
                "SBI Bluechip Fund - Direct Plan - IDCW")).isZero();

        assertThat(service.confidence(
                "Axis Small Cap Fund Direct Growth",
                "Axis Small Cap Fund Direct Dividend")).isZero();
    }

    @Test
    @DisplayName("A name that omits the plan/option suffix is NOT confidently linkable")
    void missingPlanSuffixIsNotConfident() {
        double c = service.confidence(
                "SBI Blue Chip Fund",
                "SBI Blue Chip Fund - Direct Plan - Growth");

        assertThat(c).isLessThan(MfSchemeLinkService.MIN_CONFIDENCE);
    }

    @Test
    @DisplayName("A single filler word difference stays above the threshold")
    void oneFillerWordStillMatches() {
        double c = service.confidence(
                "SBI Bluechip Fund - Direct Plan - Growth",
                "SBI Bluechip Fund - Direct - Growth");

        assertThat(c).isGreaterThanOrEqualTo(MfSchemeLinkService.MIN_CONFIDENCE);
    }

    @Test
    @DisplayName("Two different funds from the same AMC do not clear the threshold")
    void differentFundsSameAmcRejected() {
        assertThat(service.confidence(
                "HDFC Mid Cap Opportunities Fund - Direct Plan - Growth",
                "HDFC Small Cap Fund - Direct Plan - Growth"))
                .isLessThan(MfSchemeLinkService.MIN_CONFIDENCE);

        assertThat(service.confidence(
                "SBI Bluechip Fund - Direct Plan - Growth",
                "SBI Focused Equity Fund - Direct Plan - Growth"))
                .isLessThan(MfSchemeLinkService.MIN_CONFIDENCE);
    }

    @Test
    @DisplayName("Null names never match")
    void nullsNeverMatch() {
        assertThat(service.confidence(null, "SBI Bluechip Fund")).isZero();
        assertThat(service.confidence("SBI Bluechip Fund", null)).isZero();
    }

    @Test
    @DisplayName("An ISIN links exactly, even when the stored name would fail the name gate")
    void isinLinksWithoutTheNameGate() {
        var amfi = org.mockito.Mockito.mock(com.marketai.amfi.service.AmfiNavService.class);
        var repo = org.mockito.Mockito.mock(com.marketai.portfolio.repository.HoldingRepository.class);
        org.mockito.Mockito.when(amfi.findByIsin("INF179K01XQ0")).thenReturn(com.marketai.amfi.dto.AmfiNavResult.builder()
                .schemeCode("118989").schemeName("HDFC Mid Cap Fund - Growth Option - Direct Plan").build());
        com.marketai.portfolio.entity.Holding h = new com.marketai.portfolio.entity.Holding();
        h.setSymbol("HDFCMIDCAPFUNDDIRECTPLANGROWTH.MF");
        h.setName("HDFC Mid Cap");
        h.setIsin("INF179K01XQ0");

        assertThat(new MfSchemeLinkService(amfi, repo).resolve(h)).isEqualTo("118989");
        assertThat(h.getAmfiSchemeCode()).isEqualTo("118989");
    }
}
