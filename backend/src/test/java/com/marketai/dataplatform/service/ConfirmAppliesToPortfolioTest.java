package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.portfolio.dto.AddHoldingRequest;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.service.PortfolioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Confirming a missing transaction books it into the legacy portfolio, through the portfolio's own checks. */
@Import(ConfirmAppliesToPortfolioTest.Portfolios.class)
class ConfirmAppliesToPortfolioTest extends DataPlatformTestSupport {

    @TestConfiguration
    static class Portfolios {
        @Bean PortfolioService portfolioService() { return Mockito.mock(PortfolioService.class); }
    }

    @Autowired PortfolioService portfolio;
    @Autowired ResolutionService resolution;

    @BeforeEach
    void stub() {
        Mockito.reset(portfolio);
        Portfolio p = Portfolio.builder().id(9L).name("Main").build();
        when(portfolio.getUserPortfolios(USER)).thenReturn(List.of(p));
        when(portfolio.resolveFundSymbol(anyLong(), any(), any())).thenAnswer(i -> i.getArgument(1));
        Holding h = Holding.builder().id(77L).symbol("X").build();
        when(portfolio.addHolding(anyLong(), anyLong(), any())).thenReturn(h);
    }

    private LedgerIssue missingAugust() {
        emailRecorder.record(USER, emailSip("2026-06-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m6", "fp6");
        emailRecorder.record(USER, emailSip("2026-09-15", ISIN_A, "HDFC Flexi Cap", "10000"), "m9", "fp9");
        ingest(SourceType.ACCOUNT_AGGREGATOR, "mock-aa", aaTxn("AA-8", "SIP", "2026-08-15", ISIN_A, "HDFC Flexi Cap", "50", "10000"));
        return issueRepo.findAll().stream().filter(i -> i.getType() == IssueType.MISSING_TRANSACTION).findFirst().orElseThrow();
    }

    @Test @DisplayName("confirming a missing SIP adds it to the portfolio once, with the ledger's figures and a provenance link")
    void confirmAddsToPortfolio() {
        LedgerIssue issue = missingAugust();
        verify(portfolio, never()).addHolding(anyLong(), anyLong(), any());

        var done = resolution.act(USER, issue.getId(), ResolutionAction.CONFIRM, "yes", null);

        ArgumentCaptor<AddHoldingRequest> req = ArgumentCaptor.forClass(AddHoldingRequest.class);
        verify(portfolio, times(1)).addHolding(eq9(), eq(USER), req.capture());
        assertThat(req.getValue().getQuantity()).isEqualByComparingTo("50");
        assertThat(req.getValue().getPrice()).isEqualByComparingTo("200");
        assertThat(req.getValue().getIsin()).isEqualTo(ISIN_A);
        assertThat(req.getValue().getSymbol()).endsWith(".MF");
        assertThat(req.getValue().getProvenance().getSourceFingerprint()).startsWith("canonical-");
        assertThat(done.getResolutionNote()).contains("yes").contains("portfolio");
        assertThat(auditRepo.findAll()).anyMatch(a -> a.getAction().equals("NOT_APPLIED_TO_PORTFOLIO") || a.getAction().equals("APPLIED_TO_PORTFOLIO"));
    }

    @Test @DisplayName("when the portfolio refuses it (e.g. a sale beyond the units held) the ledger confirmation stands and the reason is shown")
    void portfolioRefusalIsReported() {
        when(portfolio.addHolding(anyLong(), anyLong(), any())).thenThrow(new IllegalArgumentException("ISIN clash"));
        LedgerIssue issue = missingAugust();
        var done = resolution.act(USER, issue.getId(), ResolutionAction.CONFIRM, null, null);
        assertThat(done.getStatus()).isEqualTo(IssueStatus.RESOLVED);
        assertThat(done.getResolutionNote()).contains("Not added to the portfolio").contains("ISIN clash");
        assertThat(txns.findById(issue.getTransactionId()).orElseThrow().getLegacyTransactionId()).isNull();
    }

    @Test @DisplayName("rejecting never touches the portfolio")
    void rejectDoesNotApply() {
        LedgerIssue issue = missingAugust();
        resolution.act(USER, issue.getId(), ResolutionAction.REJECT, null, null);
        verify(portfolio, never()).addHolding(anyLong(), anyLong(), any());
    }

    @Test @DisplayName("an event type with no portfolio path (e.g. a transfer) is kept in the ledger and not forced into the portfolio")
    void nonTradeNotApplied() {
        var applyService = applyService(provider(portfolio), noTm());
        assertThat(applyService.apply(CanonicalTransaction.builder().id(1L).userId(USER).transactionType(TransactionType.TRANSFER)
            .status(TxnStatus.CONFIRMED).assetId(1L).quantity(BigDecimal.ONE).build()).applied()).isFalse();
    }

    private LegacyApplyService applyService(org.springframework.beans.factory.ObjectProvider<PortfolioService> ps,
                                            org.springframework.transaction.PlatformTransactionManager tm) {
        return new LegacyApplyService(ps, provider(null), provider(mock(com.marketai.portfolio.repository.TransactionRepository.class)), txns, assetRepo, tm,
            provider(null), provider(null), provider(null), provider(null), accountRepo);
    }

    private static org.springframework.transaction.PlatformTransactionManager noTm() {
        return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { }
        };
    }

    @Test @DisplayName("a confirmed stock split or bonus is booked through the portfolio's corporate-action paths")
    void splitAndBonusApplied() {
        when(portfolio.findHoldingId(anyLong(), any())).thenReturn(5L);
        when(portfolio.recordSplit(anyLong(), any(), any(), any(), any(), any())).thenReturn(new PortfolioService.CorporateActionResult(true, false, "ok"));
        when(portfolio.recordBonus(anyLong(), any(), any(), any(), any(), any(), any())).thenReturn(new PortfolioService.CorporateActionResult(true, false, "ok"));
        FinancialAsset infy = assetRepo.save(FinancialAsset.builder().assetClass(AssetClass.STOCK).assetKey("INFY").symbol("INFY").name("Infosys").build());
        var svc = applyService(provider(portfolio), noTm());
        var base = CanonicalTransaction.builder().id(11L).userId(USER).assetId(infy.getId()).status(TxnStatus.CONFIRMED)
            .transactionDate(java.time.LocalDate.of(2026, 3, 10)).confidence(1.0);
        assertThat(svc.apply(base.transactionType(TransactionType.SPLIT).ratioFrom(BigDecimal.ONE).ratioTo(new BigDecimal("5")).build()).applied()).isTrue();
        verify(portfolio).recordSplit(eq(9L), eq("INFY.NS"), any(), any(), any(), any());
        assertThat(svc.apply(base.transactionType(TransactionType.BONUS).quantity(new BigDecimal("10")).build()).applied()).isTrue();
        verify(portfolio).recordBonus(eq(9L), eq("INFY.NS"), any(), any(), any(), any(), any());
        // a split with no ratio is refused, not guessed
        assertThat(svc.apply(base.transactionType(TransactionType.SPLIT).ratioFrom(null).ratioTo(null).build()).message()).contains("ratio");
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        org.springframework.beans.factory.ObjectProvider<T> p = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        when(p.getObject()).thenReturn(value);
        return p;
    }

    private static Long eq9() { return org.mockito.ArgumentMatchers.eq(9L); }
}
