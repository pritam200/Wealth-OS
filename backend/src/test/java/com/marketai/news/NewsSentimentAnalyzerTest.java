package com.marketai.news;

import com.marketai.news.service.NewsSentimentAnalyzer;
import com.marketai.news.service.NewsSentimentAnalyzer.Headline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsSentimentAnalyzerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 30, 12, 0);

    private static Headline h(String t, int daysAgo) {
        return new Headline(t, "https://example.test/" + t.hashCode(), "Test", NOW.minusDays(daysAgo));
    }

    @Test
    @DisplayName("Whole words only: 'Bank' is not 'ban', 'Enterprises' is not 'rise', 'Finance' is not 'fine'")
    void wordBoundaries() {
        var r = NewsSentimentAnalyzer.analyse(List.of(
                h("HDFC Bank opens new branches in Pune", 1),
                h("HDFC Bank Finance arm names new CFO", 2),
                h("HDFC Bank Enterprises unit update", 3)), List.of("HDFC Bank"), NOW);
        assertThat(r.negative()).isZero();
        assertThat(r.positive()).isZero();
        assertThat(r.score()).isZero();
    }

    @Test
    @DisplayName("Old, undated, duplicate and unrelated headlines are excluded and counted")
    void exclusions() {
        var r = NewsSentimentAnalyzer.analyse(List.of(
                h("Infosys profit jumps 12% in Q2", 1),
                h("Infosys profit jumps 12% in Q2 - Economic Times", 1),
                h("Infosys shares fall after guidance", 40),
                new Headline("Infosys wins large deal", null, "X", null),
                h("Sensex rallies 500 points", 1),
                h("Infosys bags order from European bank", 2),
                h("Infosys shares drop on weak outlook", 3)), List.of("Infosys"), NOW);
        assertThat(r.excludedDuplicate()).isEqualTo(1);
        assertThat(r.excludedOld()).isEqualTo(1);
        assertThat(r.excludedUndated()).isEqualTo(1);
        assertThat(r.excludedUnrelated()).isEqualTo(1);
        assertThat(r.considered()).isEqualTo(3);
        assertThat(r.positive()).isEqualTo(2);
        assertThat(r.negative()).isEqualTo(1);
        assertThat(r.counted()).extracting(c -> c.event()).contains("RESULTS", "ORDER_OR_CONTRACT");
    }

    @Test
    @DisplayName("Fewer than 3 usable headlines: no score")
    void insufficient() {
        var r = NewsSentimentAnalyzer.analyse(List.of(h("TCS shares surge", 1)), List.of("TCS"), NOW);
        assertThat(r.status()).isEqualTo("INSUFFICIENT");
        assertThat(r.score()).isNull();
    }
}
