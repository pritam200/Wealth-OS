package com.marketai.dataplatform.service;

import com.marketai.portfolio.dto.PortfolioSummaryDto.HoldingDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HoldingVerificationOverlayTest {

    private static DataQualityService.HoldingHealth hh(String isin, String symbol, String qty, String state) {
        return new DataQualityService.HoldingHealth(1L, 1L, "Fund", symbol, isin, new BigDecimal("100"),
            qty == null ? null : new BigDecimal(qty), state, "ACCOUNT_AGGREGATOR/mock", null, LocalDateTime.of(2026, 10, 1, 9, 0), null);
    }

    private DataQualityService.Health health(DataQualityService.HoldingHealth... h) {
        return new DataQualityService.Health(100.0, "f", Map.of(), 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), true, null, List.of(), List.of(h), List.of());
    }

    @Test @DisplayName("a holding is annotated by ISIN, keeping its own quantity and adding the institution's beside it")
    void annotatesByIsin() {
        DataQualityService q = mock(DataQualityService.class);
        when(q.health(anyLong(), anyBoolean())).thenReturn(health(hh("INF1", null, "112", "NEEDS_RECONCILIATION")));
        HoldingDto h = HoldingDto.builder().isin("inf1").symbol("X.MF").quantity(new BigDecimal("100")).build();
        new HoldingVerificationOverlay(q).annotate(1L, List.of(h));
        assertThat(h.getVerificationState()).isEqualTo("NEEDS_RECONCILIATION");
        assertThat(h.getInstitutionQuantity()).isEqualByComparingTo("112");
        assertThat(h.getQuantity()).isEqualByComparingTo("100");          // never replaced
        assertThat(h.getLastVerifiedAt()).isNotNull();
    }

    @Test @DisplayName("without an ISIN it falls back to the symbol ignoring the exchange suffix; an unrelated holding is left unannotated")
    void symbolFallback() {
        DataQualityService q = mock(DataQualityService.class);
        when(q.health(anyLong(), anyBoolean())).thenReturn(health(hh(null, "INFY", null, "UNVERIFIED")));
        HoldingDto infy = HoldingDto.builder().symbol("INFY.NS").build();
        HoldingDto other = HoldingDto.builder().symbol("TCS.NS").build();
        new HoldingVerificationOverlay(q).annotate(1L, List.of(infy, other));
        assertThat(infy.getVerificationState()).isEqualTo("UNVERIFIED");
        assertThat(other.getVerificationState()).isNull();
    }

    @Test @DisplayName("if the ledger cannot be read the holdings are returned untouched, not failed")
    void failureIsHarmless() {
        DataQualityService q = mock(DataQualityService.class);
        when(q.health(anyLong(), anyBoolean())).thenThrow(new IllegalStateException("db down"));
        HoldingDto h = HoldingDto.builder().symbol("A").build();
        new HoldingVerificationOverlay(q).annotate(1L, List.of(h));
        assertThat(h.getVerificationState()).isNull();
    }
}
