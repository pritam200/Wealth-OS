package com.marketai.gmail.service;

import com.marketai.ai.review.entity.EmailReviewItem;
import com.marketai.ai.review.entity.ReviewStatus;
import com.marketai.ai.review.repository.EmailReviewItemRepository;
import com.marketai.ai.review.service.EmailReviewService;
import com.marketai.gmail.entity.PendingPdf;
import com.marketai.gmail.ledger.EmailFinancialEvent;
import com.marketai.gmail.ledger.EmailFinancialEventRepository;
import com.marketai.gmail.ledger.EventState;
import com.marketai.gmail.ledger.FinancialEventLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * An exchange's trade alert held as a locked PDF kept its earlier review questions forever: every
 * retry stopped at the password, before the rule that settles it from its subject.
 */
class ExchangeAlertSettleTest {

    private static final Long USER_ID = 2L;
    private static final String MSG = "msg-nse";

    private static PendingPdf pdf(String sender, String subject) {
        PendingPdf p = new PendingPdf();
        p.setSender(sender);
        p.setSubject(subject);
        return p;
    }

    private static EmailReviewItem item(long id, int index) {
        return EmailReviewItem.builder().id(id).userId(USER_ID).gmailMessageId(MSG).itemIndex(index)
            .status(ReviewStatus.PENDING).build();
    }

    @Test
    @DisplayName("an NSE or BSE 'trades executed' PDF is recognised from its sender and subject; other NSE mail is not")
    void recognisesExchangeAlerts() {
        assertThat(PdfImportService.isExchangeAlert(pdf("NSE <nse-direct@nse.co.in>", "Trades executed at NSE"))).isTrue();
        assertThat(PdfImportService.isExchangeAlert(pdf("BSE <alerts@bseindia.com>", "Trades Executed on 13-Oct-2026"))).isTrue();
        assertThat(PdfImportService.isExchangeAlert(pdf("NSE <nse-direct@nse.co.in>", "Funds and securities balance"))).isFalse();
        assertThat(PdfImportService.isExchangeAlert(pdf("m.Stock <info@mstock.com>", "Trades executed"))).isFalse();
        assertThat(PdfImportService.isExchangeAlert(pdf(null, null))).isFalse();
    }

    @Test
    @DisplayName("settling one attachment clears only its pending items, not the body's or another attachment's")
    void clearsOnlyThatAttachmentsItems() {
        EmailReviewItemRepository repo = mock(EmailReviewItemRepository.class);
        EmailReviewService service = new EmailReviewService(repo, mock(ParsedEmailImporter.class), mock(FinancialEventLedger.class));
        int base = EmailLLMParserService.attachmentItemIndexBase("trades.pdf");
        EmailReviewItem first = item(1, base);
        EmailReviewItem second = item(2, base + 7);
        EmailReviewItem body = item(3, 0);
        EmailReviewItem other = item(4, base + 1000);
        when(repo.findByUserIdAndGmailMessageIdAndStatus(USER_ID, MSG, ReviewStatus.PENDING))
            .thenReturn(List.of(first, second, body, other));

        int cleared = service.clearAttachmentItems(USER_ID, MSG, base - 1, base + 999);

        assertThat(cleared).isEqualTo(2);
        verify(repo).deleteAll(List.of(first, second));
    }

    @Test
    @DisplayName("the attachment's open events are closed by the rule; one a person decided is left alone")
    void closesOpenEventsKeepsDecided() {
        EmailFinancialEventRepository repo = mock(EmailFinancialEventRepository.class);
        FinancialEventLedger ledger = new FinancialEventLedger(repo);
        int base = EmailLLMParserService.attachmentItemIndexBase("trades.pdf");
        EmailFinancialEvent open = EmailFinancialEvent.builder().itemIndex(base).state(EventState.REQUIRES_REVIEW).build();
        EmailFinancialEvent decided = EmailFinancialEvent.builder().itemIndex(base + 1).state(EventState.REQUIRES_REVIEW)
            .resolvedBy(EmailFinancialEvent.BY_USER).build();
        EmailFinancialEvent body = EmailFinancialEvent.builder().itemIndex(0).state(EventState.REQUIRES_REVIEW).build();
        when(repo.findByUserIdAndGmailMessageIdOrderByIdAsc(USER_ID, MSG)).thenReturn(List.of(open, decided, body));

        ledger.resolveItemRange(USER_ID, MSG, base - 1, base + 999, EmailLLMParserService.EXCHANGE_ALERT_REASON);

        ArgumentCaptor<EmailFinancialEvent> saved = ArgumentCaptor.forClass(EmailFinancialEvent.class);
        verify(repo, times(1)).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(open);
        assertThat(open.getState()).isEqualTo(EventState.RESOLVED);
        assertThat(open.getReason()).isEqualTo(EmailLLMParserService.EXCHANGE_ALERT_REASON);
        assertThat(decided.getState()).isEqualTo(EventState.REQUIRES_REVIEW);
        assertThat(body.getState()).isEqualTo(EventState.REQUIRES_REVIEW);
        verify(repo, never()).save(body);
    }
}
