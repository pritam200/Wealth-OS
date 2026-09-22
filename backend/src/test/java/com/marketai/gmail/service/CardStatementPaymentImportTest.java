package com.marketai.gmail.service;

import com.marketai.auth.entity.User;
import com.marketai.card.entity.CardPayment;
import com.marketai.card.entity.CardPaymentStatus;
import com.marketai.card.entity.CardStatement;
import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CardPaymentRepository;
import com.marketai.card.repository.CardStatementRepository;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.document.identity.ReferenceHarvester;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.repository.ImportedTransactionFingerprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CARD_BILL and CARD_PAYMENT routing through the live importer: a statement/payment is booked
 * as an immutable row in its own table, never as a direct mutation only, and a payment with no
 * matching saved card is parked (cardId null) rather than dropped.
 */
class CardStatementPaymentImportTest {

    private static final Long USER = 9L;

    private CreditCardRepository cardRepo;
    private CardStatementRepository statementRepo;
    private CardPaymentRepository paymentRepo;
    private ImportedTransactionFingerprintRepository fingerprintRepo;
    private ParsedEmailImporter importer;

    @BeforeEach
    void setUp() {
        cardRepo = mock(CreditCardRepository.class);
        statementRepo = mock(CardStatementRepository.class);
        paymentRepo = mock(CardPaymentRepository.class);
        fingerprintRepo = mock(ImportedTransactionFingerprintRepository.class);

        importer = new ParsedEmailImporter(
            mock(com.marketai.tracking.service.TrackingService.class),
            mock(com.marketai.portfolio.service.PortfolioService.class),
            mock(com.marketai.income.repository.IncomeRepository.class),
            mock(com.marketai.expense.repository.ExpenseRepository.class),
            cardRepo,
            statementRepo,
            paymentRepo,
            mock(com.marketai.tracking.repository.FixedDepositRepository.class),
            mock(com.marketai.tracking.repository.RecurringDepositRepository.class),
            new TransactionFingerprinter(), fingerprintRepo, new ReferenceHarvester(),
            mock(TransactionMatchScorer.class));

        when(fingerprintRepo.existsByUserIdAndFingerprint(any(), anyString())).thenReturn(false);
        when(fingerprintRepo.findFirstByUserIdAndExternalRefAndExternalRefType(any(), any(), any()))
            .thenReturn(java.util.Optional.empty());
        when(fingerprintRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(cardRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(statementRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(paymentRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private User user() {
        User u = new User();
        u.setId(USER);
        return u;
    }

    @Test
    @DisplayName("a card bill is booked as an immutable CardStatement row and also refreshes the current-due cache")
    void cardBillBooksStatementAndUpdatesCache() throws Exception {
        CreditCard card = CreditCard.builder().id(50L).userId(USER).name("HDFC Card").lastFour("1234").build();
        when(cardRepo.findByUserIdAndLastFour(USER, "1234")).thenReturn(List.of(card));

        ParsedEmail bill = ParsedEmail.builder()
            .type(ParsedEmail.Type.CARD_BILL)
            .bank("HDFC").cardLast4("1234")
            .amount(new BigDecimal("5000.00"))
            .dueDate(LocalDate.of(2026, 3, 25))
            .statementDate(LocalDate.of(2026, 3, 5))
            .sourceDescription("HDFC card bill")
            .build();

        importer.importParsedEmail(USER, user(), bill, "msg-1");

        ArgumentCaptor<CardStatement> captor = ArgumentCaptor.forClass(CardStatement.class);
        verify(statementRepo).save(captor.capture());
        CardStatement saved = captor.getValue();
        assertThat(saved.getCardId()).isEqualTo(50L);
        assertThat(saved.getTotalDue()).isEqualByComparingTo("5000.00");
        assertThat(saved.getDueDate()).isEqualTo(LocalDate.of(2026, 3, 25));

        ArgumentCaptor<CreditCard> cardCaptor = ArgumentCaptor.forClass(CreditCard.class);
        verify(cardRepo).save(cardCaptor.capture());
        assertThat(cardCaptor.getValue().getCurrentDue()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("a payment confirmation is booked as a CardPayment row matched to the owning card")
    void cardPaymentBooksPaymentRow() throws Exception {
        CreditCard card = CreditCard.builder().id(60L).userId(USER).name("ICICI Card").lastFour("5678").build();
        when(cardRepo.findByUserIdAndLastFour(USER, "5678")).thenReturn(List.of(card));

        ParsedEmail payment = ParsedEmail.builder()
            .type(ParsedEmail.Type.CARD_PAYMENT)
            .bank("ICICI").cardLast4("5678")
            .amount(new BigDecimal("2000.00"))
            .paymentDate(LocalDate.of(2026, 3, 10))
            .paymentReference("RRN999")
            .paymentStatus("CONFIRMED")
            .sourceDescription("ICICI card payment")
            .build();

        importer.importParsedEmail(USER, user(), payment, "msg-2");

        ArgumentCaptor<CardPayment> captor = ArgumentCaptor.forClass(CardPayment.class);
        verify(paymentRepo).save(captor.capture());
        CardPayment saved = captor.getValue();
        assertThat(saved.getCardId()).isEqualTo(60L);
        assertThat(saved.getAmount()).isEqualByComparingTo("2000.00");
        assertThat(saved.getReferenceNumber()).isEqualTo("RRN999");
        assertThat(saved.getStatus()).isEqualTo(CardPaymentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("a payment confirmation with no matching saved card is still recorded, parked with a null cardId")
    void unmatchedCardPaymentIsParkedNotDropped() throws Exception {
        when(cardRepo.findByUserIdAndLastFour(USER, "0000")).thenReturn(List.of());

        ParsedEmail payment = ParsedEmail.builder()
            .type(ParsedEmail.Type.CARD_PAYMENT)
            .bank("SBI").cardLast4("0000")
            .amount(new BigDecimal("1500.00"))
            .paymentDate(LocalDate.of(2026, 3, 12))
            .paymentStatus("CONFIRMED")
            .sourceDescription("SBI card payment, unmatched")
            .build();

        importer.importParsedEmail(USER, user(), payment, "msg-3");

        ArgumentCaptor<CardPayment> captor = ArgumentCaptor.forClass(CardPayment.class);
        verify(paymentRepo).save(captor.capture());
        assertThat(captor.getValue().getCardId()).isNull();
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("a reversed payment is stored with REVERSED status, not dropped or treated as CONFIRMED")
    void reversedPaymentStoredAsReversed() throws Exception {
        when(cardRepo.findByUserIdAndLastFour(USER, "1234")).thenReturn(List.of());

        ParsedEmail payment = ParsedEmail.builder()
            .type(ParsedEmail.Type.CARD_PAYMENT)
            .bank("HDFC").cardLast4("1234")
            .amount(new BigDecimal("1000.00"))
            .paymentDate(LocalDate.of(2026, 3, 15))
            .paymentStatus("REVERSED")
            .sourceDescription("HDFC card payment reversed")
            .build();

        importer.importParsedEmail(USER, user(), payment, "msg-4");

        ArgumentCaptor<CardPayment> captor = ArgumentCaptor.forClass(CardPayment.class);
        verify(paymentRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CardPaymentStatus.REVERSED);
    }
}
