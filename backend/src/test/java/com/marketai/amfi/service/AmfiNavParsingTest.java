package com.marketai.amfi.service;

import com.marketai.amfi.dto.AmfiNavResult;
import com.marketai.amfi.dto.MfCategoryBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the sectioned structure of AMFI's NAVAll.txt: category and AMC lines carry no
 * semicolons and used to be discarded, taking the fund's category and fund house with them.
 */
class AmfiNavParsingTest {

    private AmfiNavService service;

    @BeforeEach
    void setup() {
        service = new AmfiNavService(null); // parse() does no I/O
    }

    private static final String SAMPLE =
        "Scheme Code;ISIN Div Payout/ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date\n" +
        "\n" +
        "Open Ended Schemes(Equity Scheme - Large Cap Fund)\n" +
        "\n" +
        "SBI Mutual Fund\n" +
        "\n" +
        "119598;INF209K01157;-;SBI Blue Chip Fund - Direct Plan - Growth;85.4321;07-Aug-2026\n" +
        "\n" +
        "HDFC Mutual Fund\n" +
        "\n" +
        "101234;INF179K01BE2;-;HDFC Top 100 Fund - Regular Plan - Growth;1050.2500;07-Aug-2026\n" +
        "\n" +
        "Close Ended Schemes(Income)\n" +
        "\n" +
        "ICICI Prudential Mutual Fund\n" +
        "\n" +
        "102222;INF109K01AA1;-;ICICI Pru FMP Series 80;12.3456;07-Aug-2026\n";

    @Test
    @DisplayName("Category, scheme type and AMC are attached to every scheme row that follows them")
    void parse_attachesCategoryAndAmc() {
        List<AmfiNavResult> out = service.parse(SAMPLE);

        assertThat(out).hasSize(3);

        AmfiNavResult sbi = out.get(0);
        assertThat(sbi.getSchemeCode()).isEqualTo("119598");
        assertThat(sbi.getSchemeName()).isEqualTo("SBI Blue Chip Fund - Direct Plan - Growth");
        assertThat(sbi.getCategory()).isEqualTo("Equity Scheme - Large Cap Fund");
        assertThat(sbi.getSchemeType()).isEqualTo("Open Ended");
        assertThat(sbi.getAmc()).isEqualTo("SBI Mutual Fund");
        assertThat(sbi.getCategoryBucket()).isEqualTo(MfCategoryBucket.LARGE_CAP);
    }

    @Test
    @DisplayName("A second AMC under the same category keeps the category but switches fund house")
    void parse_amcSwitchesWithinCategory() {
        AmfiNavResult hdfc = service.parse(SAMPLE).get(1);

        assertThat(hdfc.getAmc()).isEqualTo("HDFC Mutual Fund");
        assertThat(hdfc.getCategory()).isEqualTo("Equity Scheme - Large Cap Fund");
        assertThat(hdfc.getSchemeType()).isEqualTo("Open Ended");
    }

    @Test
    @DisplayName("A new category section resets both the category and the fund house")
    void parse_newCategoryResetsAmc() {
        AmfiNavResult icici = service.parse(SAMPLE).get(2);

        assertThat(icici.getSchemeType()).isEqualTo("Close Ended");
        assertThat(icici.getCategory()).isEqualTo("Income");
        assertThat(icici.getAmc()).isEqualTo("ICICI Prudential Mutual Fund");
        // "Income" alone does not identify a bucket — we say OTHER rather than guess DEBT.
        assertThat(icici.getCategoryBucket()).isEqualTo(MfCategoryBucket.OTHER);
    }

    @Test
    @DisplayName("The column header line is not mistaken for a scheme row")
    void parse_skipsHeaderRow() {
        assertThat(service.parse(SAMPLE))
                .extracting(AmfiNavResult::getSchemeCode)
                .doesNotContain("Scheme Code");
    }

    @Test
    @DisplayName("A category line without parentheses keeps its raw text as the category")
    void parse_categoryWithoutParentheses() {
        String raw =
            "Open Ended Schemes\n" +
            "Nippon India Mutual Fund\n" +
            "100111;INF204K01234;-;Nippon India Growth Fund - Direct - Growth;95.1000;07-Aug-2026\n";

        AmfiNavResult r = service.parse(raw).get(0);
        assertThat(r.getCategory()).isEqualTo("Open Ended Schemes");
        assertThat(r.getSchemeType()).isEqualTo("Open Ended");
        assertThat(r.getAmc()).isEqualTo("Nippon India Mutual Fund");
    }

    @Test
    @DisplayName("Rows before any category/AMC header get null rather than a borrowed value")
    void parse_noHeaderYieldsNulls() {
        String raw = "119598;INF209K01157;-;Some Fund - Direct - Growth;85.4321;07-Aug-2026\n";

        AmfiNavResult r = service.parse(raw).get(0);
        assertThat(r.getCategory()).isNull();
        assertThat(r.getAmc()).isNull();
        assertThat(r.getCategoryBucket()).isEqualTo(MfCategoryBucket.OTHER);
    }

    @Test
    @DisplayName("categoryBucket maps the SEBI categories, preferring the more specific label")
    void categoryBucket_mapping() {
        assertThat(MfCategoryBucket.from("Equity Scheme - Large Cap Fund")).isEqualTo(MfCategoryBucket.LARGE_CAP);
        assertThat(MfCategoryBucket.from("Equity Scheme - Mid Cap Fund")).isEqualTo(MfCategoryBucket.MID_CAP);
        assertThat(MfCategoryBucket.from("Equity Scheme - Small Cap Fund")).isEqualTo(MfCategoryBucket.SMALL_CAP);
        assertThat(MfCategoryBucket.from("Equity Scheme - Flexi Cap Fund")).isEqualTo(MfCategoryBucket.FLEXI_CAP);
        assertThat(MfCategoryBucket.from("Equity Scheme - Multi Cap Fund")).isEqualTo(MfCategoryBucket.MULTI_CAP);
        assertThat(MfCategoryBucket.from("Equity Scheme - ELSS")).isEqualTo(MfCategoryBucket.ELSS);
        assertThat(MfCategoryBucket.from("Equity Scheme - Sectoral/ Thematic")).isEqualTo(MfCategoryBucket.SECTORAL_THEMATIC);
        assertThat(MfCategoryBucket.from("Hybrid Scheme - Aggressive Hybrid Fund")).isEqualTo(MfCategoryBucket.HYBRID);
        assertThat(MfCategoryBucket.from("Debt Scheme - Corporate Bond Fund")).isEqualTo(MfCategoryBucket.DEBT);
        assertThat(MfCategoryBucket.from("Other Scheme - Index Funds")).isEqualTo(MfCategoryBucket.INDEX);
    }

    @Test
    @DisplayName("'Large & Mid Cap' is not swallowed by the Large Cap rule")
    void categoryBucket_largeAndMidBeatsLargeCap() {
        assertThat(MfCategoryBucket.from("Equity Scheme - Large & Mid Cap Fund"))
                .isEqualTo(MfCategoryBucket.LARGE_AND_MID_CAP);
        assertThat(MfCategoryBucket.from("Equity Scheme - Large and Mid Cap Fund"))
                .isEqualTo(MfCategoryBucket.LARGE_AND_MID_CAP);
    }

    @Test
    @DisplayName("Gold/Silver ETFs are commodities, not index funds")
    void categoryBucket_goldEtfBeatsIndex() {
        assertThat(MfCategoryBucket.from("Other Scheme - Gold ETF")).isEqualTo(MfCategoryBucket.GOLD_SILVER);
        assertThat(MfCategoryBucket.from("Other Scheme - Silver ETF")).isEqualTo(MfCategoryBucket.GOLD_SILVER);
    }

    @Test
    @DisplayName("Liquid funds separate from the rest of the debt sweep; overseas FoFs are international")
    void categoryBucket_liquidAndOverseas() {
        assertThat(MfCategoryBucket.from("Debt Scheme - Liquid Fund")).isEqualTo(MfCategoryBucket.LIQUID);
        assertThat(MfCategoryBucket.from("Debt Scheme - Overnight Fund")).isEqualTo(MfCategoryBucket.DEBT);
        assertThat(MfCategoryBucket.from("Other Scheme - FoF Overseas")).isEqualTo(MfCategoryBucket.INTERNATIONAL);
    }

    @Test
    @DisplayName("Unclear categories return OTHER instead of a plausible guess")
    void categoryBucket_unclearIsOther() {
        assertThat(MfCategoryBucket.from("Equity Scheme - Value Fund")).isEqualTo(MfCategoryBucket.OTHER);
        assertThat(MfCategoryBucket.from("Equity Scheme - Focused Fund")).isEqualTo(MfCategoryBucket.OTHER);
        assertThat(MfCategoryBucket.from("Solution Oriented Scheme - Retirement Fund")).isEqualTo(MfCategoryBucket.OTHER);
        assertThat(MfCategoryBucket.from("Growth")).isEqualTo(MfCategoryBucket.OTHER);
        assertThat(MfCategoryBucket.from(null)).isEqualTo(MfCategoryBucket.OTHER);
        assertThat(MfCategoryBucket.from("   ")).isEqualTo(MfCategoryBucket.OTHER);
    }
}
