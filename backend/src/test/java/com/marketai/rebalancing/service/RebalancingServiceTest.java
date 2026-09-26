package com.marketai.rebalancing.service;

import com.marketai.market.entity.Stock;
import com.marketai.market.repository.StockRepository;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.rebalancing.dto.RebalancingSuggestionsResponse;
import com.marketai.rebalancing.dto.TrimSuggestionDto;
import com.marketai.scoring.factor.QualityFactor;
import com.marketai.tax.lot.DisposalCalculator;
import com.marketai.tracking.entity.OtherAsset;
import com.marketai.tracking.repository.OtherAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RebalancingServiceTest {

    private static final Long USER = 1L;

    private PortfolioContextService portfolioContextService;
    private StockRepository stockRepository;
    private TransactionRepository transactionRepository;
    private OtherAssetRepository otherAssetRepository;
    private RebalancingService service;

    @BeforeEach
    void setup() {
        portfolioContextService = mock(PortfolioContextService.class);
        stockRepository = mock(StockRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        otherAssetRepository = mock(OtherAssetRepository.class);
        service = new RebalancingService(portfolioContextService, stockRepository, transactionRepository,
            otherAssetRepository, new DisposalCalculator(), new QualityFactor());

        when(otherAssetRepository.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(Collections.emptyList());
        when(stockRepository.findBySymbol(anyString())).thenReturn(Optional.empty());
        when(transactionRepository.findByHoldingIdOrderByTransactionDateAsc(anyLong())).thenReturn(Collections.emptyList());
    }

    private Holding stock(long id, String symbol, double qty, double avgCost, double price) {
        return Holding.builder()
            .id(id).symbol(symbol).name(symbol.replace(".NS", ""))
            .quantity(BigDecimal.valueOf(qty))
            .averageCost(BigDecimal.valueOf(avgCost))
            .currentPrice(BigDecimal.valueOf(price))
            .build();
    }

    private Transaction buy(Holding h, LocalDate date, double qty, double price) {
        return Transaction.builder().id((long) (Math.random() * 1_000_000)).holding(h)
            .type(Transaction.TransactionType.BUY)
            .quantity(BigDecimal.valueOf(qty)).price(BigDecimal.valueOf(price))
            .transactionDate(date).build();
    }

    private PortfolioContext baseContext(BigDecimal totalAssets, BigDecimal stocksValue,
                                         List<PortfolioContext.Flag> flags,
                                         List<PortfolioContext.Exposure> sectorExposures) {
        return PortfolioContext.builder()
            .totalAssets(totalAssets)
            .stocksValue(stocksValue).mfValue(BigDecimal.ZERO)
            .fdValue(BigDecimal.ZERO).rdValue(BigDecimal.ZERO).epfValue(BigDecimal.ZERO)
            .otherAssetsValue(BigDecimal.ZERO).cashValue(BigDecimal.ZERO)
            .concentrationFlags(flags)
            .sectorExposures(sectorExposures)
            .dataGaps(new java.util.ArrayList<>())
            .build();
    }

    // --- Concentration → trim sizing ---

    @Test
    @DisplayName("a single-stock flag produces a trim sized to the 10%-of-assets guideline")
    void singleStockFlagProducesSizedTrim() {
        // 90,000 of BIGCO in a 100,000-asset portfolio: target is 10,000, so trim 80,000 worth.
        Holding bigco = stock(1L, "BIGCO.NS", 900, 100, 100);
        when(portfolioContextService.getAllHoldings(USER)).thenReturn(List.of(bigco));

        PortfolioContext.Flag flag = PortfolioContext.Flag.builder()
            .type("SINGLE_STOCK").label("BIGCO").percent(90.0).severity("HIGH")
            .message("BIGCO is 90.0% of your total assets").build();
        PortfolioContext ctx = baseContext(new BigDecimal("100000"), new BigDecimal("90000"),
            List.of(flag), List.of());
        when(portfolioContextService.build(USER)).thenReturn(ctx);

        when(transactionRepository.findByHoldingIdOrderByTransactionDateAsc(1L))
            .thenReturn(List.of(buy(bigco, LocalDate.of(2024, 1, 1), 900, 100)));

        RebalancingSuggestionsResponse resp = service.build(USER);

        assertThat(resp.getTrimSuggestions()).hasSize(1);
        TrimSuggestionDto s = resp.getTrimSuggestions().get(0);
        assertThat(s.getSymbol()).isEqualTo("BIGCO.NS");
        // Excess = 90,000 - 10,000 = 80,000; at price 100 that's 800 units.
        assertThat(s.getSuggestedSellUnits()).isEqualByComparingTo("800");
        assertThat(s.getSuggestedSellValue()).isEqualByComparingTo("80000.00");
        assertThat(s.getTaxImpact()).isNotNull();
    }

    @Test
    @DisplayName("no trim is suggested once the flagged holding no longer breaches either guideline")
    void noSuggestionWhenNotActuallyBreaching() {
        Holding ok = stock(2L, "OK.NS", 50, 100, 100);
        when(portfolioContextService.getAllHoldings(USER)).thenReturn(List.of(ok));

        // Stale flag from an earlier snapshot; fresh numbers (5,000 of 100,000 assets, and of a
        // 50,000 equity book) are under both the 10%-of-assets and 25%-of-equity guidelines.
        PortfolioContext.Flag flag = PortfolioContext.Flag.builder()
            .type("SINGLE_STOCK").label("OK").percent(90.0).severity("HIGH").message("stale").build();
        PortfolioContext ctx = baseContext(new BigDecimal("100000"), new BigDecimal("50000"),
            List.of(flag), List.of());
        when(portfolioContextService.build(USER)).thenReturn(ctx);

        RebalancingSuggestionsResponse resp = service.build(USER);

        assertThat(resp.getTrimSuggestions()).isEmpty();
    }

    // --- Sector flag → prefers the lower-quality holding ---

    @Test
    @DisplayName("a sector flag picks the lower-quality holding among the sector's members to trim")
    void sectorFlagPicksLowerQualityHolding() {
        Holding strong = stock(3L, "STRONG.NS", 100, 100, 100); // 10,000
        Holding weak = stock(4L, "WEAK.NS", 100, 100, 100);     // 10,000
        when(portfolioContextService.getAllHoldings(USER)).thenReturn(List.of(strong, weak));

        when(stockRepository.findBySymbol("STRONG.NS")).thenReturn(Optional.of(Stock.builder()
            .symbol("STRONG.NS").sector("Financial Services")
            .roe(new BigDecimal("0.25")).debtToEquity(new BigDecimal("0.10")).build()));
        when(stockRepository.findBySymbol("WEAK.NS")).thenReturn(Optional.of(Stock.builder()
            .symbol("WEAK.NS").sector("Financial Services")
            .roe(new BigDecimal("0.02")).debtToEquity(new BigDecimal("0.90")).build()));

        // Sector holds 20,000 of a 20,000 known-sector book = 100% > 30% guideline.
        PortfolioContext.Exposure sectorExposure = PortfolioContext.Exposure.builder()
            .label("Financial Services").value(new BigDecimal("20000")).percentOfEquity(100.0).build();
        PortfolioContext.Flag flag = PortfolioContext.Flag.builder()
            .type("SECTOR").label("Financial Services").percent(100.0).severity("HIGH")
            .message("100% of your equity is in Financial Services").build();
        PortfolioContext ctx = baseContext(new BigDecimal("100000"), new BigDecimal("20000"),
            List.of(flag), List.of(sectorExposure));
        when(portfolioContextService.build(USER)).thenReturn(ctx);

        when(transactionRepository.findByHoldingIdOrderByTransactionDateAsc(4L))
            .thenReturn(List.of(buy(weak, LocalDate.of(2024, 1, 1), 100, 100)));

        RebalancingSuggestionsResponse resp = service.build(USER);

        assertThat(resp.getTrimSuggestions()).hasSize(1);
        assertThat(resp.getTrimSuggestions().get(0).getSymbol()).isEqualTo("WEAK.NS");
        assertThat(resp.getTrimSuggestions().get(0).getSelectionReason()).contains("weakest quality");
    }

    // --- Tax impact is real, from actual lots ---

    @Test
    @DisplayName("tax impact is computed from the holding's own FIFO lots, not a flat estimate")
    void taxImpactUsesRealLots() {
        Holding h = stock(5L, "GAINCO.NS", 1000, 100, 200);
        when(portfolioContextService.getAllHoldings(USER)).thenReturn(List.of(h));

        // Bought long ago at 100; selling now at 200 is a clean long-term gain per unit of 100.
        when(transactionRepository.findByHoldingIdOrderByTransactionDateAsc(5L))
            .thenReturn(List.of(buy(h, LocalDate.of(2020, 1, 1), 1000, 100)));

        PortfolioContext.Flag flag = PortfolioContext.Flag.builder()
            .type("SINGLE_STOCK").label("GAINCO").percent(90.0).severity("HIGH").message("x").build();
        // 200,000 of a 220,000 book (>10% guideline) → target 22,000, trim 178,000 worth = 890 units.
        PortfolioContext ctx = baseContext(new BigDecimal("220000"), new BigDecimal("200000"),
            List.of(flag), List.of());
        when(portfolioContextService.build(USER)).thenReturn(ctx);

        RebalancingSuggestionsResponse resp = service.build(USER);

        TrimSuggestionDto s = resp.getTrimSuggestions().get(0);
        assertThat(s.getTaxImpact()).isNotNull();
        // Gain per unit = 100, so long-term gain = units sold * 100, all long-term (bought 2020).
        BigDecimal expectedLtGain = s.getSuggestedSellUnits().multiply(new BigDecimal("100"));
        assertThat(s.getTaxImpact().getLongTermGain()).isEqualByComparingTo(expectedLtGain.setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(s.getTaxImpact().getShortTermGain()).isEqualByComparingTo("0.00");
        assertThat(s.getTaxImpact().getTax()).isNotNull();
    }

    @Test
    @DisplayName("no tax figure is shown when there is no transaction history to derive lots from")
    void noTaxFigureWithoutTransactionHistory() {
        Holding h = stock(6L, "NOTXN.NS", 900, 100, 100);
        when(portfolioContextService.getAllHoldings(USER)).thenReturn(List.of(h));
        when(transactionRepository.findByHoldingIdOrderByTransactionDateAsc(6L)).thenReturn(Collections.emptyList());

        PortfolioContext.Flag flag = PortfolioContext.Flag.builder()
            .type("SINGLE_STOCK").label("NOTXN").percent(90.0).severity("HIGH").message("x").build();
        PortfolioContext ctx = baseContext(new BigDecimal("100000"), new BigDecimal("90000"),
            List.of(flag), List.of());
        when(portfolioContextService.build(USER)).thenReturn(ctx);

        RebalancingSuggestionsResponse resp = service.build(USER);

        TrimSuggestionDto s = resp.getTrimSuggestions().get(0);
        assertThat(s.getTaxImpact()).isNull();
        assertThat(s.getTaxImpactGap()).contains("No purchase-transaction history");
    }

    // --- Asset-class breakdown ---

    @Test
    @DisplayName("gold is broken out from other assets by its recorded category, not estimated")
    void goldBrokenOutFromOtherAssets() {
        when(portfolioContextService.getAllHoldings(USER)).thenReturn(Collections.emptyList());
        when(otherAssetRepository.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(Arrays.asList(
            OtherAsset.builder().id(1L).name("Gold coins").category("gold").value(new BigDecimal("30000")).build(),
            OtherAsset.builder().id(2L).name("Flat").category("realestate").value(new BigDecimal("70000")).build()));

        PortfolioContext ctx = baseContext(new BigDecimal("100000"), BigDecimal.ZERO, List.of(), List.of());
        ctx.setOtherAssetsValue(new BigDecimal("100000"));
        when(portfolioContextService.build(USER)).thenReturn(ctx);

        RebalancingSuggestionsResponse resp = service.build(USER);

        assertThat(resp.getAssetClassBreakdown())
            .anyMatch(e -> "Gold".equals(e.getLabel()) && e.getValue().compareTo(new BigDecimal("30000")) == 0);
        assertThat(resp.getAssetClassBreakdown())
            .anyMatch(e -> "Other".equals(e.getLabel()) && e.getValue().compareTo(new BigDecimal("70000")) == 0);
    }

    @Test
    @DisplayName("never proposes a target allocation — the response is scoped as current-state only")
    void scopeNoteDisclaimsTargetAllocation() {
        when(portfolioContextService.getAllHoldings(USER)).thenReturn(Collections.emptyList());
        when(portfolioContextService.build(USER)).thenReturn(baseContext(BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of()));

        RebalancingSuggestionsResponse resp = service.build(USER);

        assertThat(resp.getScopeNote()).contains("not a target-allocation rebalancer");
    }
}
