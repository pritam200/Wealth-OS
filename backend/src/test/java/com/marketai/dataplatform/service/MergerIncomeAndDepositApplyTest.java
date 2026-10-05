package com.marketai.dataplatform.service;

import com.marketai.dataplatform.domain.*;
import com.marketai.income.entity.IncomeSource;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.tracking.dto.FdRequest;
import com.marketai.tracking.dto.FdResponse;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.service.TrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Mergers, interest, dividends and fixed deposits reach the parts of the app that own them, once, on explicit request. */
class MergerIncomeAndDepositApplyTest extends DataPlatformTestSupport {

    @org.springframework.test.context.bean.override.mockito.MockitoBean PortfolioService portfolio;
    @org.springframework.test.context.bean.override.mockito.MockitoBean TrackingService tracking;
    @Autowired ResolutionService resolution;
    @Autowired IncomeRepository incomes;
    @Autowired FixedDepositRepository fds;
    @Autowired com.marketai.auth.repository.UserRepository users;

    @BeforeEach
    void setUp() {
        Mockito.reset(portfolio, tracking);
        incomes.deleteAll();
        fds.deleteAll();
        when(portfolio.getUserPortfolios(USER)).thenReturn(List.of(Portfolio.builder().id(9L).name("Main").build()));
    }

    private FinancialAccount account(String institution, AccountType type) {
        return accountRepo.save(FinancialAccount.builder().ownerUserId(USER).accountType(type).institution(institution)
            .accountKey(institution + ":" + type).displayName(institution).build());
    }

    private CanonicalTransaction txn(FinancialAccount a, Long assetId, TransactionType type, String date) {
        return txns.save(CanonicalTransaction.builder().userId(USER).accountId(a.getId()).assetId(assetId).transactionType(type)
            .transactionDate(LocalDate.parse(date)).sourceType(SourceType.ACCOUNT_AGGREGATOR).confidence(1.0)
            .status(TxnStatus.CONFIRMED).reconciliationStatus(ReconStatus.VERIFIED).build());
    }

    @Test @DisplayName("a merger is booked through the portfolio's merger path with the surviving symbol, once")
    void mergerApplied() {
        FinancialAsset old = assetRepo.save(FinancialAsset.builder().assetClass(AssetClass.STOCK).assetKey("OLDCO").symbol("OLDCO").name("Old Co").build());
        when(portfolio.findHoldingId(anyLong(), any())).thenReturn(5L);
        when(portfolio.recordMerger(anyLong(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new PortfolioService.CorporateActionResult(true, false, "ok"));
        CanonicalTransaction t = txn(account("Zerodha", AccountType.DEMAT), old.getId(), TransactionType.MERGER, "2026-04-01");
        t.setRatioFrom(new BigDecimal("2")); t.setRatioTo(new BigDecimal("3")); t.setNewSymbol("NEWCO");
        txns.save(t);

        assertThat(resolution.addToPortfolio(USER, t.getId()).applied()).isTrue();
        verify(portfolio).recordMerger(eq(9L), eq("OLDCO.NS"), eq("NEWCO.NS"), any(), any(), any(), any());
        assertThat(resolution.addToPortfolio(USER, t.getId()).applied()).isFalse();
        verify(portfolio, times(1)).recordMerger(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test @DisplayName("a merger that does not say which company replaces the shares is refused, not guessed")
    void mergerWithoutTargetRefused() {
        FinancialAsset old = assetRepo.save(FinancialAsset.builder().assetClass(AssetClass.STOCK).assetKey("OLDCO").symbol("OLDCO").name("Old Co").build());
        CanonicalTransaction t = txn(account("Zerodha", AccountType.DEMAT), old.getId(), TransactionType.MERGER, "2026-04-01");
        t.setRatioFrom(BigDecimal.ONE); t.setRatioTo(BigDecimal.ONE); txns.save(t);
        var r = resolution.addToPortfolio(USER, t.getId());
        assertThat(r.applied()).isFalse();
        assertThat(r.message()).contains("which company");
        verify(portfolio, never()).recordMerger(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test @DisplayName("interest becomes one income entry, gross with TDS beside it, and a repeat does not add another")
    void interestBecomesIncome() {
        CanonicalTransaction t = txn(account("HDFC Bank", AccountType.BANK_SAVINGS), null, TransactionType.INTEREST, "2026-05-10");
        t.setGrossAmount(new BigDecimal("1000")); t.setTaxes(new BigDecimal("100")); t.setNetAmount(new BigDecimal("900")); txns.save(t);

        assertThat(resolution.addToPortfolio(USER, t.getId()).applied()).isTrue();
        var rows = incomes.findByUserIdOrderByIncomeDateDesc(USER);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSource()).isEqualTo(IncomeSource.INTEREST);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("1000");
        assertThat(rows.get(0).getTds()).isEqualByComparingTo("100");
        assertThat(resolution.addToPortfolio(USER, t.getId()).applied()).isFalse();
        assertThat(incomes.findByUserIdOrderByIncomeDateDesc(USER)).hasSize(1);
    }

    @Test @DisplayName("an interest credit already booked from email is linked, not duplicated")
    void interestAlreadyFromEmail() {
        incomes.save(com.marketai.income.entity.Income.builder().userId(USER).description("Interest — HDFC").amount(new BigDecimal("900"))
            .source(IncomeSource.INTEREST).incomeDate(LocalDate.parse("2026-05-10")).sourceEmailId("gm-1").build());
        CanonicalTransaction t = txn(account("HDFC Bank", AccountType.BANK_SAVINGS), null, TransactionType.INTEREST, "2026-05-10");
        t.setGrossAmount(new BigDecimal("1000")); t.setNetAmount(new BigDecimal("900")); txns.save(t);
        var r = resolution.addToPortfolio(USER, t.getId());
        assertThat(r.applied()).isTrue();
        assertThat(r.message()).contains("already");
        assertThat(incomes.findByUserIdOrderByIncomeDateDesc(USER)).hasSize(1);
    }

    @Test @DisplayName("a dividend becomes dividend income named after the company")
    void dividendBecomesIncome() {
        FinancialAsset a = assetRepo.save(FinancialAsset.builder().assetClass(AssetClass.STOCK).assetKey("INFY").symbol("INFY").name("Infosys").build());
        CanonicalTransaction t = txn(account("Zerodha", AccountType.DEMAT), a.getId(), TransactionType.DIVIDEND, "2026-06-01");
        t.setNetAmount(new BigDecimal("500")); txns.save(t);
        assertThat(resolution.addToPortfolio(USER, t.getId()).applied()).isTrue();
        var row = incomes.findByUserIdOrderByIncomeDateDesc(USER).get(0);
        assertThat(row.getSource()).isEqualTo(IncomeSource.DIVIDEND);
        assertThat(row.getDescription()).contains("Infosys");
    }

    @Test @DisplayName("an FD opened is tracked only with a reported rate and maturity; without them it is refused")
    void fdOpen() {
        FinancialAsset fd = assetRepo.save(FinancialAsset.builder().assetClass(AssetClass.FD).assetKey("FD:SBI:100000:2026-01-05").name("SBI FD").build());
        FinancialAccount acct = account("SBI", AccountType.FD_ACCOUNT);
        CanonicalTransaction bare = txn(acct, fd.getId(), TransactionType.FD_CREATION, "2026-01-05");
        bare.setGrossAmount(new BigDecimal("100000")); bare = txns.save(bare);
        var no = resolution.addToPortfolio(USER, bare.getId());
        assertThat(no.applied()).isFalse();
        assertThat(no.message()).contains("rate");

        bare.setInterestRate(new BigDecimal("7.1")); bare.setMaturityDate(LocalDate.parse("2027-01-05")); bare = txns.save(bare);
        com.marketai.auth.entity.User u = users.findById(USER).orElseGet(() -> users.save(com.marketai.auth.entity.User.builder()
            .email("u@test.in").name("U").password("x").build()));
        FdResponse resp = Mockito.mock(FdResponse.class); when(resp.getId()).thenReturn(42L);
        when(tracking.addFd(eq(u.getId()), any(FdRequest.class), any())).thenReturn(resp);
        bare.setUserId(u.getId()); bare = txns.save(bare);
        var ok = resolution.addToPortfolio(u.getId(), bare.getId());
        assertThat(ok.applied()).as(ok.message()).isTrue();
        assertThat(txns.findById(bare.getId()).orElseThrow().getPortfolioRef()).isEqualTo("fd:42");
    }

    @Test @DisplayName("an FD payout closes the single active deposit at that bank; an ambiguous one is left for the user")
    void fdClose() {
        FinancialAccount acct = account("SBI", AccountType.FD_ACCOUNT);
        com.marketai.auth.entity.User u = users.findById(USER).orElseGet(() -> users.save(com.marketai.auth.entity.User.builder().email("u2@test.in").name("U").password("x").build()));
        FixedDeposit one = fds.save(FixedDeposit.builder().user(u).bank("SBI").principal(new BigDecimal("100000")).rate(new BigDecimal("7"))
            .startDate(LocalDate.parse("2025-01-05")).maturityDate(LocalDate.parse("2026-01-05")).build());
        CanonicalTransaction t = txn(acct, null, TransactionType.FD_MATURITY, "2026-01-05");
        t.setUserId(u.getId()); t.setNetAmount(new BigDecimal("107200")); t.setGrossAmount(new BigDecimal("107200")); txns.save(t);
        assertThat(resolution.addToPortfolio(u.getId(), t.getId()).applied()).isTrue();
        verify(tracking).closeFd(eq(one.getId()), eq(u.getId()), any(), any(), eq(LocalDate.parse("2026-01-05")), any());

        // two active deposits, neither maturing near the payout: nothing is closed
        fds.deleteAll();
        fds.save(FixedDeposit.builder().user(u).bank("SBI").principal(new BigDecimal("100000")).rate(new BigDecimal("7")).maturityDate(LocalDate.parse("2027-06-01")).build());
        fds.save(FixedDeposit.builder().user(u).bank("SBI").principal(new BigDecimal("50000")).rate(new BigDecimal("7")).maturityDate(LocalDate.parse("2028-06-01")).build());
        CanonicalTransaction t2 = txn(acct, null, TransactionType.FD_MATURITY, "2026-02-01");
        t2.setUserId(u.getId()); t2.setNetAmount(new BigDecimal("107200")); txns.save(t2);
        var r = resolution.addToPortfolio(u.getId(), t2.getId());
        assertThat(r.applied()).isFalse();
        verify(tracking, times(1)).closeFd(anyLong(), anyLong(), any(), any(), any(), any());
    }
}
