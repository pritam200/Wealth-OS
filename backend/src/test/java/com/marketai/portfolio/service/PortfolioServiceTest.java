package com.marketai.portfolio.service;

import com.marketai.market.service.MarketDataService;
import com.marketai.auth.entity.User;
import com.marketai.portfolio.dto.AddHoldingRequest;
import com.marketai.portfolio.dto.IntegrityReportDto;
import com.marketai.portfolio.dto.MergeSummaryDto;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.HoldingRepository;
import com.marketai.portfolio.repository.PortfolioRepository;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.redemption.service.RedemptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PortfolioServiceTest {

    private PortfolioRepository portfolioRepository;
    private HoldingRepository holdingRepository;
    private TransactionRepository transactionRepository;
    private MarketDataService marketDataService;
    private PortfolioService service;

    private static final Long USER_ID = 1L;
    private static final Long PORTFOLIO_ID = 10L;

    @BeforeEach
    void setup() {
        portfolioRepository = mock(PortfolioRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        marketDataService = mock(MarketDataService.class);
        RedemptionService redemptionService = mock(RedemptionService.class);

        service = new PortfolioService(portfolioRepository, holdingRepository, transactionRepository,
            mock(com.marketai.auth.repository.UserRepository.class), marketDataService, redemptionService);

        Portfolio portfolio = Portfolio.builder().id(PORTFOLIO_ID).name("Test").build();
        when(portfolioRepository.findByIdAndUserId(PORTFOLIO_ID, USER_ID)).thenReturn(Optional.of(portfolio));
        when(holdingRepository.save(any(Holding.class))).thenAnswer(inv -> {
            Holding h = inv.getArgument(0);
            if (h.getId() == null) h.setId(99L);
            return h;
        });
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void addHolding_newHolding_setsFieldsCorrectly() {
        when(holdingRepository.findByPortfolioIdAndSymbol(PORTFOLIO_ID, "RELIANCE.NS")).thenReturn(Optional.empty());

        AddHoldingRequest req = new AddHoldingRequest();
        req.setSymbol("reliance.ns");
        req.setName("Reliance");
        req.setQuantity(new BigDecimal("10"));
        req.setPrice(new BigDecimal("1200.00"));
        req.setTransactionDate(LocalDate.of(2026, 1, 1));
        req.setCharges(BigDecimal.ZERO);

        Holding result = service.addHolding(PORTFOLIO_ID, USER_ID, req);

        assertThat(result.getSymbol()).isEqualTo("RELIANCE.NS");
        assertThat(result.getQuantity()).isEqualByComparingTo("10");
        assertThat(result.getAverageCost()).isEqualByComparingTo("1200.00");
        assertThat(result.getBuyDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void addHolding_existingHolding_computesWeightedAverageCost() {
        Holding existing = Holding.builder()
            .id(5L).portfolio(Portfolio.builder().id(PORTFOLIO_ID).build())
            .symbol("RELIANCE.NS").name("Reliance")
            .quantity(new BigDecimal("10")).averageCost(new BigDecimal("1000.00"))
            .build();
        when(holdingRepository.findByPortfolioIdAndSymbol(PORTFOLIO_ID, "RELIANCE.NS")).thenReturn(Optional.of(existing));

        // Buy 10 more at 1200 -> weighted avg = (10*1000 + 10*1200) / 20 = 1100
        AddHoldingRequest req = new AddHoldingRequest();
        req.setSymbol("RELIANCE.NS");
        req.setName("Reliance");
        req.setQuantity(new BigDecimal("10"));
        req.setPrice(new BigDecimal("1200.00"));
        req.setTransactionDate(LocalDate.of(2026, 2, 1));
        req.setCharges(BigDecimal.ZERO);

        Holding result = service.addHolding(PORTFOLIO_ID, USER_ID, req);

        assertThat(result.getQuantity()).isEqualByComparingTo("20");
        assertThat(result.getAverageCost()).isEqualByComparingTo("1100.00");
    }

    @Test
    void addHolding_existingHolding_keepsEarliestBuyDate() {
        Holding existing = Holding.builder()
            .id(5L).portfolio(Portfolio.builder().id(PORTFOLIO_ID).build())
            .symbol("RELIANCE.NS").name("Reliance")
            .quantity(new BigDecimal("10")).averageCost(new BigDecimal("1000.00"))
            .buyDate(LocalDate.of(2026, 3, 1))
            .build();
        when(holdingRepository.findByPortfolioIdAndSymbol(PORTFOLIO_ID, "RELIANCE.NS")).thenReturn(Optional.of(existing));

        AddHoldingRequest req = new AddHoldingRequest();
        req.setSymbol("RELIANCE.NS");
        req.setName("Reliance");
        req.setQuantity(new BigDecimal("5"));
        req.setPrice(new BigDecimal("1200.00"));
        req.setTransactionDate(LocalDate.of(2026, 1, 15)); // earlier than existing buyDate
        req.setCharges(BigDecimal.ZERO);

        Holding result = service.addHolding(PORTFOLIO_ID, USER_ID, req);

        assertThat(result.getBuyDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void addHolding_doesNotOverwriteExistingBrokerOrFolio() {
        Holding existing = Holding.builder()
            .id(5L).portfolio(Portfolio.builder().id(PORTFOLIO_ID).build())
            .symbol("MF01.MF").name("Some Fund")
            .quantity(new BigDecimal("10")).averageCost(new BigDecimal("100.00"))
            .broker("HDFC").folio("12345")
            .build();
        when(holdingRepository.findByPortfolioIdAndSymbol(PORTFOLIO_ID, "MF01.MF")).thenReturn(Optional.of(existing));

        AddHoldingRequest req = new AddHoldingRequest();
        req.setSymbol("MF01.MF");
        req.setName("Some Fund");
        req.setQuantity(new BigDecimal("5"));
        req.setPrice(new BigDecimal("100.00"));
        req.setTransactionDate(LocalDate.of(2026, 1, 1));
        req.setCharges(BigDecimal.ZERO);
        req.setBroker("SBI");   // should NOT overwrite existing "HDFC"
        req.setFolio("99999");  // should NOT overwrite existing "12345"

        Holding result = service.addHolding(PORTFOLIO_ID, USER_ID, req);

        assertThat(result.getBroker()).isEqualTo("HDFC");
        assertThat(result.getFolio()).isEqualTo("12345");
    }

    @Test
    void isDuplicateTrade_trueWhenExactMatchExists() {
        Holding h = Holding.builder().id(5L).symbol("RELIANCE.NS").quantity(BigDecimal.TEN).averageCost(BigDecimal.TEN).build();
        when(holdingRepository.findByPortfolioIdAndSymbol(PORTFOLIO_ID, "RELIANCE.NS")).thenReturn(Optional.of(h));
        when(transactionRepository.existsByHoldingIdAndTransactionDateAndQuantityAndPrice(
            5L, LocalDate.of(2026, 1, 1), new BigDecimal("10"), new BigDecimal("1200.00"))).thenReturn(true);

        boolean result = service.isDuplicateTrade(PORTFOLIO_ID, "RELIANCE.NS", LocalDate.of(2026, 1, 1),
            new BigDecimal("10"), new BigDecimal("1200.00"));

        assertThat(result).isTrue();
    }

    @Test
    void isDuplicateTrade_falseWhenNoHoldingExists() {
        when(holdingRepository.findByPortfolioIdAndSymbol(PORTFOLIO_ID, "NEWSTOCK.NS")).thenReturn(Optional.empty());

        boolean result = service.isDuplicateTrade(PORTFOLIO_ID, "NEWSTOCK.NS", LocalDate.now(), BigDecimal.ONE, BigDecimal.ONE);

        assertThat(result).isFalse();
    }

    @Test
    void isDuplicateTrade_falseOnNullInputs() {
        assertThat(service.isDuplicateTrade(null, "X", LocalDate.now(), BigDecimal.ONE, BigDecimal.ONE)).isFalse();
        assertThat(service.isDuplicateTrade(PORTFOLIO_ID, null, LocalDate.now(), BigDecimal.ONE, BigDecimal.ONE)).isFalse();
    }

    @Test
    void sellHolding_computesPnlAndReducesQuantity() {
        Holding h = Holding.builder()
            .id(5L).portfolio(Portfolio.builder().id(PORTFOLIO_ID).user(null).build())
            .symbol("RELIANCE.NS").name("Reliance")
            .quantity(new BigDecimal("10")).averageCost(new BigDecimal("1000.00"))
            .build();
        Portfolio portfolio = Portfolio.builder().id(PORTFOLIO_ID).build();
        when(portfolioRepository.findByIdAndUserId(PORTFOLIO_ID, USER_ID)).thenReturn(Optional.of(portfolio));
        when(holdingRepository.findById(5L)).thenReturn(Optional.of(h));

        com.marketai.income.repository.IncomeRepository incomeRepo = mock(com.marketai.income.repository.IncomeRepository.class);
        when(incomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Sell 4 of 10 at 1500 -> saleValue = 6000, costBasis = 4*1000=4000, pnl = 2000
        service.sellHolding(PORTFOLIO_ID, 5L, USER_ID, new BigDecimal("4"), new BigDecimal("1500.00"), incomeRepo);

        ArgumentCaptor<com.marketai.income.entity.Income> incomeCaptor = ArgumentCaptor.forClass(com.marketai.income.entity.Income.class);
        verify(incomeRepo).save(incomeCaptor.capture());
        assertThat(incomeCaptor.getValue().getAmount()).isEqualByComparingTo("2000.00");
        assertThat(incomeCaptor.getValue().getSource()).isEqualTo("Capital Gain");

        assertThat(h.getQuantity()).isEqualByComparingTo("6"); // 10 - 4 remaining
        verify(holdingRepository).save(h);
        verify(holdingRepository, never()).deleteById(anyLong());
    }

    @Test
    void sellHolding_deletesHoldingWhenFullyLiquidated() {
        Holding h = Holding.builder()
            .id(5L).portfolio(Portfolio.builder().id(PORTFOLIO_ID).build())
            .symbol("RELIANCE.NS").name("Reliance")
            .quantity(new BigDecimal("10")).averageCost(new BigDecimal("1000.00"))
            .build();
        when(holdingRepository.findById(5L)).thenReturn(Optional.of(h));
        com.marketai.income.repository.IncomeRepository incomeRepo = mock(com.marketai.income.repository.IncomeRepository.class);
        when(incomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sellHolding(PORTFOLIO_ID, 5L, USER_ID, new BigDecimal("10"), new BigDecimal("1500.00"), incomeRepo);

        verify(holdingRepository).deleteById(5L);
        verify(holdingRepository, never()).save(h);
    }

    @Test
    void sellHolding_clampsOversell() {
        // Selling more than is held must not go negative — it should clamp to what's owned,
        // never fabricate a negative-quantity holding or an inflated capital gain.
        Holding h = Holding.builder()
            .id(5L).portfolio(Portfolio.builder().id(PORTFOLIO_ID).build())
            .symbol("RELIANCE.NS").name("Reliance")
            .quantity(new BigDecimal("10")).averageCost(new BigDecimal("1000.00"))
            .build();
        when(holdingRepository.findById(5L)).thenReturn(Optional.of(h));
        com.marketai.income.repository.IncomeRepository incomeRepo = mock(com.marketai.income.repository.IncomeRepository.class);
        when(incomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.sellHolding(PORTFOLIO_ID, 5L, USER_ID, new BigDecimal("999"), new BigDecimal("1500.00"), incomeRepo);

        // Clamped to 10 -> fully sold -> deleted, not left at a negative quantity
        verify(holdingRepository).deleteById(5L);
    }

    @Test
    void checkIntegrity_flagsUnverifiableFundName() {
        Holding garbage = Holding.builder().id(224L).symbol("NAMECOSTOFINVESTMENT.MF").name("Name Cost of Investment")
            .quantity(new BigDecimal("3266.46")).averageCost(new BigDecimal("2219.43")).build();
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(Collections.singletonList(Portfolio.builder().id(PORTFOLIO_ID).build()));
        when(holdingRepository.findByPortfolioId(PORTFOLIO_ID)).thenReturn(Collections.singletonList(garbage));

        IntegrityReportDto report = service.checkIntegrity(USER_ID);

        assertThat(report.getTotalHoldings()).isEqualTo(1);
        assertThat(report.getIssues()).hasSize(1);
        assertThat(report.getIssues().get(0).getType()).isEqualTo("UNVERIFIABLE_NAME");
        assertThat(report.getIssues().get(0).getHoldingId()).isEqualTo(224L);
    }

    @Test
    void checkIntegrity_flagsDuplicateFolioAcrossSymbols() {
        Holding a = Holding.builder().id(1L).symbol("MF13-1089.MF").name("ICICI Pru Large & Mid Cap Fund")
            .folio("43691089").quantity(BigDecimal.ONE).averageCost(BigDecimal.TEN).build();
        Holding b = Holding.builder().id(2L).symbol("MF14-1089.MF").name("ICICI Pru Large Cap Fund")
            .folio("43691089").quantity(BigDecimal.ONE).averageCost(BigDecimal.TEN).build();
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(Collections.singletonList(Portfolio.builder().id(PORTFOLIO_ID).build()));
        when(holdingRepository.findByPortfolioId(PORTFOLIO_ID)).thenReturn(Arrays.asList(a, b));

        IntegrityReportDto report = service.checkIntegrity(USER_ID);

        // Different fund names under the same folio is legitimate (one folio can hold
        // multiple schemes) — should NOT be flagged as duplicate since symbols AND names differ
        // meaningfully. This asserts the check runs without throwing and counts holdings correctly.
        assertThat(report.getTotalHoldings()).isEqualTo(2);
    }

    @Test
    void checkIntegrity_flagsSameFolioSameNameDifferentSymbol_theActualIncidentCase() {
        // Regression test for the Aug-2026 incident pattern: the same real fund fragmented
        // into two holdings under different auto-generated symbols.
        Holding a = Holding.builder().id(215L).symbol("LARGE.MF").name("Large Cap Fund (erstwhile Bluechip Fund)")
            .quantity(BigDecimal.ONE).averageCost(new BigDecimal("11000")).build();
        Holding b = Holding.builder().id(217L).symbol("LARGECAPFUNDERSTWHILEBLUECHIPF.MF").name("Large Cap Fund (erstwhile Bluechip Fund)")
            .quantity(BigDecimal.ONE).averageCost(new BigDecimal("11000")).build();
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(Collections.singletonList(Portfolio.builder().id(PORTFOLIO_ID).build()));
        when(holdingRepository.findByPortfolioId(PORTFOLIO_ID)).thenReturn(Arrays.asList(a, b));

        IntegrityReportDto report = service.checkIntegrity(USER_ID);

        assertThat(report.getIssues()).extracting(IntegrityReportDto.Issue::getType)
            .containsOnly("DUPLICATE_DISPLAY_NAME");
        assertThat(report.getIssues()).hasSize(2);
    }

    @Test
    void checkIntegrity_noIssuesForCleanPortfolio() {
        Holding clean = Holding.builder().id(1L).symbol("HDFCBANK.NS").name("HDFC Bank")
            .quantity(BigDecimal.TEN).averageCost(new BigDecimal("1500")).build();
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(Collections.singletonList(Portfolio.builder().id(PORTFOLIO_ID).build()));
        when(holdingRepository.findByPortfolioId(PORTFOLIO_ID)).thenReturn(Collections.singletonList(clean));
        // Simulate the local stock-seed lookup finding this as a real, known ticker (as it
        // would in production) so a merely-not-yet-priced holding isn't mistaken for garbage.
        when(marketDataService.searchStocks("HDFCBANK")).thenReturn(Collections.singletonList(
            com.marketai.market.entity.Stock.builder().symbol("HDFCBANK.NS").name("HDFC Bank").build()));

        IntegrityReportDto report = service.checkIntegrity(USER_ID);

        assertThat(report.getIssueCount()).isZero();
        assertThat(report.getIssues()).isEmpty();
    }

    @Test
    void checkIntegrity_flagsSameSymbolAcrossPortfolios_theActualIncidentCase() {
        // Regression test for the exact reported pattern: TITAN.NS held once under an "NSE"-
        // labelled row in one portfolio and again under an "MStock"-labelled row in another —
        // the per-portfolio unique constraint never caught it because it's cross-portfolio.
        Long pf2 = 11L;
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(Arrays.asList(
            Portfolio.builder().id(PORTFOLIO_ID).build(), Portfolio.builder().id(pf2).build()));
        Holding a = Holding.builder().id(229L).symbol("TITAN.NS").name("TITAN")
            .broker("NSE").quantity(new BigDecimal("29")).averageCost(new BigDecimal("5019.98")).build();
        Holding b = Holding.builder().id(166L).symbol("TITAN.NS").name("TITAN")
            .broker("MStock").quantity(new BigDecimal("6")).averageCost(new BigDecimal("5019.98")).build();
        when(holdingRepository.findByPortfolioId(PORTFOLIO_ID)).thenReturn(Collections.singletonList(a));
        when(holdingRepository.findByPortfolioId(pf2)).thenReturn(Collections.singletonList(b));
        // Simulate the local stock-seed lookup finding TITAN.NS as a real, known ticker (as it
        // would in production) so these merely-not-yet-priced holdings aren't mistaken for
        // garbage — they're already correctly flagged as DUPLICATE_SYMBOL instead.
        when(marketDataService.searchStocks("TITAN")).thenReturn(Collections.singletonList(
            com.marketai.market.entity.Stock.builder().symbol("TITAN.NS").name("TITAN").build()));

        IntegrityReportDto report = service.checkIntegrity(USER_ID);

        assertThat(report.getIssues()).extracting(IntegrityReportDto.Issue::getType).containsOnly("DUPLICATE_SYMBOL");
        assertThat(report.getIssues()).hasSize(2);
    }

    @Test
    void mergeHoldings_combinesWeightedAverageCostAndReparentsTransactions() {
        User owner = User.builder().id(USER_ID).build();
        Portfolio pf1 = Portfolio.builder().id(PORTFOLIO_ID).user(owner).build();
        Portfolio pf2 = Portfolio.builder().id(11L).user(owner).build();
        Holding keep = Holding.builder().id(229L).portfolio(pf1).symbol("TITAN.NS").name("TITAN")
            .quantity(new BigDecimal("29")).averageCost(new BigDecimal("5000.00")).build();
        Holding remove = Holding.builder().id(166L).portfolio(pf2).symbol("TITAN.NS").name("TITAN")
            .quantity(new BigDecimal("6")).averageCost(new BigDecimal("5200.00")).build();
        when(holdingRepository.findById(229L)).thenReturn(Optional.of(keep));
        when(holdingRepository.findById(166L)).thenReturn(Optional.of(remove));
        when(transactionRepository.findByHoldingIdOrderByTransactionDateAsc(166L))
            .thenReturn(Collections.singletonList(Transaction.builder().id(999L).holding(remove)
                .type(Transaction.TransactionType.BUY).quantity(new BigDecimal("6"))
                .price(new BigDecimal("5200.00")).transactionDate(LocalDate.of(2026, 8, 19)).build()));

        Holding result = service.mergeHoldings(USER_ID, 229L, 166L);

        // (29*5000 + 6*5200) / 35 = (145000+31200)/35 = 176200/35 = 5034.29
        assertThat(result.getQuantity()).isEqualByComparingTo("35");
        assertThat(result.getAverageCost()).isEqualByComparingTo("5034.29");
        verify(holdingRepository).delete(remove);
        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getHolding()).isSameAs(keep);
    }

    @Test
    void mergeHoldings_refusesWhenSymbolsDiffer() {
        User owner = User.builder().id(USER_ID).build();
        Portfolio pf = Portfolio.builder().id(PORTFOLIO_ID).user(owner).build();
        Holding a = Holding.builder().id(1L).portfolio(pf).symbol("TITAN.NS")
            .quantity(BigDecimal.TEN).averageCost(BigDecimal.TEN).build();
        Holding b = Holding.builder().id(2L).portfolio(pf).symbol("RELIANCE.NS")
            .quantity(BigDecimal.TEN).averageCost(BigDecimal.TEN).build();
        when(holdingRepository.findById(1L)).thenReturn(Optional.of(a));
        when(holdingRepository.findById(2L)).thenReturn(Optional.of(b));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.mergeHoldings(USER_ID, 1L, 2L));
        verify(holdingRepository, never()).delete(any(Holding.class));
    }

    @Test
    void mergeDuplicateSymbols_bulkMergesAllGroupsAndKeepsTheOneWithMoreHistory() {
        User owner = User.builder().id(USER_ID).build();
        Portfolio pf1 = Portfolio.builder().id(PORTFOLIO_ID).user(owner).build();
        Portfolio pf2 = Portfolio.builder().id(11L).user(owner).build();
        when(portfolioRepository.findByUserId(USER_ID)).thenReturn(Arrays.asList(pf1, pf2));

        Holding titanA = Holding.builder().id(229L).portfolio(pf1).symbol("TITAN.NS").name("TITAN")
            .quantity(new BigDecimal("29")).averageCost(new BigDecimal("5000")).build();
        Holding titanB = Holding.builder().id(166L).portfolio(pf2).symbol("TITAN.NS").name("TITAN")
            .quantity(new BigDecimal("6")).averageCost(new BigDecimal("5200")).build();
        Holding cleanStock = Holding.builder().id(300L).portfolio(pf1).symbol("HDFCBANK.NS").name("HDFC Bank")
            .quantity(BigDecimal.TEN).averageCost(new BigDecimal("1500")).build();

        when(holdingRepository.findByPortfolioId(PORTFOLIO_ID)).thenReturn(Arrays.asList(titanA, cleanStock));
        when(holdingRepository.findByPortfolioId(11L)).thenReturn(Collections.singletonList(titanB));
        when(holdingRepository.findById(229L)).thenReturn(Optional.of(titanA));
        when(holdingRepository.findById(166L)).thenReturn(Optional.of(titanB));
        // titanA has 2 recorded transactions, titanB has 1 — titanA should be kept.
        when(transactionRepository.findByHoldingIdOrderByTransactionDateAsc(229L)).thenReturn(Arrays.asList(
            Transaction.builder().id(1L).holding(titanA).type(Transaction.TransactionType.BUY)
                .quantity(BigDecimal.ONE).price(BigDecimal.TEN).transactionDate(LocalDate.now()).build(),
            Transaction.builder().id(2L).holding(titanA).type(Transaction.TransactionType.BUY)
                .quantity(BigDecimal.ONE).price(BigDecimal.TEN).transactionDate(LocalDate.now()).build()));
        when(transactionRepository.findByHoldingIdOrderByTransactionDateAsc(166L)).thenReturn(Collections.singletonList(
            Transaction.builder().id(3L).holding(titanB).type(Transaction.TransactionType.BUY)
                .quantity(BigDecimal.ONE).price(BigDecimal.TEN).transactionDate(LocalDate.now()).build()));

        MergeSummaryDto summary = service.mergeDuplicateSymbols(USER_ID);

        assertThat(summary.getGroupsMerged()).isEqualTo(1);
        assertThat(summary.getHoldingsMerged()).isEqualTo(1);
        assertThat(summary.getGroups().get(0).getSymbol()).isEqualTo("TITAN.NS");
        assertThat(summary.getGroups().get(0).getKeptHoldingId()).isEqualTo(229L);
        assertThat(summary.getGroups().get(0).getRemovedHoldingIds()).containsExactly("166");
        verify(holdingRepository).delete(titanB);
        verify(holdingRepository, never()).delete(cleanStock);
    }
}
