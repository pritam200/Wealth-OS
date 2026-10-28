package com.marketai.mf.service;

import com.marketai.amfi.dto.AmfiNavResult;
import com.marketai.amfi.service.AmfiNavService;
import com.marketai.mf.dto.MfPerformanceMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MfPeerComparisonServiceTest {

    private static final String CAT = "Equity Scheme - Flexi Cap Fund";
    private final AmfiNavService amfi = mock(AmfiNavService.class);
    private final MfNavHistoryService nav = mock(MfNavHistoryService.class);
    private final MfPerformanceService perf = mock(MfPerformanceService.class);
    private final MfPeerComparisonService svc = new MfPeerComparisonService(amfi, nav, perf);
    { svc.background = Runnable::run; }   // the background load, run in place

    private static AmfiNavResult scheme(String code, String name) {
        return AmfiNavResult.builder().schemeCode(code).schemeName(name).category(CAT).schemeType("Open Ended").build();
    }

    private static MfPerformanceMetrics metrics(String code, double r1, double r3) {
        return MfPerformanceMetrics.builder().schemeCode(code).return1Y(BigDecimal.valueOf(r1))
                .return3YCagr(BigDecimal.valueOf(r3)).observationCount(900).build();
    }

    @Test
    void ranksAgainstSamePlanGrowthPeersOnly() {
        List<AmfiNavResult> all = new ArrayList<>();
        all.add(scheme("SELF", "Alpha Flexi Cap Fund - Direct Plan - Growth"));
        for (int i = 1; i <= 10; i++) {
            all.add(scheme("P" + i, "Peer " + i + " Flexi Cap - Direct - Growth"));
            when(perf.getPerformance("P" + i)).thenReturn(metrics("P" + i, 10 + i, 10 + i));
        }
        // Excluded: regular plan and IDCW option, whose returns would distort the ranking.
        all.add(scheme("REG", "Peer Flexi Cap - Regular Plan - Growth"));
        all.add(scheme("IDCW", "Peer Flexi Cap - Direct - IDCW"));
        when(perf.getPerformance("REG")).thenReturn(metrics("REG", 99, 99));
        when(perf.getPerformance("IDCW")).thenReturn(metrics("IDCW", 99, 99));
        when(perf.getPerformance("SELF")).thenReturn(metrics("SELF", 10, 10));
        when(amfi.all()).thenReturn(all);
        when(amfi.findByCode("SELF")).thenReturn(all.get(0));
        when(nav.latestNavDate(anyString())).thenReturn(Optional.of(LocalDate.now()));

        var c = svc.compare("SELF").orElseThrow();

        assertThat(c.peersInCategory()).isEqualTo(10);
        var r3 = c.rank("3Y").orElseThrow();
        assertThat(r3.rank()).isEqualTo(11);
        assertThat(r3.ranked()).isEqualTo(11);
        assertThat(r3.quartileLabel()).isEqualTo("bottom quartile");
        assertThat(c.leaders()).extracting(MfPeerComparisonService.PeerMetrics::schemeCode).containsExactly("P10", "P9", "P8");
    }

    @Test
    void tooFewPeersGivesNoRank() {
        List<AmfiNavResult> all = List.of(scheme("SELF", "Alpha - Direct - Growth"), scheme("P1", "Beta - Direct - Growth"));
        when(amfi.all()).thenReturn(all);
        when(amfi.findByCode("SELF")).thenReturn(all.get(0));
        when(perf.getPerformance(anyString())).thenReturn(metrics("x", 12, 12));
        when(nav.latestNavDate(anyString())).thenReturn(Optional.of(LocalDate.now()));

        assertThat(svc.compare("SELF").orElseThrow().ranks()).isEmpty();
    }
}
