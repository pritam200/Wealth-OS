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
        // A registrar, so the bank-only fixed parsers and offer filter stay out of these cases.
        service.process(USER_ID, user, "donotreply@camsonline.com", "Alert", source, "msg-1", null);
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
    @DisplayName("a contract note's company name cut off at 20 characters still resolves to the stock")
    void truncatedCompanyName() throws Exception {
        when(marketData.searchStocks("HERO MOTOCORP")).thenReturn(List.of(
            Stock.builder().symbol("HEROMOTOCO").name("Hero MotoCorp Ltd").build()));
        String line = "INE158A01026 HERO MOTOCORP LIMITE 1 5156.0000 5156.0000 5156.0000 on 10-09-2026";
        ParsedEmail pe = extract(line, "{\"instrument_type\":\"EQUITY\",\"transaction_type\":\"BUY\","
            + "\"symbol\":\"HERO MOTOCORP LIMITE\",\"isin\":\"INE158A01026\",\"quantity\":1,\"price\":5156,"
            + "\"amount_inr\":5156,\"transaction_date\":\"2026-09-10\",\"evidence\":\"" + line + "\"}");
        assertThat(pe.getSymbol()).isEqualTo("HEROMOTOCO");
    }

    @Test
    @DisplayName("an m.Stock contract note is booked without the model: every fill, at Mirae Asset, F&O left out")
    void mstockContractNote() throws Exception {
        when(marketData.searchStocks(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            if (q.contains("BHARTI") || q.equals("INE397D01024"))
                return List.of(Stock.builder().symbol("BHARTIARTL").name("Bharti Airtel Ltd").build());
            if (q.contains("LARSEN") || q.equals("INE018A01030"))
                return List.of(Stock.builder().symbol("LT").name("Larsen & Toubro Ltd").build());
            return List.of();
        });
        String note = String.join("\n",
            "CONTRACT NOTE CUM TAX INVOICE",
            "Trade Date : 13/10/2026",
            "1000000000080 09:57:24 1488255 09:57:24 NSE - M BHARTI AIRTEL LIMITE SELL 40 1888.0000 0.0000 1888.0000 0.0000 75520.0000",
            "1000000000080 09:57:24 1488255 09:57:24 NSE - M BHARTI AIRTEL LIMITE SELL 40 1888.0000 0.0000 1888.0000 0.0000 75520.0000",
            "1000000000081 10:02:11 1488300 10:02:11 NSE - M BHARTI AIRTEL LIMITE SELL 40 1888.0000 0.0000 1888.0000 0.0000 75520.0000",
            "1000000001382 12:59:24 1200001842 12:59:24 NSE - M LARSEN & TOUBRO LTD. BUY 5 3648.7000 0.0000 3648.7000 0.0000 -18243.5000",
            "1000000000057 11:20:13 3527598 11:20:39 NSEFO - BT_O OPTIDX NIFTY 13OCT26 22150.00 PE (BT) BUY 65 24.6000 0.0000 24.6000 0.0000 -1599.0000",
            "ISIN Security Name Buy Qty WAP Sell Qty WAP Net",
            "INE397D01024 BHARTI AIRTEL LIMITE 120 1888.0000 0.0526 1887.9474 226560.0000 0 226560.0000",
            "INE018A01030 LARSEN & TOUBRO LTD 5 3648.7000 3648.7000 18243.5000 5 -18243.5000");

        service.process(USER_ID, user, "contractnote@mstock.com", "Contract Note as on Oct 13 2026", note, "msg-1", null,
            0, new EmailLLMParserService.SourceDoc("att-1", "note.pdf", "h", null));

        verify(llm, never()).complete(any(), any(), anyString());
        ArgumentCaptor<ParsedEmail> pe = ArgumentCaptor.forClass(ParsedEmail.class);
        verify(importer, times(4)).importParsedEmail(any(), any(), pe.capture(), any(), any());
        List<ParsedEmail> fills = pe.getAllValues();
        assertThat(fills).extracting(ParsedEmail::getSymbol).containsExactly("BHARTIARTL", "BHARTIARTL", "BHARTIARTL", "LT");
        assertThat(fills).extracting(ParsedEmail::getBroker).containsOnly("Mirae Asset");
        assertThat(fills).extracting(ParsedEmail::getTradeReference).containsExactly("1488255", "1488255", "1488300", "1200001842");
        // Three sells of 40 at one price on one day are three trades; the two identical lines are numbered apart.
        assertThat(fills.get(0).getOccurrenceInSource()).isZero();
        assertThat(fills.get(1).getOccurrenceInSource()).isEqualTo(1);
        verify(review, never()).enqueue(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("an exchange's trade alert is recorded as a confirmation, neither booked nor queued")
    void exchangeTradeAlert() throws Exception {
        when(llm.complete(any(), any(), anyString())).thenReturn(LlmCompletion.builder()
            .text("{\"transactions\":[{\"instrument_type\":\"EQUITY\",\"transaction_type\":\"BUY\",\"symbol\":\"AXISBANK\","
                + "\"quantity\":5,\"price\":1260,\"amount_inr\":6300,\"transaction_date\":\"2026-10-14\","
                + "\"evidence\":\"B Axis Bank Limited AXISBANK EQ 5 1260\"}],\"confidence\":0.95}").provider("t").model("m").build());
        service.process(USER_ID, user, "nse-direct@nse.co.in", "Trades executed at NSE",
            "B Axis Bank Limited AXISBANK EQ 5 1260", "msg-1", null);
        verify(importer, never()).importParsedEmail(any(), any(), any(), any(), any());
        verify(review, never()).enqueue(any(), any(), anyInt(), any(), any(), any());
    }

    /** Runs a bank alert through the whole pipeline; returns what reached the importer, or null. */
    private ParsedEmail alert(String from, String body) throws Exception {
        user.setName("PRITAM KUMAR SINGH");
        EmailLLMParserService.Result r = service.process(USER_ID, user, from, "Alert", body, "msg-1", null);
        verify(llm, never()).complete(any(), any(), anyString());
        lastResult = r;
        ArgumentCaptor<ParsedEmail> pe = ArgumentCaptor.forClass(ParsedEmail.class);
        try {
            verify(importer).importParsedEmail(any(), any(), pe.capture(), any(), any());
        } catch (AssertionError none) {
            return null;
        }
        return pe.getValue();
    }

    private EmailLLMParserService.Result lastResult;

    @Test
    @DisplayName("an Axis UPI alert is booked as spending by its fixed parser, without the extractor")
    void axisAlertBookedWithoutModel() throws Exception {
        ParsedEmail pe = alert("alerts@axis.bank.in", "Amount Debited:\nINR 2129.00\nAccount Number:\nXX6248\n"
            + "Date & Time:\n02-10-26, 18:39:14 IST\nTransaction Info:\nUPI/P2M/123459734/SUNIL STORE\n");
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.EXPENSE);
        assertThat(pe.getAmount()).isEqualByComparingTo("2129");
        assertThat(pe.getTradeDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 2));
        assertThat(pe.getMerchant()).isEqualTo("SUNIL STORE");
    }

    @Test
    @DisplayName("an SIP debit is neither spending nor a review question: the fund confirmation books it")
    void sipDebitIsCovered() throws Exception {
        ParsedEmail pe = alert("alerts@hdfcbank.bank.in", "Rs.5000.00 has been debited from HDFC Bank Account Number "
            + "XXXXXXXXXX2548 towards HDFC Mutual Fund OTM/12340633 with UMRN HDFC00012408 on 22-Oct-2026.");
        assertThat(pe).isNull();
        verify(review, never()).enqueue(any(), any(), anyInt(), any(), any(), any());
        assertThat(lastResult.getResolved()).isEqualTo(1);
    }

    @Test
    @DisplayName("an SBI transfer to the holder's own account is an own-account transfer")
    void sbiOwnTransfer() throws Exception {
        ParsedEmail pe = alert("cbssbi.cas@alerts.sbi.bank.in", "Your A/C XXXXX953801 Debited INR 88.00 on 20/10/26 "
            + "-Transferred to Mr. PRITAM KUMARSI. Avl Balance INR 1,50,000.00-SBI");
        assertThat(pe.getType()).isEqualTo(ParsedEmail.Type.OWN_TRANSFER);
    }

    @Test
    @DisplayName("a bank's offer is recognised as no transaction without the extractor")
    void bankOfferSkipped() throws Exception {
        assertThat(alert("offers@indusind.com", "Begin your savings journey with 7% p.a. assured returns on an FD.")).isNull();
        assertThat(lastResult.getOutcome()).isEqualTo(EmailLLMParserService.Outcome.NOT_FINANCIAL);
    }

    @Test
    @DisplayName("money from the stock clearing house is not income")
    void brokerPayoutNotIncome() throws Exception {
        String line = "Rs 57700.00 credited on 10-09-2026 by NSCCL payout";
        assertThat(extract(line, bank("CREDIT", "null", "57700", line))).isNull();
        verify(review, never()).enqueue(any(), any(), anyInt(), any(), any(), any());
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
