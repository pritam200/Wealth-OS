package com.marketai.cas;

import com.marketai.dataplatform.repo.FinancialAssetRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A demat statement gives share holdings their ISIN only where exactly one line fits. */
class DematIsinBackfillTest {

    private final PortfolioRepository portfolios = mock(PortfolioRepository.class);
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final FinancialAssetRepository assets = mock(FinancialAssetRepository.class);
    private final DematIsinBackfill backfill = new DematIsinBackfill(portfolios, holdings, assets);

    private static Holding h(long id, String symbol, String name, String isin) {
        return Holding.builder().id(id).symbol(symbol).name(name).isin(isin).quantity(BigDecimal.ONE).build();
    }

    @Test
    @DisplayName("fills a missing ISIN by company name; never overwrites one, touches a fund, or guesses between two")
    void fillsOnlyWhatItCanTell() {
        Portfolio p = new Portfolio(); p.setId(1L);
        when(portfolios.findByUserId(2L)).thenReturn(List.of(p));
        Holding hal = h(1, "HAL.NS", "Hindustan Aeronautics Ltd", null);
        Holding tcs = h(2, "TCS.NS", "Tata Consultancy Services Ltd", "INE467B01029");
        Holding fund = h(3, "ICICIMC.MF", "Hindustan Aeronautics Ltd", null);
        Holding tata = h(4, "TATA.NS", "Tata Ltd", null);
        when(holdings.findByPortfolioId(1L)).thenReturn(List.of(hal, tcs, fund, tata));
        when(assets.findFirstByIsin(any())).thenReturn(Optional.empty());

        int filled = backfill.backfill(2L, List.of(
            new DematIsinBackfill.Line("INE066F01020", "HINDUSTAN AERONAUTICS LIMITED"),
            new DematIsinBackfill.Line("INE999A01011", "TCS LIMITED"),
            new DematIsinBackfill.Line("INE000T01011", "TATA LIMITED"),
            new DematIsinBackfill.Line("INE000T01029", "TATA LTD")));

        assertThat(filled).isEqualTo(1);
        assertThat(hal.getIsin()).isEqualTo("INE066F01020");
        assertThat(tcs.getIsin()).isEqualTo("INE467B01029");
        assertThat(fund.getIsin()).isNull();
        assertThat(tata.getIsin()).isNull();
        verify(holdings, times(1)).save(any());
    }
}
