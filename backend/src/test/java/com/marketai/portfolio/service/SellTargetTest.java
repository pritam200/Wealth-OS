package com.marketai.portfolio.service;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Which holding an emailed sale reduces. */
class SellTargetTest {

    private static final Long USER_ID = 1L;
    private static final Long MSTOCK = 10L, GENERAL = 20L, ZERODHA = 30L;
    private static final Predicate<String> BROKER_NAMED = n -> n.toLowerCase().contains("mstock") || n.toLowerCase().contains("zerodha");

    private HoldingRepository holdings;
    private PortfolioService service;

    @BeforeEach
    void setup() {
        PortfolioRepository portfolios = mock(PortfolioRepository.class);
        holdings = mock(HoldingRepository.class);
        service = new PortfolioService(portfolios, holdings, mock(TransactionRepository.class),
            mock(com.marketai.auth.repository.UserRepository.class), mock(com.marketai.market.service.MarketDataService.class),
            mock(com.marketai.redemption.service.RedemptionService.class), mock(com.marketai.mf.service.MfNavHistoryService.class));
        when(portfolios.findByUserIdOrderByIdAsc(USER_ID)).thenReturn(List.of(
            Portfolio.builder().id(MSTOCK).name("mStock").build(),
            Portfolio.builder().id(GENERAL).name("My stocks").build(),
            Portfolio.builder().id(ZERODHA).name("Zerodha").build()));
        when(holdings.findByPortfolioIdAndSymbol(anyLong(), anyString())).thenReturn(Optional.empty());
        when(holdings.findByPortfolioIdAndIsinIgnoreCase(anyLong(), anyString())).thenReturn(List.of());
        when(holdings.findByPortfolioId(anyLong())).thenReturn(List.of());
    }

    private static Holding h(long id, String symbol, String isin, String broker) {
        return Holding.builder().id(id).symbol(symbol).isin(isin).broker(broker).quantity(BigDecimal.TEN).build();
    }

    @Test
    @DisplayName("the broker's own portfolio comes first, by symbol and then by ISIN")
    void brokerPortfolioFirst() {
        when(holdings.findByPortfolioIdAndIsinIgnoreCase(MSTOCK, "INE002A01018")).thenReturn(List.of(h(5, "RELIANCE.NS", "INE002A01018", null)));
        assertThat(service.findSellTarget(USER_ID, MSTOCK, "RELIANCE", "INE002A01018", "mStock", BROKER_NAMED))
            .contains(new PortfolioService.SellTarget(MSTOCK, 5L));
    }

    @Test
    @DisplayName("otherwise the one matching holding in a general portfolio; never one at another broker")
    void elsewhereOnlyWhenUnambiguous() {
        when(holdings.findByPortfolioId(GENERAL)).thenReturn(List.of(h(7, "RELIANCE", "INE002A01018", null)));
        when(holdings.findByPortfolioId(ZERODHA)).thenReturn(List.of(h(8, "RELIANCE", "INE002A01018", "Zerodha")));
        assertThat(service.findSellTarget(USER_ID, MSTOCK, "RELIANCE", "INE002A01018", "mStock", BROKER_NAMED))
            .contains(new PortfolioService.SellTarget(GENERAL, 7L));

        when(holdings.findByPortfolioId(GENERAL)).thenReturn(List.of(h(7, "RELIANCE", "INE002A01018", null), h(9, "RELIANCE", "INE002A01018", null)));
        assertThat(service.findSellTarget(USER_ID, MSTOCK, "RELIANCE", "INE002A01018", "mStock", BROKER_NAMED))
            .as("two candidates: no safe choice").isEmpty();
    }
}
