package com.marketai.market.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketai.market.entity.Stock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class YahooHistoryParseTest {

    @Test
    @DisplayName("Bars with null or zero OHLC are skipped, not stored as ₹0; adjusted close is read")
    void skipsIncompleteBars() throws Exception {
        String json = """
            {"chart":{"result":[{"timestamp":[1759203000,1759289400,1759375800,1759462200],
              "indicators":{"quote":[{"open":[100,null,102,0],"high":[101,103,104,5],"low":[99,101,101,1],
                                      "close":[100.5,102,null,2],"volume":[1000,2000,3000,null]}],
                            "adjclose":[{"adjclose":[99.5,101.2,103.1,1.9]}]}}]}}""";
        List<YahooFinanceClient.OhlcvBar> bars = YahooFinanceClient.parseHistory(new ObjectMapper().readTree(json));
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).close()).isEqualTo(100.5);
        assertThat(bars.get(0).adjClose()).isEqualTo(99.5);
    }

    @Test
    @DisplayName("Debt-to-equity: Yahoo's percentage is read as a ratio, for old and new rows alike")
    void debtToEquityUnits() {
        Stock legacy = Stock.builder().debtToEquity(new BigDecimal("45.30")).build();
        assertThat(legacy.debtToEquityRatio()).isEqualByComparingTo("0.4530");
        Stock current = Stock.builder().debtToEquity(new BigDecimal("0.4530")).fundamentalsVersion(Stock.FUNDAMENTALS_VERSION).build();
        assertThat(current.debtToEquityRatio()).isEqualByComparingTo("0.4530");
        assertThat(Stock.builder().build().debtToEquityRatio()).isNull();
    }
}
