package com.marketai.research;

import com.marketai.research.service.EvidenceRetriever;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceTierTest {

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "NSE,-,PRIMARY",
            "Some Blog,https://nsearchives.nseindia.com/x.pdf,PRIMARY",
            "Reuters,https://news.google.com/rss/articles/abc,RELIABLE",
            "The Economic Times,-,RELIABLE",
            "reuters.com,https://vertexaisearch.cloud.google.com/grounding-api-redirect/x,RELIABLE",
            "x.com,-,UNVERIFIED",
            "Anyone,https://www.reddit.com/r/IndianStockMarket/1,UNVERIFIED",
            "Local Daily,-,NEWS",
            // suffix matches respect domain boundaries
            "upstox.com,https://news.google.com/rss/articles/abc,NEWS",
            "Someone,https://www.box.com/file,NEWS",
            "Someone,https://mobile.x.com/post/1,UNVERIFIED",
            "-,-,UNVERIFIED",
    })
    void tiers(String source, String url, String tier) {
        assertThat(EvidenceRetriever.tierOf(source, url)).isEqualTo(tier);
    }
}
