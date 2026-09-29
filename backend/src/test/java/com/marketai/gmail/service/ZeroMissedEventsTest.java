package com.marketai.gmail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmService;
import com.marketai.ai.review.service.EmailReviewService;
import com.marketai.auth.entity.User;
import com.marketai.document.classify.SenderTrustEvaluator;
import com.marketai.gmail.ledger.EmailFinancialEvent;
import com.marketai.gmail.ledger.EventState;
import com.marketai.gmail.ledger.FinancialEventLedger;
import com.marketai.market.service.MarketDataService;
import com.marketai.mf.repository.CasBalanceSnapshotRepository;
import com.marketai.mf.service.MfSchemeLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Every event the extractor reports ends in exactly one ledger state, with a reason. */
class ZeroMissedEventsTest {

    private static final Long USER_ID = 7L;
    private static final String MSG = "msg-z";

    private LlmService llm;
    private ParsedEmailImporter importer;
    private EmailReviewService review;
    private FinancialEventLedger ledger;
    private EmailLLMParserService service;
    private final User user = new User();

    @BeforeEach
    void setUp() {
        llm = mock(LlmService.class);
        importer = mock(ParsedEmailImporter.class);
        review = mock(EmailReviewService.class);
        ledger = mock(FinancialEventLedger.class);
        service = new EmailLLMParserService(llm, new LlmJsonParser(new ObjectMapper()), mock(AiAuditService.class),
            mock(MarketDataService.class), mock(MfSchemeLinkService.class), new SenderTrustEvaluator(), review, importer,
            mock(CasBalanceSnapshotRepository.class), ledger);
        ReflectionTestUtils.setField(service, "minConfidence", 0.85);
        user.setId(USER_ID);
    }

    private void modelSays(String json) {
        when(llm.complete(any(), any(), anyString())).thenReturn(LlmCompletion.builder()
            .text(json).provider("ollama").model("qwen-test").promptVersion("transaction-extraction-v2").build());
    }

    private static String debit(String merchant, int amount, String date, String evidence, String status) {
        return "{\"instrument_type\":\"BANK\",\"transaction_type\":\"DEBIT\",\"merchant\":" + (merchant == null ? "null" : "\"" + merchant + "\"")
            + ",\"transaction_date\":\"" + date + "\",\"amount_inr\":" + amount + ",\"evidence\":\"" + evidence + "\""
            + (status != null ? ",\"status\":\"" + status + "\"" : "") + "}";
    }

    private List<EmailFinancialEvent> recorded() {
        ArgumentCaptor<EmailFinancialEvent> c = ArgumentCaptor.forClass(EmailFinancialEvent.class);
        verify(ledger, atLeast(0)).record(c.capture());
        return c.getAllValues();
    }

    @Test
    @DisplayName("one email with many events: each gets its own ledger row and state, none is dropped")
    void everyEventIsRecorded() throws Exception {
        String text = "Statement\n10-01-2026 Swiggy 1,200.00 Dr\n11-01-2026 Amazon 500.00 Dr\n"
            + "12-01-2026 Payment of 900.00 to Zomato failed\n13-01-2026 Unknown POS 300.00 Dr";
        modelSays("{\"transactions\":["
            + debit("Swiggy", 1200, "2026-01-10", "10-01-2026 Swiggy 1,200.00 Dr", null) + ","
            + debit("Amazon", 500, "2026-01-11", "11-01-2026 Amazon 500.00 Dr", null) + ","
            + debit("Zomato", 900, "2026-01-12", "12-01-2026 Payment of 900.00 to Zomato failed", "FAILED") + ","
            + debit(null, 300, "2026-01-13", "13-01-2026 Unknown POS 300.00 Dr", null)
            + "],\"confidence\":0.95}");
        when(importer.importParsedEmail(any(), any(), any(), any(), any()))
            .thenReturn(ParsedEmailImporter.ImportOutcome.IMPORTED, ParsedEmailImporter.ImportOutcome.DUPLICATE,
                ParsedEmailImporter.ImportOutcome.IMPORTED);

        EmailLLMParserService.Result r = service.process(USER_ID, user, "alerts@hdfcbank.net", "Statement", text, MSG, null);

        List<EmailFinancialEvent> events = recorded();
        assertThat(events).hasSize(4);
        assertThat(events).extracting(EmailFinancialEvent::getState).containsExactly(
            EventState.IMPORTED, EventState.DUPLICATE_OF_EXISTING, EventState.RESOLVED, EventState.IMPORTED);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getReason()).isNotBlank();
            assertThat(e.getLlmModel()).isEqualTo("qwen-test");
            assertThat(e.getPromptVersion()).isEqualTo("transaction-extraction-v2");
            assertThat(e.getSourceKind()).isEqualTo(EmailFinancialEvent.BODY);
        });
        assertThat(events.get(3).getMerchant()).as("an unknown merchant is kept as UNKNOWN").isEqualTo("UNKNOWN");
        assertThat(events).extracting(EmailFinancialEvent::getEventKey).doesNotHaveDuplicates();
        assertThat(r.getResolved()).isEqualTo(1);
        assertThat(r.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.IMPORTED);
    }

    @Test
    @DisplayName("a 'failed' label the source line doesn't support goes to review, not closed unseen")
    void unsupportedFailureLabelIsReviewed() throws Exception {
        String text = "10-01-2026 Swiggy 1,200.00 Dr";
        modelSays("{\"transactions\":[" + debit("Swiggy", 1200, "2026-01-10", text, "FAILED") + "],\"confidence\":0.95}");

        service.process(USER_ID, user, "alerts@hdfcbank.net", "Alert", text, MSG, null);

        assertThat(recorded()).singleElement().extracting(EmailFinancialEvent::getState).isEqualTo(EventState.REQUIRES_REVIEW);
        verify(importer, never()).importParsedEmail(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a salary the model mislabels as a refund is reviewed, not quietly taken out of income")
    void refundLabelNeedsRefundWording() throws Exception {
        String text = "07-01-2026 | NEFT-ACME CORP SALARY | 85,000.00 | Cr";
        modelSays("{\"transactions\":[{\"instrument_type\":\"BANK\",\"transaction_type\":\"CREDIT\",\"sub_type\":\"REFUND\","
            + "\"merchant\":\"ACME CORP\",\"transaction_date\":\"2026-01-07\",\"amount_inr\":85000,\"evidence\":\"" + text + "\"}],"
            + "\"confidence\":1}");

        service.process(USER_ID, user, "alerts@hdfcbank.net", "Statement", text, MSG, null);

        EmailFinancialEvent e = recorded().get(0);
        assertThat(e.getState()).isEqualTo(EventState.REQUIRES_REVIEW);
        assertThat(e.getReason()).contains("does not say so");
        verify(importer, never()).importParsedEmail(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a pending payment is held for review")
    void pendingIsReviewed() throws Exception {
        String text = "10-01-2026 Swiggy 1,200.00 pending";
        modelSays("{\"transactions\":[" + debit("Swiggy", 1200, "2026-01-10", text, "PENDING") + "],\"confidence\":0.95}");

        service.process(USER_ID, user, "alerts@hdfcbank.net", "Alert", text, MSG, null);

        EmailFinancialEvent e = recorded().get(0);
        assertThat(e.getState()).isEqualTo(EventState.REQUIRES_REVIEW);
        assertThat(e.getReason()).contains("pending");
    }

    @Test
    @DisplayName("37 stated, 36 read: the statement needs reconciliation")
    void statedCountMismatchNeedsReconciliation() throws Exception {
        when(importer.importParsedEmail(any(), any(), any(), any(), any())).thenReturn(ParsedEmailImporter.ImportOutcome.IMPORTED);
        String text = "10-01-2026 Swiggy 1,200.00 Dr\nTotal transactions: 2";
        modelSays("{\"transactions\":[" + debit("Swiggy", 1200, "2026-01-10", "10-01-2026 Swiggy 1,200.00 Dr", null)
            + "],\"statement_totals\":{\"transaction_count\":2,\"evidence\":\"Total transactions: 2\"},\"confidence\":0.95}");

        EmailLLMParserService.Result r = service.process(USER_ID, user, "alerts@hdfcbank.net", "Statement", text, MSG, null);

        assertThat(r.getTotalsCheck()).isEqualTo("MISMATCHED");
        assertThat(r.getTotalsDetail()).contains("says 2 transaction(s), 1 were read");
        assertThat(EmailLLMParserService.countsOf(r).getOutcome()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(recorded()).anySatisfy(e -> {
            assertThat(e.getEventKey()).endsWith(":totals");
            assertThat(e.getState()).isEqualTo(EventState.RECONCILIATION_REQUIRED);
        });
    }

    @Test
    @DisplayName("a matching stated count closes an earlier totals problem")
    void matchingCountClosesTheGap() throws Exception {
        when(importer.importParsedEmail(any(), any(), any(), any(), any())).thenReturn(ParsedEmailImporter.ImportOutcome.DUPLICATE);
        String text = "10-01-2026 Swiggy 1,200.00 Dr\nTotal transactions: 1";
        modelSays("{\"transactions\":[" + debit("Swiggy", 1200, "2026-01-10", "10-01-2026 Swiggy 1,200.00 Dr", null)
            + "],\"statement_totals\":{\"transaction_count\":1,\"evidence\":\"Total transactions: 1\"},\"confidence\":0.95}");

        EmailLLMParserService.Result r = service.process(USER_ID, user, "alerts@hdfcbank.net", "Statement", text, MSG, null);

        assertThat(r.getTotalsCheck()).isEqualTo("MATCHED");
        verify(ledger).resolveIfOpen(eq(USER_ID), eq(MSG), eq("d0:totals"), anyString());
    }

    @Test
    @DisplayName("a foreign-currency amount with no rupee figure is reviewed, never booked as rupees")
    void foreignCurrencyIsReviewed() throws Exception {
        String text = "Charged USD 20.00 at Netflix on 2026-01-10";
        modelSays("{\"transactions\":[{\"instrument_type\":\"BANK\",\"transaction_type\":\"DEBIT\",\"merchant\":\"Netflix\","
            + "\"transaction_date\":\"2026-01-10\",\"amount_inr\":null,\"amount\":20,\"currency\":\"USD\",\"evidence\":\"" + text + "\"}],\"confidence\":0.95}");

        service.process(USER_ID, user, "alerts@hdfcbank.net", "Card alert", text, MSG, null);

        EmailFinancialEvent e = recorded().get(0);
        assertThat(e.getState()).isEqualTo(EventState.REQUIRES_REVIEW);
        assertThat(e.getCurrency()).isEqualTo("USD");
        assertThat(e.getReason()).contains("USD 20");
    }

    @Test
    @DisplayName("an unreadable extraction is recorded as needing reconciliation")
    void unreadIsRecorded() {
        modelSays("not json");

        EmailLLMParserService.Result r = service.process(USER_ID, user, "alerts@hdfcbank.net", "Alert", "Rs 500 debited", MSG, null);

        assertThat(r.isIncomplete()).isTrue();
        assertThat(recorded()).singleElement().satisfies(e -> {
            assertThat(e.getEventKey()).isEqualTo("d0:unread");
            assertThat(e.getState()).isEqualTo(EventState.RECONCILIATION_REQUIRED);
        });
    }

    @Test
    @DisplayName("an attachment is always read, even when nothing in it looks like money; a body is screened with a reason")
    void documentsAreNeverScreenedOut() {
        modelSays("{\"transactions\":[],\"confidence\":1}");

        EmailLLMParserService.Result body = service.process(USER_ID, user, "friend@example.com", "Hello", "See you soon", MSG, null);
        assertThat(body.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.NOT_FINANCIAL);
        assertThat(body.getDetail()).startsWith("Screened out before extraction");
        verify(llm, never()).complete(any(), any(), anyString());

        service.process(USER_ID, user, "friend@example.com", "Hello", "Page 1 of 3", MSG, null, 1_000_000,
            new EmailLLMParserService.SourceDoc("att-1", "scan.txt", "h", null));
        verify(llm).complete(any(), any(), anyString());
    }

    @Test
    @DisplayName("mail from a known issuer is never screened out, and the wider screen catches invoices and premiums")
    void widerScreen() {
        assertThat(service.looksFinancial("alerts@hdfcbank.net", "Hello", "Greetings")).isTrue();
        assertThat(service.looksFinancial("x@shop.example", "Your invoice", "Thanks for shopping")).isTrue();
        assertThat(service.looksFinancial("x@lic.example", "Premium due", "Your policy")).isTrue();
        assertThat(service.looksFinancial("friend@example.com", "Hello", "See you soon")).isFalse();
    }

    @Test
    @DisplayName("body and text attachment results combine into one email result")
    void mergeCombines() {
        var body = EmailLLMParserService.Result.builder().outcome(EmailLLMParserService.Outcome.REVIEW)
            .extracted(1).queuedForReview(1).build();
        var att = EmailLLMParserService.Result.builder().outcome(EmailLLMParserService.Outcome.IMPORTED)
            .extracted(3).imported(2).duplicates(1).totalsCheck("MISMATCHED").totalsDetail("count").build();

        var merged = EmailLLMParserService.merge(body, att);

        assertThat(merged.getExtracted()).isEqualTo(4);
        assertThat(merged.getImported()).isEqualTo(2);
        assertThat(merged.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.IMPORTED);
        assertThat(merged.getTotalsCheck()).isEqualTo("MISMATCHED");
        var incomplete = EmailLLMParserService.merge(merged,
            EmailLLMParserService.Result.builder().outcome(EmailLLMParserService.Outcome.REVIEW).incomplete(true).build());
        assertThat(incomplete.isIncomplete()).isTrue();
        assertThat(incomplete.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.REVIEW);
    }
}
