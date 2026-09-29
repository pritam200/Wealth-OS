package com.marketai.gmail.service;

import com.marketai.auth.entity.User;
import com.marketai.document.identity.ReferenceHarvester;
import com.marketai.expense.entity.Expense;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.expense.repository.ExpenseRepository;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import com.marketai.income.entity.Income;
import com.marketai.income.entity.IncomeSource;
import com.marketai.income.repository.IncomeRepository;
import com.marketai.portfolio.dto.AddHoldingRequest;
import com.marketai.portfolio.entity.Portfolio;
import com.marketai.portfolio.service.PortfolioService;
import com.marketai.tracking.entity.FixedDeposit;
import com.marketai.tracking.repository.FixedDepositRepository;
import com.marketai.tracking.repository.RecurringDepositRepository;
import com.marketai.tracking.service.TrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Phase 5 event types: each is recorded exactly once, against the record it belongs to, or held
 *  for review with the reason. */
class NewEventTypeImportTest {

    private static final Long USER = 9L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 10);

    private PortfolioService portfolioService;
    private TrackingService trackingService;
    private IncomeRepository incomeRepo;
    private ExpenseRepository expenseRepo;
    private FixedDepositRepository fdRepo;
    private ParsedEmailImporter importer;

    @BeforeEach
    void setUp() {
        portfolioService = mock(PortfolioService.class);
        trackingService = mock(TrackingService.class);
        incomeRepo = mock(IncomeRepository.class);
        expenseRepo = mock(ExpenseRepository.class);
        fdRepo = mock(FixedDepositRepository.class);
        ImportedTransactionFingerprintRepository fpRepo = mock(ImportedTransactionFingerprintRepository.class);
        when(fpRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any())).thenReturn(Optional.empty());
        when(fpRepo.findFirstByUserIdAndFingerprint(any(), any())).thenReturn(Optional.empty());
        when(fpRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        TransactionMatchScorer scorer = mock(TransactionMatchScorer.class);
        when(scorer.findBestMatch(any(), any(), any())).thenReturn(Optional.empty());

        Portfolio p = new Portfolio();
        p.setId(1L);
        when(portfolioService.getUserPortfolios(USER)).thenReturn(List.of(p));
        when(portfolioService.resolveFundSymbol(any(), any(), any())).thenAnswer(i -> i.getArgument(1));

        importer = new ParsedEmailImporter(trackingService, portfolioService, incomeRepo, expenseRepo,
            mock(com.marketai.card.repository.CreditCardRepository.class),
            mock(com.marketai.card.repository.CardStatementRepository.class),
            mock(com.marketai.card.repository.CardPaymentRepository.class),
            fdRepo, mock(RecurringDepositRepository.class),
            new TransactionFingerprinter(), fpRepo, new ReferenceHarvester(),
            scorer, mock(com.marketai.rent.service.RentService.class));
    }

    private ParsedEmailImporter.ImportOutcome run(ParsedEmail pe) throws Exception {
        return importer.importParsedEmail(USER, new User(), pe, "msg-1", null);
    }

    private static Expense purchase(long id, String merchant, String amount, LocalDate date) {
        return Expense.builder().id(id).userId(USER).merchant(merchant).description(merchant)
            .amount(new BigDecimal(amount)).category(ExpenseCategory.SHOPPING).expenseDate(date).build();
    }

    // ── Refunds ──

    @Test
    @DisplayName("a refund is a negative expense linked to the purchase it reverses, in that purchase's category")
    void refundLinksToPurchase() throws Exception {
        when(expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(purchase(5L, "Amazon", "2499", DAY.minusDays(12))));
        when(expenseRepo.findByRefundOfExpenseId(5L)).thenReturn(List.of());

        var outcome = run(ParsedEmail.builder().type(ParsedEmail.Type.REFUND).merchant("AMAZON")
            .amount(new BigDecimal("2499")).tradeDate(DAY).build());

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepo).save(saved.capture());
        assertThat(outcome).isEqualTo(ParsedEmailImporter.ImportOutcome.IMPORTED);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("-2499");
        assertThat(saved.getValue().getRefundOfExpenseId()).isEqualTo(5L);
        assertThat(saved.getValue().getCategory()).isEqualTo(ExpenseCategory.SHOPPING);
        verify(incomeRepo, never()).save(any());
    }

    @Test
    @DisplayName("a refund larger than what is left of the purchase, or with no purchase, goes to review")
    void refundWithoutPurchaseIsReviewed() {
        Expense p = purchase(5L, "Amazon", "2499", DAY.minusDays(12));
        when(expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(p));
        // ₹2,000 of it was already refunded.
        when(expenseRepo.findByRefundOfExpenseId(5L)).thenReturn(List.of(Expense.builder()
            .amount(new BigDecimal("-2000")).refundOfExpenseId(5L).build()));

        assertThatThrownBy(() -> run(ParsedEmail.builder().type(ParsedEmail.Type.REFUND).merchant("Amazon")
            .amount(new BigDecimal("1000")).tradeDate(DAY).build()))
            .isInstanceOf(ImportRejectedException.class)
            .hasMessageContaining("matches no purchase");
        verify(expenseRepo, never()).save(any());
    }

    // ── Own-account transfers ──

    @Test
    @DisplayName("the outgoing leg of an own-account transfer is an account transfer, not spending")
    void outgoingTransferIsNotSpend() throws Exception {
        run(ParsedEmail.builder().type(ParsedEmail.Type.OWN_TRANSFER).incoming(false)
            .amount(new BigDecimal("50000")).tradeDate(DAY).merchant("Self").build());

        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepo).save(saved.capture());
        assertThat(saved.getValue().getCategory()).isEqualTo(ExpenseCategory.ACCOUNT_TRANSFER);
    }

    @Test
    @DisplayName("the incoming leg pairs with the outgoing one instead of being booked as income")
    void incomingTransferPairs() throws Exception {
        Expense out = Expense.builder().id(3L).userId(USER).amount(new BigDecimal("50000"))
            .category(ExpenseCategory.ACCOUNT_TRANSFER).expenseDate(DAY.minusDays(1)).build();
        when(expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of(out));

        var outcome = run(ParsedEmail.builder().type(ParsedEmail.Type.OWN_TRANSFER).incoming(true)
            .amount(new BigDecimal("50000")).tradeDate(DAY).build());

        assertThat(outcome).isEqualTo(ParsedEmailImporter.ImportOutcome.DUPLICATE);
        assertThat(out.getNote()).contains("Arrived " + DAY);
        verify(incomeRepo, never()).save(any());
    }

    @Test
    @DisplayName("an incoming own transfer with no outgoing leg is reported, not booked as income")
    void unpairedIncomingTransferIsReviewed() {
        when(expenseRepo.findByUserIdAndExpenseDateBetweenOrderByExpenseDateDesc(eq(USER), any(), any()))
            .thenReturn(List.of());
        assertThatThrownBy(() -> run(ParsedEmail.builder().type(ParsedEmail.Type.OWN_TRANSFER).incoming(true)
            .amount(new BigDecimal("50000")).tradeDate(DAY).build()))
            .isInstanceOf(ImportRejectedException.class).hasMessageContaining("not income");
        verify(incomeRepo, never()).save(any());
    }

    // ── Deposits ──

    private static FixedDeposit fd(long id, String bank, String principal, String status) {
        FixedDeposit d = new FixedDeposit();
        d.setId(id);
        d.setBank(bank);
        d.setPrincipal(new BigDecimal(principal));
        d.setStatus(status);
        return d;
    }

    @Test
    @DisplayName("a maturity payout closes the one open FD at that bank with the bank's amount, TDS and date")
    void maturityClosesTheDeposit() throws Exception {
        when(fdRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(fd(7L, "HDFC Bank", "100000", "ACTIVE")));

        run(ParsedEmail.builder().type(ParsedEmail.Type.DEPOSIT_CLOSE).instrumentKind("FD").bank("HDFC")
            .amount(new BigDecimal("106500")).tds(new BigDecimal("750")).tradeDate(DAY).build());

        verify(trackingService).closeFd(eq(7L), eq(USER), argThat(a -> a.compareTo(new BigDecimal("106500")) == 0),
            argThat(t -> t.compareTo(new BigDecimal("750")) == 0), eq(DAY), eq("msg-1"));
    }

    @Test
    @DisplayName("two open FDs at the bank and no principal to tell them apart: review, not a guess")
    void ambiguousMaturityIsReviewed() {
        when(fdRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(
            fd(7L, "HDFC Bank", "100000", "ACTIVE"), fd(8L, "HDFC Bank", "100000", "ACTIVE")));

        assertThatThrownBy(() -> run(ParsedEmail.builder().type(ParsedEmail.Type.DEPOSIT_CLOSE).instrumentKind("FD")
            .bank("HDFC Bank").amount(new BigDecimal("106500")).tradeDate(DAY).build()))
            .isInstanceOf(ImportRejectedException.class).hasMessageContaining("2 open fixed deposits");
        verify(trackingService, never()).closeFd(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a payout for an FD already closed on those figures is a duplicate")
    void alreadyClosedIsDuplicate() throws Exception {
        FixedDeposit closed = fd(7L, "HDFC Bank", "100000", "CLOSED");
        closed.setMaturityAmount(new BigDecimal("106500"));
        when(fdRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(closed));

        var outcome = run(ParsedEmail.builder().type(ParsedEmail.Type.DEPOSIT_CLOSE).instrumentKind("FD")
            .bank("HDFC Bank").amount(new BigDecimal("106500")).tradeDate(DAY).build());

        assertThat(outcome).isEqualTo(ParsedEmailImporter.ImportOutcome.DUPLICATE);
        verify(trackingService, never()).closeFd(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("two identical FDs at one bank: the second's payout closes the second, not 'already recorded' by the first")
    void identicalSecondDepositIsClosed() throws Exception {
        FixedDeposit first = fd(7L, "HDFC Bank", "100000", "CLOSED");
        first.setMaturityAmount(new BigDecimal("106500"));
        first.setClosedDate(DAY);
        when(fdRepo.findByUserIdOrderByCreatedAtDesc(USER)).thenReturn(List.of(first, fd(8L, "HDFC Bank", "100000", "ACTIVE")));

        run(ParsedEmail.builder().type(ParsedEmail.Type.DEPOSIT_CLOSE).instrumentKind("FD")
            .bank("HDFC Bank").amount(new BigDecimal("106500")).tradeDate(DAY).build());

        verify(trackingService).closeFd(eq(8L), eq(USER), any(), any(), eq(DAY), eq("msg-1"));
    }

    @Test
    @DisplayName("a TDS-only advice attaches only to interest from the same bank")
    void tdsAdviceNeedsTheSameBank() {
        Income otherBank = Income.builder().id(1L).amount(new BigDecimal("5000")).tds(new BigDecimal("500"))
            .source(IncomeSource.INTEREST).payer("SBI").description("FD interest — SBI").incomeDate(DAY).build();
        when(incomeRepo.findByUserIdAndSourceAndIncomeDateBetweenOrderByIncomeDateDesc(eq(USER), eq(IncomeSource.INTEREST), any(), any()))
            .thenReturn(List.of(otherBank));

        assertThatThrownBy(() -> run(ParsedEmail.builder().type(ParsedEmail.Type.DEPOSIT_INTEREST).instrumentKind("FD")
            .bank("ICICI Bank").tds(new BigDecimal("500")).tradeDate(DAY).build()))
            .isInstanceOf(ImportRejectedException.class).hasMessageContaining("isn't on record yet");
    }

    @Test
    @DisplayName("deposit interest is booked gross with the TDS beside it")
    void depositInterestIsGross() throws Exception {
        when(incomeRepo.findByUserIdAndIncomeDateBetweenOrderByIncomeDateDesc(eq(USER), any(), any())).thenReturn(List.of());

        run(ParsedEmail.builder().type(ParsedEmail.Type.DEPOSIT_INTEREST).instrumentKind("FD").bank("SBI")
            .amount(new BigDecimal("4500")).tds(new BigDecimal("500")).tradeDate(DAY).build());

        ArgumentCaptor<Income> saved = ArgumentCaptor.forClass(Income.class);
        verify(incomeRepo).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("5000");
        assertThat(saved.getValue().getTds()).isEqualByComparingTo("500");
        assertThat(saved.getValue().getSource()).isEqualTo(IncomeSource.INTEREST);
    }

    // ── Corporate actions and trades ──

    @Test
    @DisplayName("a split is booked through the ledger; a stock that isn't held goes to review")
    void splitRoutesToLedger() throws Exception {
        when(portfolioService.recordSplit(eq(1L), eq("TCS.NS"), any(), any(), eq(DAY), any()))
            .thenReturn(new PortfolioService.CorporateActionResult(true, false, "ok"));
        ParsedEmail split = ParsedEmail.builder().type(ParsedEmail.Type.CORPORATE_ACTION).corporateAction("SPLIT")
            .symbol("TCS").ratioFrom(BigDecimal.ONE).ratioTo(new BigDecimal("2")).tradeDate(DAY).build();

        assertThat(run(split)).isEqualTo(ParsedEmailImporter.ImportOutcome.IMPORTED);

        when(portfolioService.recordSplit(eq(1L), eq("INFY.NS"), any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("INFY.NS is not held"));
        assertThatThrownBy(() -> importer.importParsedEmail(USER, new User(),
            split.toBuilder().symbol("INFY").build(), "msg-2", null))
            .isInstanceOf(ImportRejectedException.class).hasMessageContaining("not held");
    }

    @Test
    @DisplayName("a contract note's charges and trade number reach the ledger")
    void tradeCarriesChargesAndReference() throws Exception {
        when(portfolioService.isDuplicateTrade(any(), any(), any(), any(), any(), any())).thenReturn(false);

        run(ParsedEmail.builder().type(ParsedEmail.Type.TRADE_BUY).symbol("TCS").quantity(10)
            .price(new BigDecimal("3500")).charges(new BigDecimal("42.10")).tradeReference("T-991")
            .tradeDate(DAY).build());

        ArgumentCaptor<AddHoldingRequest> req = ArgumentCaptor.forClass(AddHoldingRequest.class);
        verify(portfolioService).addHolding(eq(1L), eq(USER), req.capture());
        assertThat(req.getValue().getCharges()).isEqualByComparingTo("42.10");
        assertThat(req.getValue().getProvenance().getSourceReference()).isEqualTo("T-991");
        verify(portfolioService).isDuplicateTrade(eq(1L), eq("TCS.NS"), eq(DAY), any(), any(), eq("T-991"));
    }

    @Test
    @DisplayName("two fills with different trade numbers fingerprint apart; the same fill does not")
    void tradeNumberSeparatesFingerprints() {
        TransactionFingerprinter f = new TransactionFingerprinter();
        ParsedEmail a = ParsedEmail.builder().type(ParsedEmail.Type.TRADE_BUY).symbol("TCS").quantity(10)
            .price(new BigDecimal("3500")).tradeDate(DAY).tradeReference("T-1").build();
        assertThat(f.fingerprint(a)).isNotEqualTo(f.fingerprint(a.toBuilder().tradeReference("T-2").build()));
        assertThat(f.fingerprint(a)).isEqualTo(f.fingerprint(a.toBuilder().build()));
        // Records without a trade number keep the hash they had before the field existed.
        ParsedEmail legacy = a.toBuilder().tradeReference(null).build();
        assertThat(f.fingerprint(legacy)).isEqualTo(f.fingerprint(legacy.toBuilder().build()));
    }

    // ── Mutual-fund switch and IDCW ──

    @Test
    @DisplayName("both legs of a switch carry one link group, so the redemption isn't flagged as uncredited cash")
    void switchLegsShareALinkGroup() {
        ParsedEmail leg = ParsedEmail.builder().type(ParsedEmail.Type.MF_REDEEM).linkGroup("SWITCH").build();
        assertThat(ParsedEmailImporter.linkGroup(leg, "msg-1")).isEqualTo("SWITCH:msg-1");
        assertThat(ParsedEmailImporter.linkGroup(leg.toBuilder().linkGroup(null).build(), "msg-1")).isNull();

        com.marketai.portfolio.entity.Transaction sale = com.marketai.portfolio.entity.Transaction.builder()
            .provenance(com.marketai.common.ledger.Provenance.builder().linkGroup("SWITCH:msg-1").build()).build();
        assertThat(com.marketai.reconciliation.check.UncreditedProceedsCheck.isSwitchLeg(sale)).isTrue();
    }

    @Test
    @DisplayName("a reinvested IDCW buys units and is also recorded as dividend income")
    void idcwReinvestIsDividend() throws Exception {
        when(portfolioService.isDuplicateTrade(any(), any(), any(), any(), any())).thenReturn(false);

        run(ParsedEmail.builder().type(ParsedEmail.Type.MF_SIP).fundName("HDFC Balanced Advantage Fund IDCW")
            .units(new BigDecimal("12.5")).nav(new BigDecimal("40")).amount(new BigDecimal("500"))
            .idcwReinvest(true).tradeDate(DAY).build());

        verify(portfolioService).addHolding(eq(1L), eq(USER), any());
        ArgumentCaptor<Income> income = ArgumentCaptor.forClass(Income.class);
        verify(incomeRepo).save(income.capture());
        assertThat(income.getValue().getSource()).isEqualTo(IncomeSource.DIVIDEND);
        assertThat(income.getValue().getAmount()).isEqualByComparingTo("500");
    }
}
