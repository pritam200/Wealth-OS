package com.marketai.gmail.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.ai.client.GeminiClient;
import com.marketai.gmail.parser.ParsedEmail;
import com.marketai.market.entity.Stock;
import com.marketai.market.service.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the P0 "stocks total ~₹11.8L too high" report: the AI email-extraction
 * fallback was booking trades under whatever symbol the LLM returned with zero validation,
 * and LLMs routinely return the first word of a company name (ADANI for ADANIENT, AXIS for
 * AXISBANK, HERO for HEROMOTOCO, LARSEN for LT, MRS for BECTORFOOD) — each one syntactically a
 * valid-looking ticker, so nothing downstream caught it. Every such case created a SECOND
 * holding for a stock the user already owned under its real ticker, double-counting it.
 */
class AiEmailExtractorSymbolResolutionTest {

    private GeminiClient gemini;
    private MarketDataService marketDataService;
    private AiEmailExtractor extractor;

    @BeforeEach
    void setup() {
        gemini = mock(GeminiClient.class);
        marketDataService = mock(MarketDataService.class);
        extractor = new AiEmailExtractor(gemini, new ObjectMapper(), marketDataService);
    }

    private Stock stock(String symbol, String name) {
        return Stock.builder().symbol(symbol).name(name).exchange("NSE").build();
    }

    private void geminiReturns(String json) {
        when(gemini.generateContent(anyString(), anyString())).thenReturn(json);
    }

    @Test
    @DisplayName("Exact ticker from the LLM passes through unchanged (e.g. LT is genuinely correct, not a fragment)")
    void exactKnownTickerPassesThrough() {
        when(marketDataService.searchStocks("LT")).thenReturn(Collections.singletonList(stock("LT", "Larsen & Toubro")));
        geminiReturns("[{\"type\":\"TRADE_BUY\",\"symbol\":\"LT\",\"price\":4038.20,\"quantity\":12}]");

        List<ParsedEmail> result = extractor.extract("broker@example.com", "Contract note", "bought LT shares ₹4038.20 today");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSymbol()).isEqualTo("LT");
    }

    @Test
    @DisplayName("The actual incident: a company-name fragment resolves to the single real ticker")
    void fragmentResolvesToSingleRealTicker() {
        when(marketDataService.searchStocks("ADANI")).thenReturn(Collections.singletonList(stock("ADANIENT", "Adani Enterprises")));
        geminiReturns("[{\"type\":\"TRADE_BUY\",\"symbol\":\"ADANI\",\"price\":2898.00,\"quantity\":15}]");

        List<ParsedEmail> result = extractor.extract("broker@example.com", "Trade confirmation", "bought Adani shares ₹2898 today");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSymbol()).isEqualTo("ADANIENT");
    }

    @Test
    @DisplayName("Ambiguous resolution (multiple candidates) is rejected outright, never guessed")
    void ambiguousResolutionIsRejected() {
        when(marketDataService.searchStocks("AXIS")).thenReturn(Arrays.asList(
            stock("AXISBANK", "Axis Bank"), stock("AXISCADES", "Axiscades Technologies")));
        geminiReturns("[{\"type\":\"TRADE_BUY\",\"symbol\":\"AXIS\",\"price\":1250.90,\"quantity\":2}]");

        List<ParsedEmail> result = extractor.extract("broker@example.com", "Trade confirmation", "bought Axis shares ₹1250.90 today");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Zero matches is rejected, never booked under the raw unresolved fragment")
    void noMatchIsRejected() {
        when(marketDataService.searchStocks("MRS")).thenReturn(Collections.emptyList());
        geminiReturns("[{\"type\":\"TRADE_BUY\",\"symbol\":\"MRS\",\"price\":229.00,\"quantity\":5}]");

        List<ParsedEmail> result = extractor.extract("broker@example.com", "Trade confirmation", "bought Mrs Bector shares ₹229 today");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Non-trade types (e.g. EXPENSE) are unaffected by symbol resolution")
    void nonTradeTypesUnaffected() {
        geminiReturns("[{\"type\":\"EXPENSE\",\"amount\":499.00,\"merchant\":\"Swiggy\",\"category\":\"Food\"}]");

        List<ParsedEmail> result = extractor.extract("bank@example.com", "Debit alert", "Rs.499 debited for Swiggy order");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo(ParsedEmail.Type.EXPENSE);
        verifyNoInteractions(marketDataService);
    }
}
