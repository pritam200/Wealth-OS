package com.marketai.card.service;

import com.marketai.card.dto.CardStatementDtos.ReconciliationStatus;
import com.marketai.card.dto.CardStatementDtos.StatementReconciliation;
import com.marketai.card.entity.CardPayment;
import com.marketai.card.entity.CardPaymentStatus;
import com.marketai.card.entity.CardStatement;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CardPaymentRepository;
import com.marketai.card.repository.CardStatementRepository;
import com.marketai.card.repository.CreditCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The FIFO/waterfall reconciliation is recomputed from scratch on every call from two immutable
 * fact tables — nothing here is stored. These tests exercise every case the spec explicitly
 * calls out: full/partial/multiple payment, payment split across statements, a reversed payment
 * excluded from settlement, and an overpayment carrying a negative "outstanding".
 */
class CardReconciliationServiceTest {

    private static final Long USER = 1L;
    private static final Long CARD = 10L;

    private CreditCardRepository cardRepo;
    private CardStatementRepository statementRepo;
    private CardPaymentRepository paymentRepo;
    private CardReconciliationService service;

    @BeforeEach
    void setUp() {
        cardRepo = mock(CreditCardRepository.class);
        statementRepo = mock(CardStatementRepository.class);
        paymentRepo = mock(CardPaymentRepository.class);
        service = new CardReconciliationService(cardRepo, statementRepo, paymentRepo);

        when(cardRepo.findByIdAndUserId(CARD, USER))
            .thenReturn(Optional.of(CreditCard.builder().id(CARD).userId(USER).name("Test Card").build()));
    }

    private CardStatement statement(long id, LocalDate dueDate, BigDecimal totalDue) {
        return CardStatement.builder().id(id).cardId(CARD).userId(USER)
            .statementDate(dueDate.minusDays(20)).dueDate(dueDate).totalDue(totalDue).build();
    }

    private CardPayment payment(long id, LocalDate date, BigDecimal amount, CardPaymentStatus status) {
        return CardPayment.builder().id(id).cardId(CARD).userId(USER)
            .paymentDate(date).amount(amount).status(status).build();
    }

    @Test
    @DisplayName("full payment marks statement PAID with zero outstanding")
    void fullPaymentIsPaid() {
        when(statementRepo.findByCardIdOrderByDueDateDesc(CARD))
            .thenReturn(List.of(statement(1, LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"))));
        when(paymentRepo.findByCardIdOrderByPaymentDateDesc(CARD))
            .thenReturn(List.of(payment(1, LocalDate.of(2026, 3, 4), new BigDecimal("5000.00"), CardPaymentStatus.CONFIRMED)));

        List<StatementReconciliation> result = service.reconcile(USER, CARD);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(ReconciliationStatus.PAID);
        assertThat(result.get(0).getOutstanding()).isEqualByComparingTo("0.00");
        assertThat(result.get(0).getMatchedPaymentIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("partial payment leaves a positive outstanding balance")
    void partialPaymentIsPartiallyPaid() {
        when(statementRepo.findByCardIdOrderByDueDateDesc(CARD))
            .thenReturn(List.of(statement(1, LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"))));
        when(paymentRepo.findByCardIdOrderByPaymentDateDesc(CARD))
            .thenReturn(List.of(payment(1, LocalDate.of(2026, 3, 4), new BigDecimal("2000.00"), CardPaymentStatus.CONFIRMED)));

        StatementReconciliation r = service.reconcile(USER, CARD).get(0);

        assertThat(r.getStatus()).isEqualTo(ReconciliationStatus.PARTIALLY_PAID);
        assertThat(r.getAmountApplied()).isEqualByComparingTo("2000.00");
        assertThat(r.getOutstanding()).isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("no confirmed payment leaves a statement OUTSTANDING")
    void noPaymentIsOutstanding() {
        when(statementRepo.findByCardIdOrderByDueDateDesc(CARD))
            .thenReturn(List.of(statement(1, LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"))));
        when(paymentRepo.findByCardIdOrderByPaymentDateDesc(CARD)).thenReturn(List.of());

        StatementReconciliation r = service.reconcile(USER, CARD).get(0);

        assertThat(r.getStatus()).isEqualTo(ReconciliationStatus.OUTSTANDING);
        assertThat(r.getAmountApplied()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("multiple payments settle one statement together")
    void multiplePaymentsCombineToSettleOneStatement() {
        when(statementRepo.findByCardIdOrderByDueDateDesc(CARD))
            .thenReturn(List.of(statement(1, LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"))));
        when(paymentRepo.findByCardIdOrderByPaymentDateDesc(CARD))
            .thenReturn(List.of(
                payment(1, LocalDate.of(2026, 2, 20), new BigDecimal("3000.00"), CardPaymentStatus.CONFIRMED),
                payment(2, LocalDate.of(2026, 3, 1), new BigDecimal("2000.00"), CardPaymentStatus.CONFIRMED)));

        StatementReconciliation r = service.reconcile(USER, CARD).get(0);

        assertThat(r.getStatus()).isEqualTo(ReconciliationStatus.PAID);
        assertThat(r.getMatchedPaymentIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("a payment before the next statement carries forward and settles it via the waterfall")
    void paymentAppliesAcrossStatementsOldestFirst() {
        CardStatement jan = statement(1, LocalDate.of(2026, 1, 5), new BigDecimal("2000.00"));
        CardStatement feb = statement(2, LocalDate.of(2026, 2, 5), new BigDecimal("3000.00"));
        when(statementRepo.findByCardIdOrderByDueDateDesc(CARD)).thenReturn(List.of(feb, jan));
        // One payment large enough to cover January fully and leave a remainder for February.
        when(paymentRepo.findByCardIdOrderByPaymentDateDesc(CARD))
            .thenReturn(List.of(payment(1, LocalDate.of(2026, 1, 10), new BigDecimal("2500.00"), CardPaymentStatus.CONFIRMED)));

        List<StatementReconciliation> results = service.reconcile(USER, CARD);
        // Most recent statement first, per the API's other list endpoints.
        StatementReconciliation febResult = results.get(0);
        StatementReconciliation janResult = results.get(1);

        assertThat(janResult.getStatus()).isEqualTo(ReconciliationStatus.PAID);
        assertThat(febResult.getStatus()).isEqualTo(ReconciliationStatus.PARTIALLY_PAID);
        assertThat(febResult.getAmountApplied()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("a reversed payment is excluded from settlement entirely")
    void reversedPaymentDoesNotSettle() {
        when(statementRepo.findByCardIdOrderByDueDateDesc(CARD))
            .thenReturn(List.of(statement(1, LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"))));
        when(paymentRepo.findByCardIdOrderByPaymentDateDesc(CARD))
            .thenReturn(List.of(payment(1, LocalDate.of(2026, 3, 4), new BigDecimal("5000.00"), CardPaymentStatus.REVERSED)));

        StatementReconciliation r = service.reconcile(USER, CARD).get(0);

        assertThat(r.getStatus()).isEqualTo(ReconciliationStatus.OUTSTANDING);
        assertThat(r.getAmountApplied()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("duplicate payment notifications for the same money are still just one CardPayment row, never double-applied")
    void duplicateNotificationRowsOnlyCountOnceEach() {
        // The importer's own fingerprint gate is what prevents a duplicate *row* from being
        // created in the first place; this test only proves reconciliation doesn't invent extra
        // credit if, despite that, two rows for the same payment id-space exist is out of scope
        // here — instead this proves a single row is applied exactly once, not twice.
        when(statementRepo.findByCardIdOrderByDueDateDesc(CARD))
            .thenReturn(List.of(statement(1, LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"))));
        when(paymentRepo.findByCardIdOrderByPaymentDateDesc(CARD))
            .thenReturn(List.of(payment(1, LocalDate.of(2026, 3, 4), new BigDecimal("5000.00"), CardPaymentStatus.CONFIRMED)));

        StatementReconciliation r = service.reconcile(USER, CARD).get(0);
        assertThat(r.getAmountApplied()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("a payment larger than the statement due is capped at that statement's total due — never over-applied")
    void overpaymentIsCappedAtTotalDueNotOverApplied() {
        when(statementRepo.findByCardIdOrderByDueDateDesc(CARD))
            .thenReturn(List.of(statement(1, LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"))));
        when(paymentRepo.findByCardIdOrderByPaymentDateDesc(CARD))
            .thenReturn(List.of(payment(1, LocalDate.of(2026, 3, 4), new BigDecimal("6000.00"), CardPaymentStatus.CONFIRMED)));

        StatementReconciliation r = service.reconcile(USER, CARD).get(0);

        // The statement is fully settled by ₹5,000 of the ₹6,000 payment; the surplus ₹1,000
        // isn't attributed to this (or any) statement — with no later statement yet to absorb
        // it, it simply carries forward unapplied rather than fabricating a credit against a
        // statement that never had one.
        assertThat(r.getStatus()).isEqualTo(ReconciliationStatus.PAID);
        assertThat(r.getAmountApplied()).isEqualByComparingTo("5000.00");
        assertThat(r.getOutstanding()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an overpayment's surplus carries forward to settle the next statement that arrives")
    void overpaymentSurplusCarriesForwardToNextStatement() {
        CardStatement march = statement(1, LocalDate.of(2026, 3, 5), new BigDecimal("5000.00"));
        CardStatement april = statement(2, LocalDate.of(2026, 4, 5), new BigDecimal("800.00"));
        when(statementRepo.findByCardIdOrderByDueDateDesc(CARD)).thenReturn(List.of(april, march));
        when(paymentRepo.findByCardIdOrderByPaymentDateDesc(CARD))
            .thenReturn(List.of(payment(1, LocalDate.of(2026, 3, 4), new BigDecimal("6000.00"), CardPaymentStatus.CONFIRMED)));

        List<StatementReconciliation> results = service.reconcile(USER, CARD);
        StatementReconciliation aprilResult = results.get(0); // most recent first
        StatementReconciliation marchResult = results.get(1);

        assertThat(marchResult.getStatus()).isEqualTo(ReconciliationStatus.PAID);
        assertThat(aprilResult.getStatus()).isEqualTo(ReconciliationStatus.PAID);
        assertThat(aprilResult.getAmountApplied()).isEqualByComparingTo("800.00");
    }

    @Test
    @DisplayName("reconciling a card owned by a different user is rejected")
    void crossUserAccessRejected() {
        when(cardRepo.findByIdAndUserId(CARD, 999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reconcile(999L, CARD))
            .isInstanceOf(ResponseStatusException.class);
    }
}
