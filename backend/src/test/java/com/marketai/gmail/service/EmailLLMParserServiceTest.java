package com.marketai.gmail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmProviderRouter;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.ai.review.service.EmailReviewService;
import com.marketai.auth.entity.User;
import com.marketai.document.classify.SenderTrustEvaluator;
import com.marketai.identity.service.PasswordStrategy;
import com.marketai.market.service.MarketDataService;
import com.marketai.mf.entity.CasBalanceSnapshot;
import com.marketai.mf.repository.CasBalanceSnapshotRepository;
import com.marketai.mf.service.MfSchemeLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The single LLM-based extraction path that replaced the 18 regex parsers and the two AI
 * fallbacks. Every test here mocks {@link LlmProviderRouter} only — {@link LlmJsonParser} is
 * real, since the whole point of the deterministic-safety boundary is that JSON parsing/field
 * reading is not something a test should fake past.
 */
class EmailLLMParserServiceTest {

    private static final Long USER_ID = 42L;
    private static final String MSG_ID = "msg-1";

    private LlmProviderRouter llm;
    private AiAuditService audit;
    private MarketDataService marketDataService;
    private MfSchemeLinkService mfSchemeLinkService;
    private EmailReviewService emailReviewService;
    private ParsedEmailImporter importer;
    private CasBalanceSnapshotRepository casBalanceSnapshotRepository;
    private EmailLLMParserService service;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        llm = mock(LlmProviderRouter.class);
        audit = mock(AiAuditService.class);
        marketDataService = mock(MarketDataService.class);
        mfSchemeLinkService = mock(MfSchemeLinkService.class);
        emailReviewService = mock(EmailReviewService.class);
        importer = mock(ParsedEmailImporter.class);
        casBalanceSnapshotRepository = mock(CasBalanceSnapshotRepository.class);

        LlmJsonParser json = new LlmJsonParser(new ObjectMapper());
        SenderTrustEvaluator senderTrustEvaluator = new SenderTrustEvaluator();

        service = new EmailLLMParserService(llm, json, audit, marketDataService,
            mfSchemeLinkService, senderTrustEvaluator, emailReviewService, importer,
            casBalanceSnapshotRepository);
        ReflectionTestUtils.setField(service, "minConfidence", 0.85);

        user = new User();
        user.setId(USER_ID);
    }

    private LlmCompletion completion(String text) {
        return LlmCompletion.builder().text(text).provider("test").model("test-model").build();
    }

    @Test
    @DisplayName("a well-formed, high-confidence, grounded MF transaction is imported")
    void wellFormedJsonHappyPath() throws Exception {
        String sourceText = "Dear investor, Amount Rs 5000 invested in HDFC Small Cap Fund on 15-Jan-2026.";
        String json = "{\"transactions\":[{\"instrument_type\":\"MF\",\"transaction_type\":\"PURCHASE\"," +
            "\"scheme_name\":\"HDFC Small Cap Fund - Direct Plan - Growth\",\"plan_type\":\"Direct\"," +
            "\"option_type\":\"Growth\",\"folio_number\":\"12345\",\"transaction_date\":\"2026-01-15\"," +
            "\"amount_inr\":5000,\"nav\":100.50,\"units\":49.75," +
            "\"evidence\":\"Amount Rs 5000 invested in HDFC Small Cap Fund\"}],\"confidence\":0.95}";

        when(llm.complete(anyString(), anyString())).thenReturn(completion(json));
        when(mfSchemeLinkService.resolveSchemeCodeByFundName("HDFC Small Cap Fund - Direct Plan - Growth"))
            .thenReturn(Optional.of("HDFC001"));

        EmailLLMParserService.Result result = service.process(USER_ID, user,
            "noreply@hdfcfund.com", "Purchase confirmation", sourceText, MSG_ID, null);

        assertThat(result.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.IMPORTED);
        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getQueuedForReview()).isZero();
        verify(importer).importParsedEmail(eq(USER_ID), eq(user), any(), eq(MSG_ID), eq(sourceText));
        verify(emailReviewService, never()).enqueue(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("malformed/partial JSON is rejected by LlmJsonParser and queues the whole email for review")
    void malformedJsonRoutesToReview() throws Exception {
        when(llm.complete(anyString(), anyString())).thenReturn(completion("this is not json at all"));

        EmailLLMParserService.Result result = service.process(USER_ID, user,
            "noreply@hdfcfund.com", "Purchase confirmation",
            "Amount Rs 5000 debited for your SIP.", MSG_ID, null);

        assertThat(result.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.REVIEW);
        verify(importer, never()).importParsedEmail(any(), any(), any(), any(), any());
        verify(emailReviewService).enqueue(eq(USER_ID), eq(MSG_ID), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a transaction whose evidence span cannot be found in the source text is dropped, not trusted")
    void spanVerificationFailureRoutesToReview() throws Exception {
        String sourceText = "Dear investor, your SIP has been processed successfully.";
        String json = "{\"transactions\":[{\"instrument_type\":\"MF\",\"transaction_type\":\"SIP\"," +
            "\"scheme_name\":\"HDFC Small Cap Fund - Direct Plan - Growth\"," +
            "\"transaction_date\":\"2026-01-15\",\"amount_inr\":5000," +
            "\"evidence\":\"This exact sentence does not appear anywhere in the source text\"}]," +
            "\"confidence\":0.95}";
        when(llm.complete(anyString(), anyString())).thenReturn(completion(json));

        EmailLLMParserService.Result result = service.process(USER_ID, user,
            "noreply@hdfcfund.com", "SIP confirmation", sourceText, MSG_ID, null);

        assertThat(result.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.REVIEW);
        assertThat(result.getQueuedForReview()).isEqualTo(1);
        verify(importer, never()).importParsedEmail(any(), any(), any(), any(), any());
        verify(emailReviewService).enqueue(eq(USER_ID), eq(MSG_ID), eq(0), anyString(), anyString(), any());
        // Never resolved against AMFI — dropped before that check even runs, since it has no
        // usable evidence at all.
        verifyNoInteractions(mfSchemeLinkService);
    }

    @Test
    @DisplayName("confidence below app.llm.min-confidence queues the transaction for review instead of auto-importing")
    void lowConfidenceRoutesToReview() throws Exception {
        String sourceText = "Dear investor, Amount Rs 5000 invested in HDFC Small Cap Fund on 15-Jan-2026.";
        String json = "{\"transactions\":[{\"instrument_type\":\"MF\",\"transaction_type\":\"PURCHASE\"," +
            "\"scheme_name\":\"HDFC Small Cap Fund - Direct Plan - Growth\"," +
            "\"transaction_date\":\"2026-01-15\",\"amount_inr\":5000,\"nav\":100.50,\"units\":49.75," +
            "\"evidence\":\"Amount Rs 5000 invested in HDFC Small Cap Fund\"}],\"confidence\":0.40}";
        when(llm.complete(anyString(), anyString())).thenReturn(completion(json));
        when(mfSchemeLinkService.resolveSchemeCodeByFundName(anyString())).thenReturn(Optional.of("HDFC001"));

        EmailLLMParserService.Result result = service.process(USER_ID, user,
            "noreply@hdfcfund.com", "Purchase confirmation", sourceText, MSG_ID, null);

        assertThat(result.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.REVIEW);
        verify(importer, never()).importParsedEmail(any(), any(), any(), any(), any());
        verify(emailReviewService).enqueue(eq(USER_ID), eq(MSG_ID), eq(0), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an unavailable LLM routes to review rather than crashing the sync")
    void llmUnavailableRoutesToReviewNotCrash() throws Exception {
        when(llm.complete(anyString(), anyString())).thenThrow(new LlmUnavailableException("no model"));

        EmailLLMParserService.Result result = service.process(USER_ID, user,
            "noreply@hdfcfund.com", "Purchase confirmation",
            "Amount Rs 5000 debited for your SIP.", MSG_ID, null);

        assertThat(result.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.UNAVAILABLE);
        verify(importer, never()).importParsedEmail(any(), any(), any(), any(), any());
        verify(emailReviewService).enqueue(eq(USER_ID), eq(MSG_ID), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a statement_provider the sending domain doesn't support is treated as spoofed, even with no display-name claim")
    void llmClaimedProviderMismatchWithDomainRoutesToReview() throws Exception {
        // hdfcbank.com is a verified HDFC Bank domain (IssuerDomainRegistry) and the From header
        // makes no display-name claim at all — SenderTrustEvaluator alone would call this
        // VERIFIED_DOMAIN and permit the import. The LLM's own claimed provider is what catches
        // the mismatch here.
        String sourceText = "Your account has been debited Rs 2500 towards UPI payment.";
        String json = "{\"transactions\":[{\"instrument_type\":\"BANK\",\"transaction_type\":\"DEBIT\"," +
            "\"transaction_date\":\"2026-01-15\",\"amount_inr\":2500,\"merchant\":\"Some Shop\"," +
            "\"evidence\":\"debited Rs 2500 towards UPI payment\"}],\"confidence\":0.95}";
        when(llm.complete(anyString(), anyString())).thenReturn(completion(json));

        EmailLLMParserService.Classification claim =
            new EmailLLMParserService.Classification(true, "ZERODHA", null);

        EmailLLMParserService.Result result = service.process(USER_ID, user,
            "noreply@hdfcbank.com", "Zerodha trade confirmation", sourceText, MSG_ID, claim);

        assertThat(result.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.REVIEW);
        verify(importer, never()).importParsedEmail(any(), any(), any(), any(), any());
        verify(emailReviewService).enqueue(eq(USER_ID), eq(MSG_ID), eq(0), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a credit card statement's individual purchase line items import as separate, correctly-categorized expenses")
    void creditCardStatementLineItemsImportSeparately() throws Exception {
        String sourceText = "Card Statement: Swiggy Instamart Rs 1200 on 2026-01-10; "
            + "Starbucks Rs 450 on 2026-01-12; Total Amount Due: Rs 1650.";
        String json = "{\"transactions\":[" +
            "{\"instrument_type\":\"BANK\",\"transaction_type\":\"DEBIT\",\"merchant\":\"Swiggy Instamart\"," +
                "\"transaction_date\":\"2026-01-10\",\"amount_inr\":1200," +
                "\"evidence\":\"Swiggy Instamart Rs 1200 on 2026-01-10\"}," +
            "{\"instrument_type\":\"BANK\",\"transaction_type\":\"DEBIT\",\"merchant\":\"Starbucks\"," +
                "\"transaction_date\":\"2026-01-12\",\"amount_inr\":450," +
                "\"evidence\":\"Starbucks Rs 450 on 2026-01-12\"}" +
            "],\"confidence\":0.95}";
        when(llm.complete(anyString(), anyString())).thenReturn(completion(json));

        EmailLLMParserService.Result result = service.process(USER_ID, user,
            "alerts@hdfcbank.com", "Credit Card Statement", sourceText, MSG_ID, null);

        assertThat(result.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.IMPORTED);
        assertThat(result.getImported()).isEqualTo(2);
        verify(importer, times(2)).importParsedEmail(eq(USER_ID), eq(user), any(), eq(MSG_ID), eq(sourceText));
    }

    @Test
    @DisplayName("a CRED/credit-card-bill-payment debit is categorized as Account Transfer, excluded from spend")
    void credPaymentImportsAsAccountTransfer() throws Exception {
        String sourceText = "Rs 5000 paid to CRED towards your credit card bill on 2026-01-15.";
        String json = "{\"transactions\":[{\"instrument_type\":\"BANK\",\"transaction_type\":\"DEBIT\"," +
            "\"merchant\":\"CRED\",\"transaction_date\":\"2026-01-15\",\"amount_inr\":5000," +
            "\"evidence\":\"Rs 5000 paid to CRED towards your credit card bill\"}],\"confidence\":0.95}";
        when(llm.complete(anyString(), anyString())).thenReturn(completion(json));

        EmailLLMParserService.Result result = service.process(USER_ID, user,
            "alerts@hdfcbank.com", "Payment confirmation", sourceText, MSG_ID, null);

        assertThat(result.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.IMPORTED);
        assertThat(result.getImported()).isEqualTo(1);
        verify(importer).importParsedEmail(eq(USER_ID), eq(user), argThat(pe ->
            pe.getCategory().equals(com.marketai.expense.entity.ExpenseCategory.ACCOUNT_TRANSFER.getLabel())),
            eq(MSG_ID), eq(sourceText));
    }

    // --- closing_balances[] — CAS statement closing-balance snapshots ---

    @Test
    @DisplayName("a closing_balances entry with a valid span is persisted as a CasBalanceSnapshot")
    void closingBalanceWithValidSpanIsPersisted() throws Exception {
        String sourceText = "Statement for folio 98765: Closing Balance: 1234.5670 units as of 31-Jan-2026 "
            + "for HDFC Small Cap Fund - Direct Plan - Growth.";
        String json = "{\"transactions\":[],\"closing_balances\":[{\"folio\":\"98765\"," +
            "\"scheme_name\":\"HDFC Small Cap Fund - Direct Plan - Growth\"," +
            "\"as_of_date\":\"2026-01-31\",\"units\":1234.5670," +
            "\"evidence\":\"Closing Balance: 1234.5670 units as of 31-Jan-2026\"}],\"confidence\":0.95}";
        when(llm.complete(anyString(), anyString())).thenReturn(completion(json));
        when(mfSchemeLinkService.resolveSchemeCodeByFundName("HDFC Small Cap Fund - Direct Plan - Growth"))
            .thenReturn(Optional.of("HDFC001"));

        EmailLLMParserService.Result result = service.process(USER_ID, user,
            "noreply@camsonline.com", "CAS Statement", sourceText, MSG_ID, null);

        assertThat(result.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.NOT_FINANCIAL);
        verify(casBalanceSnapshotRepository).save(argThat((CasBalanceSnapshot snap) ->
            snap.getUserId().equals(USER_ID)
                && snap.getFolio().equals("98765")
                && snap.getSchemeCode().equals("HDFC001")
                && snap.getStatedUnits().compareTo(new java.math.BigDecimal("1234.5670")) == 0
                && snap.getAsOfDate().equals(java.time.LocalDate.of(2026, 1, 31))));
    }

    @Test
    @DisplayName("a closing_balances entry whose evidence span isn't in the source text is not persisted")
    void ungroundedClosingBalanceIsNotPersisted() throws Exception {
        String sourceText = "Statement for folio 98765: your SIP has been processed successfully.";
        String json = "{\"transactions\":[],\"closing_balances\":[{\"folio\":\"98765\"," +
            "\"scheme_name\":\"HDFC Small Cap Fund - Direct Plan - Growth\"," +
            "\"as_of_date\":\"2026-01-31\",\"units\":1234.5670," +
            "\"evidence\":\"This exact sentence does not appear anywhere in the source text\"}]," +
            "\"confidence\":0.95}";
        when(llm.complete(anyString(), anyString())).thenReturn(completion(json));

        service.process(USER_ID, user, "noreply@camsonline.com", "CAS Statement", sourceText, MSG_ID, null);

        verify(casBalanceSnapshotRepository, never()).save(any());
        verifyNoInteractions(mfSchemeLinkService);
    }

    @Test
    @DisplayName("closing_balances is not persisted when the sender fails the trust gate")
    void closingBalanceSkippedWhenSenderBlocked() throws Exception {
        String sourceText = "Statement for folio 98765: Closing Balance: 1234.5670 units as of 31-Jan-2026.";
        String json = "{\"transactions\":[],\"closing_balances\":[{\"folio\":\"98765\"," +
            "\"scheme_name\":\"HDFC Small Cap Fund - Direct Plan - Growth\"," +
            "\"as_of_date\":\"2026-01-31\",\"units\":1234.5670," +
            "\"evidence\":\"Closing Balance: 1234.5670 units as of 31-Jan-2026\"}],\"confidence\":0.95}";
        when(llm.complete(anyString(), anyString())).thenReturn(completion(json));

        // Display name claims CAMS but the sending domain isn't a CAMS domain — impersonation.
        service.process(USER_ID, user, "\"CAMS\" <noreply@attacker.example>", "CAS Statement",
            sourceText, MSG_ID, null);

        verify(casBalanceSnapshotRepository, never()).save(any());
    }

    // --- classify() / mapPasswordHint() — the password-hint mapping used by PdfImportService ---

    @Test
    @DisplayName("password hint mapping is exact and fails safe on anything unrecognised")
    void mapPasswordHintIsFailSafe() throws Exception {
        assertThat(service.mapPasswordHint("PAN")).isEqualTo(PasswordStrategy.PAN_UPPERCASE);
        assertThat(service.mapPasswordHint("PAN_DOB")).isEqualTo(PasswordStrategy.PAN_UPPERCASE_PLUS_DOB_DDMMYYYY);
        assertThat(service.mapPasswordHint("PAN_FIRST4_DOB")).isEqualTo(PasswordStrategy.PAN_FIRST4_PLUS_DOB_DDMMYYYY);
        assertThat(service.mapPasswordHint("DOB")).isEqualTo(PasswordStrategy.DOB_DDMMYYYY);
        assertThat(service.mapPasswordHint("USER_DEFINED")).isNull();
        assertThat(service.mapPasswordHint("something the model made up")).isNull();
        assertThat(service.mapPasswordHint(null)).isNull();
    }

    @Test
    @DisplayName("classify() returns a no-op classification when the LLM is unavailable")
    void classifyDegradesGracefullyWhenUnavailable() throws Exception {
        when(llm.complete(anyString(), anyString())).thenThrow(new LlmUnavailableException("down"));

        EmailLLMParserService.Classification result = service.classify(
            "noreply@hdfcfund.com", "SIP confirmation", "Your SIP of Rs 5000 has been debited.");

        assertThat(result.financialStatement()).isFalse();
        assertThat(result.statementProvider()).isNull();
        assertThat(result.passwordHintType()).isNull();
    }

    @Test
    @DisplayName("classify() reads the password hint type from a well-formed response")
    void classifyReadsPasswordHint() throws Exception {
        when(llm.complete(anyString(), anyString())).thenReturn(completion(
            "{\"is_financial_statement\":true,\"statement_provider\":\"CAMS\",\"password_hint_type\":\"PAN\"}"));

        EmailLLMParserService.Classification result = service.classify(
            "noreply@camsonline.com", "Your CAS statement", "Password is your PAN in uppercase.");

        assertThat(result.financialStatement()).isTrue();
        assertThat(result.statementProvider()).isEqualTo("CAMS");
        assertThat(result.passwordHintType()).isEqualTo(PasswordStrategy.PAN_UPPERCASE);
    }
}
