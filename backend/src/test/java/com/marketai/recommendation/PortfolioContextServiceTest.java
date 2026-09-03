package com.marketai.recommendation;

import com.marketai.market.entity.Stock;
import com.marketai.market.repository.StockRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.tracking.dto.TrackingSummaryResponse;
import com.marketai.tracking.service.TrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioContextServiceTest {

    private static final Long USER = 1L;
    private static final Long PF = 10L;

    private PortfolioRepository portfolioRepo;
    private HoldingRepository holdingRepo;
    private StockRepository stockRepo;
    private TrackingService tracking;
    private PortfolioContextService service;

    @BeforeEach
    void setup() {
        portfolioRepo = mock(PortfolioRepository.class);
        holdingRepo = mock(HoldingRepository.class);
        stockRepo = mock(StockRepository.class);
        tracking = mock(TrackingService.class);
        service = new PortfolioContextService(portfolioRepo, holdingRepo, stockRepo, tracking);

        when(portfolioRepo.findByUserIdOrderByIdAsc(USER))
            .thenReturn(Collections.singletonList(Portfolio.builder().id(PF).build()));
        when(stockRepo.findBySymbol(anyString())).thenReturn(Optional.empty());
        when(tracking.getSummary(anyLong())).thenReturn(emptyTracking());
    }

    private TrackingSummaryResponse emptyTracking() {
        return TrackingSummaryResponse.builder()
            .totalFdCurrentValue(BigDecimal.ZERO).totalFdPrincipal(BigDecimal.ZERO)
            .totalRdCurrentValue(BigDecimal.ZERO).totalEpf(BigDecimal.ZERO)
            .totalOtherAssets(BigDecimal.ZERO).totalLoanOutstanding(BigDecimal.ZERO)
            .build();
    }

    private Holding stock(String symbol, double qty, double avgCost, double price) {
        return Holding.builder()
            .id(Math.abs((long) symbol.hashCode()))
            .symbol(symbol).name(symbol.replace(".NS", ""))
            .quantity(BigDecimal.valueOf(qty))
            .averageCost(BigDecimal.valueOf(avgCost))
            .currentPrice(BigDecimal.valueOf(price))
            .build();
    }

    private void withStock(String symbol, String sector) {
        when(stockRepo.findBySymbol(symbol))
            .thenReturn(Optional.of(Stock.builder().symbol(symbol).sector(sector).build()));
    }

    @Test
    @DisplayName("Flags a single stock that dominates the portfolio")
    void flagsSingleStockConcentration() {
        // One position worth 90,000 of a 100,000 book.
        when(holdingRepo.findByPortfolioId(PF)).thenReturn(Arrays.asList(
            stock("BIGCO.NS", 900, 100, 100),
            stock("SMALLCO.NS", 100, 100, 100)));

        PortfolioContext ctx = service.build(USER);

        assertThat(ctx.getStockCount()).isEqualTo(2);
        assertThat(ctx.getConcentrationFlags())
            .anyMatch(f -> "SINGLE_STOCK".equals(f.getType()) && f.getLabel().contains("BIGCO"));
    }

    @Test
    @DisplayName("Flags sector concentration and reports sector coverage honestly")
    void flagsSectorConcentrationAndReportsCoverage() {
        withStock("BANKA.NS", "Financial Services");
        withStock("BANKB.NS", "Financial Services");
        // BANKC has no Stock row → unknown sector, must reduce reported coverage.
        when(holdingRepo.findByPortfolioId(PF)).thenReturn(Arrays.asList(
            stock("BANKA.NS", 100, 100, 100),
            stock("BANKB.NS", 100, 100, 100),
            stock("BANKC.NS", 100, 100, 100)));

        PortfolioContext ctx = service.build(USER);

        assertThat(ctx.getConcentrationFlags())
            .anyMatch(f -> "SECTOR".equals(f.getType()) && f.getLabel().contains("Financial"));
        // 2 of 3 equal-sized positions have a known sector.
        assertThat(ctx.getSectorCoveragePercent()).isEqualTo(66.7);
        assertThat(ctx.getDataQuality()).isEqualTo("PARTIAL");
        assertThat(ctx.getDataGaps()).anyMatch(g -> g.contains("Sector is known for only"));
    }

    @Test
    @DisplayName("Declares MF look-through as unavailable rather than implying full coverage")
    void declaresMfLookThroughGap() {
        when(holdingRepo.findByPortfolioId(PF)).thenReturn(Arrays.asList(
            stock("RELIANCE.NS", 10, 100, 120),
            stock("SOMEFUND.MF", 100, 50, 55)));

        PortfolioContext ctx = service.build(USER);

        assertThat(ctx.getStockCount()).isEqualTo(1);
        assertThat(ctx.getMfCount()).isEqualTo(1);
        assertThat(ctx.getDataGaps())
            .anyMatch(g -> g.contains("cannot be looked through to underlying stocks"));
        assertThat(ctx.getSectorScopeNote()).contains("Direct stocks only");
    }

    @Test
    @DisplayName("Percentages are null, not zero, when there is nothing to divide by")
    void undefinedPercentagesAreNull() {
        when(holdingRepo.findByPortfolioId(PF)).thenReturn(Collections.<Holding>emptyList());

        PortfolioContext ctx = service.build(USER);

        assertThat(ctx.getTotalAssets()).isEqualByComparingTo("0");
        // An undefined share must not be presented as "0% in equity".
        assertThat(ctx.getEquityPercent()).isNull();
        assertThat(ctx.getEquityPnlPercent()).isNull();
    }

    @Test
    @DisplayName("Equity P&L aggregates across every portfolio, not just the largest")
    void aggregatesAcrossAllPortfolios() {
        Long pf2 = 11L;
        when(portfolioRepo.findByUserIdOrderByIdAsc(USER)).thenReturn(Arrays.asList(
            Portfolio.builder().id(PF).build(), Portfolio.builder().id(pf2).build()));
        when(holdingRepo.findByPortfolioId(PF)).thenReturn(Collections.singletonList(
            stock("AAA.NS", 10, 100, 150)));   // invested 1000, current 1500
        when(holdingRepo.findByPortfolioId(pf2)).thenReturn(Collections.singletonList(
            stock("BBB.NS", 10, 100, 50)));    // invested 1000, current 500

        PortfolioContext ctx = service.build(USER);

        assertThat(ctx.getStockCount()).isEqualTo(2);
        assertThat(ctx.getEquityInvested()).isEqualByComparingTo("2000.00");
        assertThat(ctx.getEquityCurrent()).isEqualByComparingTo("2000.00");
        assertThat(ctx.getEquityPnl()).isEqualByComparingTo("0.00");
    }
}
