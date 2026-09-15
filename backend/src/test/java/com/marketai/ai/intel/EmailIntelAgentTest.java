package com.marketai.ai.intel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.audit.service.AiAuditService;
import com.marketai.ai.llm.LlmCompletion;
import com.marketai.ai.llm.LlmJsonParser;
import com.marketai.ai.llm.LlmProviderRouter;
import com.marketai.ai.llm.LlmUnavailableException;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.market.entity.Stock;
import com.marketai.market.service.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Classification routing: what gets imported, what goes to a human, and what is discarded.
 * The recurring theme is that uncertainty must become a review row — never a silent drop and
 * never an auto-import.
 */
class EmailIntelAgentTest {

    private static final Long USER_ID = 5L;
    private static final String UPI_EMAIL =
        "Dear Customer, Rs.450.00 has been debited from your A/c XX1234 on 04-03-26 to SWIGGY via UPI.";

    private LlmProviderRouter llm;
    private AiAuditService audit;
    private MarketDataService marketData;
    private EmailIntelAgent agent;

    @BeforeEach
    void setup() {
        llm = mock(LlmProviderRouter.class);
        audit = mock(AiAuditService.class);
        marketData = mock(MarketDataService.class);
        ObjectMapper mapper = new ObjectMapper();
        agent = new EmailIntelAgent(llm, new LlmJsonParser(mapper), audit, mapper, marketData);
        ReflectionTestUtils.setField(agent, "minConfidence", 0.85);
    }

    private void modelReturns(String json) {
        when(llm.complete(anyString(), anyString())).thenReturn(
            LlmCompletion.builder().text(json).model("qwen2.5:7b").provider("ollama").latencyMs(10).build());
    }

    @Test
    void highConfidenceExpense_isImported() {
        modelReturns("{\"classification\":\"UPI_EXPENSE\",\"confidence\":0.95,"
            + "\"extractedFields\":{\"amount\":450.00,\"date\":\"2026-03-04\",\"merchant\":\"Swiggy\"},"
            + "\"reasoning\":\"UPI debit to a merchant\",\"evidence\":\"Rs.450.00 has been debited\"}");

        EmailIntelResult r = agent.classify(USER_ID, "alerts@hdfcbank.net", "UPI alert", UPI_EMAIL, "m1");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.IMPORT);
        assertThat(r.getType()).isEqualTo(EmailIntelType.UPI_EXPENSE);
        assertThat(r.getParsed().getType()).isEqualTo(ParsedEmail.Type.EXPENSE);
        assertThat(r.getParsed().getAmount()).isEqualByComparingTo("450.00");
    }

    @Test
    void lowConfidence_routesToReviewInsteadOfImporting() {
        modelReturns("{\"classification\":\"UPI_EXPENSE\",\"confidence\":0.60,"
            + "\"extractedFields\":{\"amount\":450.00},\"reasoning\":\"unclear\",\"evidence\":\"...\"}");

        EmailIntelResult r = agent.classify(USER_ID, "x@y.com", "alert", UPI_EMAIL, "m2");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.REVIEW_REQUIRED);
        assertThat(r.getReviewReason()).contains("below");
        assertThat(r.getParsed()).isNull();
        verify(audit).record(eq(USER_ID), eq("EMAIL_CLASSIFY"), eq("m2"), anyString(), anyString(),
            any(), eq(0.60), eq("REVIEW_REQUIRED"), anyString());
    }

    @Test
    void missingConfidenceIsTreatedAsLow_notAsPermission() {
        modelReturns("{\"classification\":\"UPI_EXPENSE\","
            + "\"extractedFields\":{\"amount\":450.00},\"reasoning\":\"r\",\"evidence\":\"e\"}");

        EmailIntelResult r = agent.classify(USER_ID, "x@y.com", "alert", UPI_EMAIL, "m3");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.REVIEW_REQUIRED);
        assertThat(r.getReviewReason()).contains("did not report a usable confidence");
    }

    @Test
    void internalTransfer_isNeverAutoImportedEvenAtHighConfidence() {
        // A bank->MF movement booked as an expense or as income would distort net worth, so
        // even a confident classification has to be confirmed by a human.
        modelReturns("{\"classification\":\"INTERNAL_TRANSFER\",\"confidence\":0.99,"
            + "\"extractedFields\":{\"amount\":50000,\"date\":\"2026-03-04\",\"counterparty\":\"Own MF folio\"},"
            + "\"reasoning\":\"Funds moved to own investment account\",\"evidence\":\"transferred to your folio\"}");

        EmailIntelResult r = agent.classify(USER_ID, "x@bank.com", "Transfer", "Rs.50000 transferred to your folio", "m4");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.REVIEW_REQUIRED);
        assertThat(r.getType()).isEqualTo(EmailIntelType.INTERNAL_TRANSFER);
        assertThat(r.getReviewReason()).contains("net worth");
    }

    @Test
    void malformedModelOutput_yieldsUnresolvedNotAGuess() {
        modelReturns("I think this is probably a UPI payment of about 450 rupees.");

        EmailIntelResult r = agent.classify(USER_ID, "x@y.com", "alert", UPI_EMAIL, "m5");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.UNRESOLVED);
        assertThat(r.getParsed()).isNull();
        verify(audit).record(eq(USER_ID), eq("EMAIL_CLASSIFY"), eq("m5"), anyString(), anyString(),
            any(), isNull(), eq("PARSE_FAILED"), anyString());
    }

    @Test
    void modelUnavailable_yieldsUnresolvedAndIsAudited() {
        when(llm.complete(anyString(), anyString())).thenThrow(new LlmUnavailableException("ollama down"));

        EmailIntelResult r = agent.classify(USER_ID, "x@y.com", "alert", UPI_EMAIL, "m6");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.UNRESOLVED);
        verify(audit).recordFailure(eq(USER_ID), eq("EMAIL_CLASSIFY"), eq("m6"), anyString(), anyString(),
            eq("UNAVAILABLE"), anyString());
    }

    @Test
    void confidentTradeWithAnUnresolvableTicker_goesToReviewRatherThanCreatingAPhantomHolding() {
        // "ADANI" is not a real ticker; searching it returns several candidates, so there's no
        // confident correction. Booking it would create a second holding for a stock the user
        // already owns under its real symbol.
        when(marketData.searchStocks("ADANI")).thenReturn(java.util.Arrays.asList(
            stock("ADANIENT"), stock("ADANIPORTS"), stock("ADANIGREEN")));
        modelReturns("{\"classification\":\"STOCK_BUY\",\"confidence\":0.97,"
            + "\"extractedFields\":{\"symbol\":\"ADANI\",\"quantity\":10,\"price\":2500,\"date\":\"2026-03-04\"},"
            + "\"reasoning\":\"contract note\",\"evidence\":\"bought 10 @ 2500\"}");

        EmailIntelResult r = agent.classify(USER_ID, "x@broker.com", "Trade", "You bought 10 ADANI @ 2500", "m7");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.REVIEW_REQUIRED);
        assertThat(r.getReviewReason()).contains("incomplete or unreadable");
    }

    @Test
    void aSingleSearchHitIsAcceptedAsAConfidentTickerCorrection() {
        when(marketData.searchStocks("HDFCBANK")).thenReturn(Collections.singletonList(stock("HDFCBANK")));
        modelReturns("{\"classification\":\"STOCK_BUY\",\"confidence\":0.97,"
            + "\"extractedFields\":{\"symbol\":\"HDFCBANK\",\"quantity\":10,\"price\":1500,\"date\":\"2026-03-04\"},"
            + "\"reasoning\":\"contract note\",\"evidence\":\"bought 10 @ 1500\"}");

        EmailIntelResult r = agent.classify(USER_ID, "x@broker.com", "Trade", "bought 10 HDFCBANK @ 1500", "m8");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.IMPORT);
        assertThat(r.getParsed().getSymbol()).isEqualTo("HDFCBANK");
        assertThat(r.getParsed().getQuantity()).isEqualTo(10);
    }

    @Test
    void promotionalMail_isDiscardedWithoutAReviewRow() {
        modelReturns("{\"classification\":\"PROMOTIONAL\",\"confidence\":0.98,"
            + "\"extractedFields\":{},\"reasoning\":\"marketing\",\"evidence\":\"offer\"}");

        EmailIntelResult r = agent.classify(USER_ID, "x@y.com", "Offer", "Get a loan now! Rs.5,00,000 pre-approved", "m9");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.NOT_A_TRANSACTION);
    }

    @Test
    void nonFinancialMailNeverReachesTheModel() {
        EmailIntelResult r = agent.classify(USER_ID, "team@work.com", "Standup notes", "Let's sync at 10am", "m10");

        assertThat(r.getOutcome()).isEqualTo(EmailIntelResult.Outcome.NOT_A_TRANSACTION);
        verify(llm, never()).complete(anyString(), anyString());
    }

    private Stock stock(String symbol) {
        Stock s = new Stock();
        s.setSymbol(symbol);
        return s;
    }
}
