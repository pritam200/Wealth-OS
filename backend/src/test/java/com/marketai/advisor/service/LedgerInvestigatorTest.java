package com.marketai.advisor.service;

import com.marketai.advisor.dto.AdvisorEvidence;
import com.marketai.common.ledger.Provenance;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.gmail.entity.ProcessedEmail;
import com.marketai.gmail.repository.ProcessedEmailRepository;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.investmentplan.service.PlannedInvestmentService;
import com.marketai.mf.entity.MfNavHistory;
import com.marketai.mf.repository.MfNavHistoryRepository;
import com.marketai.networth.entity.NetWorthSnapshot;
import com.marketai.networth.service.NetWorthService;
import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.repository.TransactionRepository;
import com.marketai.recommendation.dto.PortfolioContext;
import com.marketai.recommendation.service.PortfolioContextService;
import com.marketai.reconciliation.service.DataAuditService;
import com.marketai.reconciliation.service.ReconciliationIssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Every figure in an investigation answer comes from the ledger, and the records behind it are listed. */
class LedgerInvestigatorTest {

    private static final Long USER = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private NetWorthService netWorth;
    private PortfolioContextService context;
    private IncomeRepository incomes;
    private ExpenseRepository expenses;
    private TransactionRepository txns;
    private MfNavHistoryRepository navs;
    private DataAuditService audit;
    private ProcessedEmailRepository emails;
    private ReconciliationIssueService issues;
    private PlannedInvestmentService plans;
    private LedgerInvestigator investigator;

    @BeforeEach
    void setUp() {
        netWorth = mock(NetWorthService.class);
        context = mock(PortfolioContextService.class);
        incomes = mock(IncomeRepository.class);
        expenses = mock(ExpenseRepository.class);
        txns = mock(TransactionRepository.class);
        navs = mock(MfNavHistoryRepository.class);
        audit = mock(DataAuditService.class);
        emails = mock(ProcessedEmailRepository.class);
        issues = mock(ReconciliationIssueService.class);
        plans = mock(PlannedInvestmentService.class);
        investigator = new LedgerInvestigator(netWorth, context, incomes, expenses, txns,
            plans, navs, audit, issues, emails);
    }

    @Test
    @DisplayName("net-worth change: compares with the last snapshot before the month and splits out income and spending")
    void netWorthChangeAgainstLastMonth() {
        when(netWorth.series(USER)).thenReturn(List.of(
            NetWorthSnapshot.builder().snapshotDate(LocalDate.of(2026, 8, 20)).netWorth(new BigDecimal("900000")).build(),
            NetWorthSnapshot.builder().snapshotDate(LocalDate.of(2026, 8, 31)).netWorth(new BigDecimal("1000000"))
                .totalAssets(new BigDecimal("1000000")).build(),
            NetWorthSnapshot.builder().snapshotDate(LocalDate.of(2026, 9, 10)).netWorth(new BigDecimal("1010000")).build()));
        when(context.build(USER)).thenReturn(PortfolioContext.builder().netWorth(new BigDecimal("1050000"))
            .totalAssets(new BigDecimal("1050000")).dataGaps(Collections.emptyList()).build());
        LocalDate from = LocalDate.of(2026, 9, 1);
        when(incomes.sumBySource(USER, from, TODAY)).thenReturn(List.of(
            new Object[]{com.marketai.income.entity.IncomeSource.SALARY, new BigDecimal("100000")},
            new Object[]{com.marketai.income.entity.IncomeSource.UNMATCHED_SALE, new BigDecimal("150000")}));
        when(expenses.sumByCategory(USER, from, TODAY)).thenReturn(List.of(
            new Object[]{ExpenseCategory.FOOD, new BigDecimal("30000")},
            new Object[]{ExpenseCategory.INVESTMENT, new BigDecimal("25000")}));
        when(txns.findForUserBetween(USER, from, TODAY)).thenReturn(List.of());

        LedgerInvestigator.Answer a = investigator.netWorthChange(USER, TODAY);

        assertThat(a.data()).containsEntry("baselineDate", LocalDate.of(2026, 8, 31));
        assertThat(a.data()).containsEntry("change", new BigDecimal("50000"));
        // Money put into investments is not spending.
        assertThat(a.data()).containsEntry("spending", new BigDecimal("30000"));
        assertThat(a.data()).containsEntry("notIncomeOrSpending", new BigDecimal("-20000"));
        assertThat(a.text()).contains("₹1000000 on 2026-08-31", "up ₹50000", "₹100000 of income", "₹30000 of spending", "−₹20000");
    }

    @Test
    @DisplayName("net-worth change with no earlier snapshot says it can't be measured instead of guessing")
    void netWorthChangeWithoutBaseline() {
        when(netWorth.series(USER)).thenReturn(List.of());
        when(context.build(USER)).thenReturn(PortfolioContext.builder().netWorth(new BigDecimal("5000")).dataGaps(List.of()).build());

        LedgerInvestigator.Answer a = investigator.netWorthChange(USER, TODAY);

        assertThat(a.text()).contains("can't be measured");
        assertThat(a.data()).doesNotContainKey("change");
    }

    @Test
    @DisplayName("fund value change: splits the move into the NAV change and units bought since, which add up")
    void fundMoveAddsUp() {
        MfNavHistory before = MfNavHistory.builder().schemeCode("100").date(LocalDate.of(2026, 9, 25)).nav(new BigDecimal("50")).build();
        MfNavHistory after = MfNavHistory.builder().schemeCode("100").date(LocalDate.of(2026, 9, 26)).nav(new BigDecimal("52")).build();
        Transaction old = Transaction.builder().type(Transaction.TransactionType.BUY).quantity(new BigDecimal("100"))
            .price(new BigDecimal("40")).transactionDate(LocalDate.of(2026, 1, 5)).build();
        Transaction sip = Transaction.builder().type(Transaction.TransactionType.BUY).quantity(new BigDecimal("10"))
            .price(new BigDecimal("52")).transactionDate(LocalDate.of(2026, 9, 26)).build();

        LedgerInvestigator.FundMove m = LedgerInvestigator.fundMove(new BigDecimal("110"), before, after, List.of(old, sip));

        assertThat(m.valueBefore()).isEqualByComparingTo("5000");      // 100 × 50
        assertThat(m.valueAfter()).isEqualByComparingTo("5720");       // 110 × 52
        assertThat(m.navEffect()).isEqualByComparingTo("200");         // 100 × 2
        assertThat(m.tradeEffect()).isEqualByComparingTo("520");       // 10 × 52
        assertThat(m.tradesSince()).containsExactly(sip);
    }

    @Test
    @DisplayName("imported records: only email-sourced rows, filtered by the provider in the sender")
    void importedFromProvider() {
        LocalDate from = TODAY.withDayOfMonth(1);
        Expense hdfc = Expense.builder().id(1L).amount(new BigDecimal("499")).merchant("Swiggy")
            .expenseDate(LocalDate.of(2026, 9, 3)).sourceEmailId("m1").build();
        Expense icici = Expense.builder().id(2L).amount(new BigDecimal("1200")).merchant("Amazon")
            .expenseDate(LocalDate.of(2026, 9, 4)).sourceEmailId("m2").build();
        Expense manual = Expense.builder().id(3L).amount(new BigDecimal("50")).merchant("HDFC tea stall")
            .expenseDate(LocalDate.of(2026, 9, 5)).build();
        when(expenses.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(USER, from, TODAY)).thenReturn(List.of(hdfc, icici, manual));
        when(incomes.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(USER, from, TODAY)).thenReturn(List.of());
        when(txns.findForUserBetween(USER, from, TODAY)).thenReturn(List.of());
        when(emails.findByUserIdAndGmailMessageIdIn(eq(USER), anyCollection())).thenReturn(List.of(
            ProcessedEmail.builder().gmailMessageId("m1").sender("alerts@hdfcbank.net").build(),
            ProcessedEmail.builder().gmailMessageId("m2").sender("credit_cards@icicibank.com").build()));

        LedgerInvestigator.Answer a = investigator.importedTransactions(USER, "HDFC", TODAY);

        assertThat(a.evidence()).extracting(AdvisorEvidence::getId).containsExactly(1L);
        assertThat(a.evidence().get(0).getSource()).isEqualTo("Email from alerts@hdfcbank.net");
        assertThat(a.text()).startsWith("1 record imported from emails matching “HDFC”").contains("₹499");
    }

    @Test
    @DisplayName("duplicates come from the data audit, with what they overstate")
    void duplicatesFromAudit() {
        when(audit.audit(USER)).thenReturn(DataAuditService.Report.builder()
            .totalRecords(40).duplicateExpenseAmount(new BigDecimal("700")).duplicateIncomeAmount(BigDecimal.ZERO)
            .findings(List.of(
                DataAuditService.Finding.builder().entity(DataAuditService.Entity.EXPENSE).id(9L)
                    .classification(DataAuditService.Classification.DUPLICATE).duplicateOf(8L)
                    .label("Zomato").reason("Same email as expense 8").amount(new BigDecimal("700")).build(),
                DataAuditService.Finding.builder().entity(DataAuditService.Entity.FIXED_DEPOSIT).id(3L)
                    .classification(DataAuditService.Classification.REQUIRES_RECONCILIATION)
                    .label("SBI FD").reason("Matured").build()))
            .build());

        LedgerInvestigator.Answer a = investigator.duplicates(USER);

        assertThat(a.text()).startsWith("1 record duplicates another").contains("overstated by ₹700");
        assertThat(a.evidence()).singleElement().satisfies(e -> {
            assertThat(e.getKind()).isEqualTo("expense");
            assertThat(e.getId()).isEqualTo(9L);
        });
    }

    @Test
    @DisplayName("holding sources: counts trades by where they came from and names holdings with no trades behind them")
    void holdingSources() {
        Holding fund = Holding.builder().id(10L).symbol("HDFCMID.MF").name("HDFC Mid Cap").quantity(BigDecimal.ONE)
            .averageCost(BigDecimal.ONE).build();
        Holding bare = Holding.builder().id(11L).symbol("AXISBLUE.MF").name("Axis Bluechip").quantity(BigDecimal.ONE)
            .averageCost(BigDecimal.ONE).build();
        Holding stock = Holding.builder().id(12L).symbol("INFY").name("Infosys").quantity(BigDecimal.ONE)
            .averageCost(BigDecimal.ONE).build();
        when(context.getAllHoldings(USER)).thenReturn(List.of(fund, bare, stock));
        Transaction fromEmail = Transaction.builder().id(100L).holding(fund).type(Transaction.TransactionType.BUY)
            .quantity(BigDecimal.ONE).price(BigDecimal.TEN).transactionDate(LocalDate.of(2026, 5, 1))
            .provenance(Provenance.builder().sourceEmailId("m9").extractionMethod(Provenance.PDF_LLM).build()).build();
        Transaction byHand = Transaction.builder().id(101L).holding(fund).type(Transaction.TransactionType.BUY)
            .quantity(BigDecimal.ONE).price(BigDecimal.TEN).transactionDate(LocalDate.of(2026, 6, 1))
            .provenance(Provenance.manual()).build();
        when(txns.findByHoldingIdInOrderByTransactionDateAscIdAsc(anyList())).thenReturn(List.of(fromEmail, byHand));
        when(emails.findByUserIdAndGmailMessageIdIn(eq(USER), anyCollection())).thenReturn(List.of(
            ProcessedEmail.builder().gmailMessageId("m9").sender("donotreply@camsonline.com").build()));

        LedgerInvestigator.Answer a = investigator.holdingSources(USER, null, LedgerInvestigator.Scope.MF);

        assertThat(a.data()).containsEntry("holdings", 2).containsEntry("fromEmail", 1).containsEntry("enteredByHand", 1)
            .containsEntry("noSourceRecorded", 0).containsEntry("holdingsWithNoTrades", List.of("Axis Bluechip"));
        assertThat(a.evidence()).extracting(AdvisorEvidence::getSource)
            .containsExactly("Email from donotreply@camsonline.com", "Entered by hand");
        verify(txns).findByHoldingIdInOrderByTransactionDateAscIdAsc(argThat((List<Long> ids) -> !ids.contains(12L)));
    }

    @Test
    @DisplayName("a stock value-change question explains that only funds have a price history")
    void valueChangeForStocks() {
        assertThat(investigator.valueChange(USER, null, LedgerInvestigator.Scope.STOCK).text()).contains("NAV history");
        verifyNoInteractions(navs);
    }

    @Test
    @DisplayName("holding sources with only hand-entered trades: no email lookup, no crash")
    void holdingSourcesWithoutEmails() {
        Holding fund = Holding.builder().id(10L).symbol("X.MF").name("X Fund").quantity(BigDecimal.ONE).averageCost(BigDecimal.ONE).build();
        when(context.getAllHoldings(USER)).thenReturn(List.of(fund));
        when(txns.findByHoldingIdInOrderByTransactionDateAscIdAsc(anyList())).thenReturn(List.of(
            Transaction.builder().id(1L).holding(fund).type(Transaction.TransactionType.BUY).quantity(BigDecimal.ONE)
                .price(BigDecimal.TEN).transactionDate(LocalDate.of(2026, 2, 1)).provenance(Provenance.manual()).build(),
            Transaction.builder().id(2L).holding(fund).type(Transaction.TransactionType.BUY).quantity(BigDecimal.ONE)
                .price(BigDecimal.TEN).transactionDate(LocalDate.of(2026, 3, 1)).build()));

        LedgerInvestigator.Answer a = investigator.holdingSources(USER, null, LedgerInvestigator.Scope.ALL);

        assertThat(a.text()).startsWith("1 holding rests on 2 trades").contains("1 entered by hand, 1 with no source recorded");
        verifyNoInteractions(emails);
    }

    @Test
    @DisplayName("missing records: a deposit the audit lists is not repeated from the maturity check")
    void missingDedupesDeposits() {
        when(audit.audit(USER)).thenReturn(DataAuditService.Report.builder().totalRecords(5).findings(List.of(
            DataAuditService.Finding.builder().entity(DataAuditService.Entity.FIXED_DEPOSIT).id(801L)
                .classification(DataAuditService.Classification.REQUIRES_RECONCILIATION).label("SBI FD").reason("Matured").build(),
            DataAuditService.Finding.builder().entity(DataAuditService.Entity.TRANSACTION).id(5L)
                .classification(DataAuditService.Classification.MISSING).label("Sell INFY").reason("No purchase").build())).build());
        when(issues.current(USER)).thenReturn(List.of(
            com.marketai.reconciliation.entity.ReconciliationIssueRecord.builder().domain("FD").type("MATURED_IDLE").referenceId(801L)
                .description("SBI FD matured").build(),
            com.marketai.reconciliation.entity.ReconciliationIssueRecord.builder().domain("INGESTION").type("TOTALS").referenceId(801L)
                .description("Statement total differs").build()));
        when(emails.countByUserIdAndStatus(USER, "FAILED")).thenReturn(0L);

        LedgerInvestigator.Answer a = investigator.missingTransactions(USER);

        assertThat(a.text()).contains("1 record implies another", "1 record needs a look", "1 open reconciliation issue", "Statement total differs")
            .doesNotContain("SBI FD matured");
        assertThat(a.evidence()).hasSize(3);
    }

    @Test
    @DisplayName("investment plan: planned, transferred, invested and what is still pending, from the plan review")
    void investmentPlanFromReview() {
        var pending = com.marketai.investmentplan.dto.PlannedInvestmentResponse.builder().destinationRef("ICICI Bank")
            .investmentType("MUTUAL_FUND").plannedAmount(new BigDecimal("20000")).remainingAmount(new BigDecimal("20000"))
            .fundedAmount(BigDecimal.ZERO).investedAmount(BigDecimal.ZERO).stage("PLANNED").build();
        var done = com.marketai.investmentplan.dto.PlannedInvestmentResponse.builder().destinationRef("HDFC Bank")
            .investmentType("RD").plannedAmount(new BigDecimal("35000")).remainingAmount(new BigDecimal("5000"))
            .fundedAmount(new BigDecimal("35000")).investedAmount(new BigDecimal("30000")).stage("FUNDED").build();
        when(plans.getReview(USER, LocalDate.of(2026, 9, 1))).thenReturn(com.marketai.investmentplan.dto.MonthlyPlanReviewResponse.builder()
            .totalPlanned(new BigDecimal("55000")).totalFunded(new BigDecimal("35000")).totalInvested(new BigDecimal("30000"))
            .totalAwaitingInvestment(new BigDecimal("5000")).totalPending(new BigDecimal("25000"))
            .pending(List.of(pending, done)).completed(List.of()).overInvested(List.of()).build());

        LedgerInvestigator.Answer a = investigator.investmentPlan(USER, TODAY);

        assertThat(a.text()).startsWith("September 2026: ₹55000 planned. ₹35000 has been transferred and ₹30000 invested; ₹5000 was transferred but is not invested yet")
            .contains("ICICI Bank (MUTUAL FUND) (₹20000 left, not started)", "HDFC Bank (RD) (₹5000 left, transferred, not all invested)");
        assertThat(a.evidence()).hasSize(2);
    }
}
