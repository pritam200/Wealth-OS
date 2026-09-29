package com.marketai.gmail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmService;
import com.marketai.ai.review.service.EmailReviewService;
import com.marketai.auth.entity.User;
import com.marketai.document.classify.SenderTrustEvaluator;
import com.marketai.expense.entity.ExpenseCategory;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.market.entity.Stock;
import com.marketai.market.service.MarketDataService;
import com.marketai.mf.repository.CasBalanceSnapshotRepository;
import com.marketai.mf.service.MfSchemeLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** How the extractor maps the Phase 5 event types, with the LLM mocked and the JSON reading real. */
class EmailLLMParserEventTypesTest {

    private static final Long USER_ID = 42L;
    private LlmService llm;
    private MarketDataService marketData;
    private MfSchemeLinkService mfLink;
    private EmailReviewService review;
    private ParsedEmailImporter importer;
    private EmailLLMParserService service;
    private User user;

    @BeforeEach
    void setUp() {
        llm = mock(LlmService.class);
        marketData = mock(MarketDataService.class);
        mfLink = mock(MfSchemeLinkService.class);
        review = mock(EmailReviewService.class);
        importer = mock(ParsedEmailImporter.class);
        service = new EmailLLMParserService(llm, new LlmJsonParser(new ObjectMapper()), mock(AiAuditService.class),
            marketData, mfLink, new SenderTrustEvaluator(), review, importer, mock(CasBalanceSnapshotRepository.class),
            mock(com.marketai.gmail.ledger.FinancialEventLedger.class));
        ReflectionTestUtils.setField(service, "minConfidence", 0.85);
        user = new User();
        user.setId(USER_ID);
    }

    /** Runs one extracted transaction and returns what reached the importer, or null if held. */
    private ParsedEmail extract(String source, String txnJson) throws Exception {
        when(llm.complete(any(), any(), anyString())).thenReturn(LlmCompletion.builder()
            .text("{\"transactions\":[" + txnJson + "],\"confidence\":0.95}").provider("t").model("m").build());
        service.process(USER_ID, user, "alerts@hdfcbank.net", "Alert", source, "msg-1", null);
        ArgumentCaptor<ParsedEmail> pe = ArgumentCaptor.forClass(ParsedEmail.class);
        try {
            verify(importer).importParsedEmail(any(), any(), pe.capture(), any(), any());
        } catch (AssertionError none) {
            return null;
        }
        return pe.getValue();
    }

    private static String bank(String direction, String subType, String amount, String evidence) {
        return "{\"instrument_type\":\"BANK\",\"transaction_type\":\"" + direction + "\",\"sub_type\":" + subType
            + ",\"merchant\":\"Amazon\",\"transaction_date\":\"2026-09-10\",\"amount_inr\":" + amount
            + ",\"evidence\":\"" + evidence + "\"}";
    }

    @Test
    @DisplayName("a refund credit becomes a REFUND, not income")
    void refund() throws Exception {
        String line = "Rs 2499.00 credited on 10-09-2026 refund from Amazon";
        ParsedEmail pe = extract("Dear customer, " + line + ".", bank("CREDIT", "\"REFUND\"", "2499", line));
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.REFUND);
        assertThat(pe.getMerchant()).isEqualTo("Amazon");
    }

    @Test
    @DisplayName("a bank fee is spending in Bank Charges")
    void fee() throws Exception {
        String line = "Rs 590.00 debited on 10-09-2026 annual card fee";
        ParsedEmail pe = extract(line, bank("DEBIT", "\"FEE\"", "590", line));
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.EXPENSE);
        assertThat(ExpenseCategory.fromLabel(pe.getCategory())).isEqualTo(ExpenseCategory.BANK_CHARGES);
    }

    @Test
    @DisplayName("an own-account credit is an incoming transfer")
    void ownTransfer() throws Exception {
        String line = "Rs 50000.00 credited on 10-09-2026 from your a/c XX1234";
        ParsedEmail pe = extract(line, bank("CREDIT", "\"OWN_TRANSFER\"", "50000", line));
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.OWN_TRANSFER);
        assertThat(pe.getIncoming()).isTrue();
    }

    @Test
    @DisplayName("an EMI conversion is held: the purchase is already counted")
    void emiConversion() throws Exception {
        String line = "Rs 60000.00 on 10-09-2026 converted to EMI";
        assertThat(extract(line, bank("DEBIT", "\"EMI_CONVERSION\"", "60000", line))).isNull();
        verify(review).enqueue(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("a split is read with its ratio; one without a readable ratio is held")
    void split() throws Exception {
        when(marketData.searchStocks("TCS")).thenReturn(List.of(Stock.builder().symbol("TCS").name("TCS").build()));
        String line = "TCS sub-division of shares from 1 to 2, record date 10-09-2026";
        ParsedEmail pe = extract(line, "{\"instrument_type\":\"EQUITY\",\"transaction_type\":\"SPLIT\",\"symbol\":\"TCS\","
            + "\"ratio_from\":1,\"ratio_to\":2,\"transaction_date\":\"2026-09-10\",\"evidence\":\"" + line + "\"}");
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.CORPORATE_ACTION);
        assertThat(pe.getCorporateAction()).isEqualTo("SPLIT");
        assertThat(pe.getRatioTo()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("an FD maturity becomes a DEPOSIT_CLOSE carrying the principal and TDS")
    void fdMaturity() throws Exception {
        String line = "FD matured on 10-09-2026: principal Rs 100000, Rs 106500 credited after TDS Rs 750";
        ParsedEmail pe = extract(line, "{\"instrument_type\":\"FD\",\"transaction_type\":\"MATURITY\",\"bank\":\"HDFC Bank\","
            + "\"principal_inr\":100000,\"tds_inr\":750,\"amount_inr\":106500,\"transaction_date\":\"2026-09-10\","
            + "\"evidence\":\"" + line + "\"}");
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.DEPOSIT_CLOSE);
        assertThat(pe.getInstrumentKind()).isEqualTo("FD");
        assertThat(pe.getPrincipal()).isEqualByComparingTo("100000");
        assertThat(pe.getTds()).isEqualByComparingTo("750");
    }

    @Test
    @DisplayName("a switch-out is a redemption tagged as one leg of a switch")
    void switchOut() throws Exception {
        when(mfLink.resolveSchemeCodeByFundName(anyString())).thenReturn(Optional.of("100"));
        String line = "Switch out 49.75 units of HDFC Small Cap Fund Rs 5000 on 10-09-2026";
        ParsedEmail pe = extract(line, "{\"instrument_type\":\"MF\",\"transaction_type\":\"SWITCH_OUT\","
            + "\"scheme_name\":\"HDFC Small Cap Fund - Direct Plan - Growth\",\"transaction_date\":\"2026-09-10\","
            + "\"amount_inr\":5000,\"nav\":100.50,\"units\":49.75,\"evidence\":\"" + line + "\"}");
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.MF_REDEEM);
        assertThat(pe.getLinkGroup()).isEqualTo("SWITCH");
    }
}
