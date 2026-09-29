package com.marketai.ai.review.service;

import com.marketai.ai.intel.EmailIntelResult;
import com.marketai.ai.intel.EmailIntelType;
import com.marketai.ai.review.dto.ReviewDecisionRequest;
import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.auth.entity.User;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.gmail.service.ParsedEmailImporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A held-back item must book the transaction that was held back. Items used to be queued as
 * UNKNOWN, which books nothing, so approving one silently lost the transaction; and a trade,
 * MF, FD or RD could never be approved because only amount/date/party were kept.
 */
class EmailReviewServiceTest {

    private static final Long USER_ID = 7L;

    private EmailReviewItemRepository repo;
    private ParsedEmailImporter importer;
    private EmailReviewService service;
    private EmailReviewItem saved;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(EmailReviewItemRepository.class);
        importer = mock(ParsedEmailImporter.class);
        when(importer.importParsedEmail(any(), any(), any(), any())).thenReturn(ParsedEmailImporter.ImportOutcome.IMPORTED);
        service = new EmailReviewService(repo, importer, mock(com.marketai.gmail.ledger.FinancialEventLedger.class));
        user = new User();
        user.setId(USER_ID);
        when(repo.findByUserIdAndGmailMessageIdAndItemIndex(any(), any(), anyInt())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> {
            saved = i.getArgument(0);
            if (saved.getId() == null) saved.setId(1L);
            return saved;
        });
        when(repo.findByIdAndUserId(eq(1L), eq(USER_ID))).thenAnswer(i -> Optional.ofNullable(saved));
    }

    private static ParsedEmail sip() {
        return ParsedEmail.builder().type(ParsedEmail.Type.MF_SIP)
            .fundName("SBI Equity Hybrid Fund Direct Growth").folio("23655953")
            .amount(new BigDecimal("499.98")).nav(new BigDecimal("355.6801")).units(new BigDecimal("1.406"))
            .tradeDate(LocalDate.of(2026, 8, 7)).occurrenceInSource(0).build();
    }

    private void queue(ParsedEmail pe) {
        service.enqueue(USER_ID, "msg-1", 0, "SBI MF <noreply@sbimf.com>", "SIP confirmation",
            EmailIntelResult.builder().outcome(EmailIntelResult.Outcome.REVIEW_REQUIRED)
                .type(EmailIntelType.UNKNOWN).confidence(0.7).parsed(pe).reviewReason("low confidence").build());
    }

    private static ReviewDecisionRequest decision(String d) {
        ReviewDecisionRequest r = new ReviewDecisionRequest();
        r.setDecision(d);
        return r;
    }

    @Test
    @DisplayName("a held-back SIP is shown as a SIP, not UNKNOWN")
    void queuedItemShowsWhatItWouldBookAs() {
        queue(sip());
        assertThat(saved.getProposedType()).isEqualTo("MF_SIP");
        assertThat(saved.getParsedPayload()).contains("23655953");
    }

    @Test
    @DisplayName("approving books the complete held-back record — units, NAV and folio included")
    void acceptBooksTheHeldBackRecord() throws Exception {
        queue(sip());

        service.decide(user, 1L, decision("ACCEPT"));

        ArgumentCaptor<ParsedEmail> booked = ArgumentCaptor.forClass(ParsedEmail.class);
        verify(importer).importParsedEmail(eq(USER_ID), eq(user), booked.capture(), eq("msg-1"));
        assertThat(booked.getValue().getType()).isEqualTo(ParsedEmail.Type.MF_SIP);
        assertThat(booked.getValue().getUnits()).isEqualByComparingTo("1.406");
        assertThat(booked.getValue().getNav()).isEqualByComparingTo("355.6801");
        assertThat(booked.getValue().getFolio()).isEqualTo("23655953");
        assertThat(booked.getValue().isUserConfirmed()).isTrue();
    }

    @Test
    @DisplayName("an edit of the same kind corrects the record without dropping its other fields")
    void editKeepsFieldsTheFormDoesNotShow() throws Exception {
        queue(sip());
        ReviewDecisionRequest edit = decision("EDIT");
        edit.setCorrectedType("MF_SIP");
        edit.setCorrectedDate(LocalDate.of(2026, 8, 8));

        service.decide(user, 1L, edit);

        ArgumentCaptor<ParsedEmail> booked = ArgumentCaptor.forClass(ParsedEmail.class);
        verify(importer).importParsedEmail(any(), any(), booked.capture(), any());
        assertThat(booked.getValue().getTradeDate()).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(booked.getValue().getUnits()).isEqualByComparingTo("1.406");
    }

    @Test
    @DisplayName("an approved refund is booked as a refund against its purchase, never as income")
    void refundIsNotIncome() throws Exception {
        service.enqueue(USER_ID, "msg-2", 0, "bank", "Refund credited",
            EmailIntelResult.builder().outcome(EmailIntelResult.Outcome.REVIEW_REQUIRED)
                .type(EmailIntelType.REFUND).build());
        when(repo.findByIdAndUserId(eq(1L), eq(USER_ID))).thenReturn(Optional.of(saved));
        saved.setAmount(new BigDecimal("1200"));
        saved.setTransactionDate(LocalDate.of(2026, 9, 1));

        service.decide(user, 1L, decision("ACCEPT"));

        ArgumentCaptor<ParsedEmail> booked = ArgumentCaptor.forClass(ParsedEmail.class);
        verify(importer).importParsedEmail(any(), any(), booked.capture(), any());
        assertThat(booked.getValue().getType()).isEqualTo(ParsedEmail.Type.REFUND);
    }

    @Test
    @DisplayName("an older item with no stored record still books from its headline figures")
    void itemWithoutPayloadFallsBackToTheForm() throws Exception {
        EmailReviewItem legacy = EmailReviewItem.builder().id(1L).userId(USER_ID).gmailMessageId("msg-3")
            .proposedType("UPI_EXPENSE").amount(new BigDecimal("250")).transactionDate(LocalDate.of(2026, 9, 2))
            .counterparty("Swiggy").build();
        saved = legacy;

        service.decide(user, 1L, decision("ACCEPT"));

        ArgumentCaptor<ParsedEmail> booked = ArgumentCaptor.forClass(ParsedEmail.class);
        verify(importer).importParsedEmail(any(), any(), booked.capture(), eq("msg-3"));
        assertThat(booked.getValue().getType()).isEqualTo(ParsedEmail.Type.EXPENSE);
        assertThat(booked.getValue().getAmount()).isEqualByComparingTo("250");
    }

    @Test
    @DisplayName("approving something already on record says nothing was added, instead of showing it approved")
    void acceptOfAnAlreadyRecordedItemIsRefused() throws Exception {
        queue(sip());
        when(importer.importParsedEmail(any(), any(), any(), any())).thenReturn(ParsedEmailImporter.ImportOutcome.DUPLICATE);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.decide(user, 1L, decision("ACCEPT")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("already recorded");
    }

    @Test
    @DisplayName("an item of unknown type can't be approved as unknown — that would record nothing")
    void acceptAsUnknownIsRefused() {
        saved = EmailReviewItem.builder().id(1L).userId(USER_ID).gmailMessageId("msg-4").proposedType("UNKNOWN")
            .amount(new BigDecimal("250")).transactionDate(LocalDate.of(2026, 9, 2)).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.decide(user, 1L, decision("ACCEPT")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("choose a type");
    }
}
