package com.marketai.scoring;

import com.marketai.common.quality.DataQuality;
import com.marketai.scoring.eligibility.RatingEligibility;
import com.marketai.scoring.eligibility.RatingEligibilityChecker;
import com.marketai.scoring.factor.BinaryCheck;
import com.marketai.scoring.factor.FactorScore;
import com.marketai.scoring.factor.QualityFactor;
import com.marketai.scoring.redflag.RedFlag;
import com.marketai.scoring.redflag.RedFlagAssessment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringTest {

    private final RatingEligibilityChecker eligibility = new RatingEligibilityChecker();
    private final QualityFactor quality = new QualityFactor();

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    // --- Eligibility ---

    @Test
    @DisplayName("a normal stock is eligible")
    void healthyStockIsEligible() {
        assertThat(eligibility.check(1, 8.0, bd("50000"), false, 60.0).eligible()).isTrue();
    }

    @Test
    @DisplayName("negative net worth disqualifies — otherwise it would score as cheap")
    void negativeNetWorthDisqualifies() {
        // A negative book value makes P/B negative, which sorts as the cheapest stock in the
        // market. That is the exact failure this exclusion prevents.
        RatingEligibility r = eligibility.check(1, 8.0, bd("-1000"), false, 60.0);

        assertThat(r.eligible()).isFalse();
        assertThat(r.explanation()).contains("valuation ratios invert");
    }

    @Test
    void thinHistoryAndStalePricesDisqualify() {
        assertThat(eligibility.check(1, 1.5, bd("1000"), false, 50.0).eligible()).isFalse();
        assertThat(eligibility.check(90, 8.0, bd("1000"), false, 50.0).eligible()).isFalse();
        assertThat(eligibility.check(1, 8.0, bd("1000"), false, 0.4).eligible()).isFalse();
        assertThat(eligibility.check(1, 8.0, bd("1000"), true, 50.0).eligible()).isFalse();
    }

    @Test
    @DisplayName("every exclusion is reported, not just the first")
    void allExclusionsAreListed() {
        RatingEligibility r = eligibility.check(90, 1.0, bd("-500"), true, 0.2);

        assertThat(r.exclusions()).hasSize(5);
    }

    @Test
    void refusingToRateMustSayWhy() {
        assertThatThrownBy(() -> RatingEligibility.deny(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dead end");
    }

    // --- Quality factor ---

    @Test
    @DisplayName("a high-quality business passes its checks, with the numbers shown")
    void strongBusinessScoresWell() {
        FactorScore s = quality.score(bd("0.22"), bd("0.15"), bd("0.18"),
            bd("400"), bd("1000"));

        assertThat(s.score()).isEqualTo(1.0);
        assertThat(s.quality()).isEqualTo(DataQuality.FULL);
        assertThat(s.explanation()).contains("passed 4 of 4");
    }

    @Test
    @DisplayName("a failing check names the number and the threshold")
    void failuresAreExplainedNotScored() {
        FactorScore s = quality.score(bd("0.08"), bd("1.80"), bd("0.55"),
            bd("50"), bd("1000"));

        assertThat(s.score()).isEqualTo(0.0);
        // "quality 0.0" conveys nothing actionable; the failed checks do.
        assertThat(s.explanation())
            .contains("Debt to equity: 180.0%")
            .contains("against a target of below 40%");
    }

    @Test
    @DisplayName("missing ROE excludes the stock rather than scoring it on the rest")
    void missingRoeMakesQualityUnassessable() {
        // Without a profitability anchor the remaining descriptors measure only balance-sheet
        // conservatism — which a dormant company also exhibits.
        FactorScore s = quality.score(null, bd("0.10"), bd("0.10"), bd("400"), bd("1000"));

        assertThat(s.isUsable()).isFalse();
        assertThat(s.unavailableReason()).contains("dormant company");
    }

    @Test
    @DisplayName("an unavailable input counts as a failure, never as a pass")
    void absentDataDoesNotPass() {
        // Treating a missing debt figure as a pass would score a company with undisclosed debt
        // as though it had none.
        FactorScore s = quality.score(bd("0.22"), null, bd("0.10"), bd("400"), bd("1000"));

        assertThat(s.quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(s.checks()).anyMatch(c -> c.isUnavailable() && !c.passed());
        assertThat(s.score()).isLessThan(1.0);
    }

    @Test
    @DisplayName("Novy-Marx gross profitability is gross profit over assets")
    void grossProfitabilityFormula() {
        assertThat(quality.grossProfitToAssets(bd("250"), bd("1000")))
            .isEqualByComparingTo("0.2500");
        assertThat(quality.grossProfitToAssets(bd("250"), BigDecimal.ZERO)).isNull();
        assertThat(quality.grossProfitToAssets(null, bd("1000"))).isNull();
    }

    // --- Red flags ---

    @Test
    @DisplayName("a surveillance flag vetoes regardless of how well the stock scores")
    void surveillanceFlagVetoes() {
        // A cheap valuation must not be able to average away a GSM listing — the stock is often
        // cheap precisely because of it.
        RedFlagAssessment a = new RedFlagAssessment(List.of(
            new RedFlag(RedFlag.Type.GSM, "Stage 2", RedFlag.Severity.BLOCKING)));

        assertThat(a.vetoes()).isTrue();
        assertThat(a.vetoReason()).contains("No recommendation is shown").contains("GSM");
    }

    @Test
    @DisplayName("a promoter pledge is reported but does not veto — it is a risk to accept knowingly")
    void pledgeIsReportedNotBlocking() {
        RedFlagAssessment a = new RedFlagAssessment(List.of(
            new RedFlag(RedFlag.Type.PROMOTER_PLEDGE, "62% pledged", RedFlag.Severity.WARNING)));

        assertThat(a.vetoes()).isFalse();
        assertThat(a.flags()).hasSize(1);
        assertThat(a.vetoReason()).isNull();
    }

    @Test
    void cleanStocksHaveNoFlags() {
        assertThat(RedFlagAssessment.clean().vetoes()).isFalse();
        assertThat(RedFlagAssessment.clean().flags()).isEmpty();
    }

    @Test
    void blockingTypesAreExchangeDeclaredFacts() {
        assertThat(RedFlag.Type.ASM.isBlocking()).isTrue();
        assertThat(RedFlag.Type.ESM.isBlocking()).isTrue();
        assertThat(RedFlag.Type.INSOLVENCY.isBlocking()).isTrue();
        assertThat(RedFlag.Type.TRADE_TO_TRADE.isBlocking()).isTrue();
        // Judgement calls the user may take knowingly.
        assertThat(RedFlag.Type.PROMOTER_PLEDGE.isBlocking()).isFalse();
        assertThat(RedFlag.Type.DISTRESS_SCORE.isBlocking()).isFalse();
    }

    @Test
    void binaryCheckCarriesItsNumbers() {
        BinaryCheck c = BinaryCheck.fail("Return on equity", "8.0%", "at least 15%");

        assertThat(c.passed()).isFalse();
        assertThat(c.explanation()).contains("8.0%").contains("at least 15%");
    }
}
