package com.marketai.scoring.factor;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The quality dimension — absent entirely from the previous model.
 *
 * <p>The old composite weighted technical 30, momentum 20, valuation 20 and sentiment 30. It had
 * no quality factor and no growth factor: the two things that most determine whether a business
 * is worth owning were simply not measured, while 30% went to the softest available signal.
 * Value Research, the most methodologically transparent Indian rater, weights Quality at 25 and
 * does not use sentiment as a rating input at all.
 *
 * <p>Implements MSCI's three published descriptors — ROE, debt-to-equity, and five-year earnings
 * variability — because it is the cheapest defensible quality factor in existence, needing only
 * three ratios. Novy-Marx's gross-profits-to-assets is added alongside as the single best
 * profitability measure: <em>"the cleanest accounting measure of true economic profitability.
 * The farther down the income statement one goes, the more polluted profitability measures
 * become."</em> A company expensing R&D or advertising — value-creating choices — shows lower
 * earnings and ROE than a less productive competitor, and gross profit sits above those lines.
 */
@Component
public class QualityFactor {

    /** MSCI winsorises at the 5th/95th percentile before standardising. */
    public static final double WINSOR_LOW = 5.0;
    public static final double WINSOR_HIGH = 95.0;

    /**
     * @param roe                 return on equity, as a fraction (0.18 = 18%)
     * @param debtToEquity        total debt ÷ book value, as a fraction
     * @param earningsVariability standard deviation of YoY EPS growth over five years
     * @param grossProfit         revenue − COGS
     * @param totalAssets         for Novy-Marx GP/A
     */
    public FactorScore score(BigDecimal roe, BigDecimal debtToEquity,
                             BigDecimal earningsVariability,
                             BigDecimal grossProfit, BigDecimal totalAssets) {

        // MSCI excludes a security outright when ROE is missing, rather than scoring it on the
        // remaining descriptors — without a profitability anchor the other two measure only
        // balance-sheet conservatism, which a dormant company also exhibits.
        if (roe == null) {
            return FactorScore.unavailable("QUALITY",
                "Return on equity is not available, and the other quality descriptors measure "
                    + "only balance-sheet conservatism without it — which a dormant company "
                    + "also exhibits");
        }

        List<BinaryCheck> checks = new ArrayList<>();

        checks.add(roe.compareTo(new BigDecimal("0.15")) >= 0
            ? BinaryCheck.pass("Return on equity", pct(roe), "at least 15%")
            : BinaryCheck.fail("Return on equity", pct(roe), "at least 15%"));

        checks.add(debtToEquity == null
            ? BinaryCheck.unavailable("Debt to equity", "below 40%")
            : debtToEquity.compareTo(new BigDecimal("0.40")) <= 0
                ? BinaryCheck.pass("Debt to equity", pct(debtToEquity), "below 40%")
                : BinaryCheck.fail("Debt to equity", pct(debtToEquity), "below 40%"));

        checks.add(earningsVariability == null
            ? BinaryCheck.unavailable("Earnings stability", "5-year EPS growth SD below 30%")
            : earningsVariability.compareTo(new BigDecimal("0.30")) <= 0
                ? BinaryCheck.pass("Earnings stability", pct(earningsVariability), "SD below 30%")
                : BinaryCheck.fail("Earnings stability", pct(earningsVariability), "SD below 30%"));

        BigDecimal gpa = grossProfitToAssets(grossProfit, totalAssets);
        checks.add(gpa == null
            ? BinaryCheck.unavailable("Gross profitability", "at least 20% of assets")
            : gpa.compareTo(new BigDecimal("0.20")) >= 0
                ? BinaryCheck.pass("Gross profitability", pct(gpa), "at least 20% of assets")
                : BinaryCheck.fail("Gross profitability", pct(gpa), "at least 20% of assets"));

        return FactorScore.of("QUALITY", checks);
    }

    /** Novy-Marx: GP/A = (revenue − COGS) / total assets. */
    public BigDecimal grossProfitToAssets(BigDecimal grossProfit, BigDecimal totalAssets) {
        if (grossProfit == null || totalAssets == null
                || totalAssets.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return grossProfit.divide(totalAssets, 4, RoundingMode.HALF_UP);
    }

    private static String pct(BigDecimal fraction) {
        return fraction.multiply(BigDecimal.valueOf(100))
            .setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
