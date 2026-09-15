package com.marketai.card.counter;

import com.marketai.card.route.CardNetwork;
import com.marketai.card.route.PaymentRoute;
import com.marketai.card.route.RouteEligibility;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Values a card for one specific purchase, at the margin rather than on average.
 *
 * <p>The research conclusion this implements: the right answer to "which card for this ₹X?" is
 *
 * <pre>
 *   argmax of  (capped category rate)
 *            + Δ(probability-weighted milestone value)
 *            + Δ(fee-waiver value)
 *            − surcharge
 * </pre>
 *
 * <p>The consequence is counter-intuitive and is the point: in December, a card whose reward
 * caps are exhausted but which is ₹40,000 short of a ₹2,00,000 fee waiver is the correct
 * recommendation <b>at a 0% reward rate</b>, because spending on it is worth the whole annual
 * fee. No static rate table can express that, which is why every competitor that ranks on rate
 * gets this case wrong.
 */
@Component
public class MarginalValueEngine {

    public record Valuation(boolean eligible, String ineligibleReason,
                            BigDecimal rewardValue, BigDecimal milestoneValue,
                            BigDecimal feeWaiverValue, BigDecimal surcharge,
                            BigDecimal total, String explanation) {

        public static Valuation ineligible(String reason) {
            return new Valuation(false, reason, null, null, null, null, BigDecimal.ZERO, reason);
        }
    }

    /**
     * @param acceleratedRate rate within the category cap, as a fraction
     * @param baseRate        rate once the cap is exhausted
     * @param rewardCap       the category counter, null when uncapped
     * @param feeWaiver       progress toward the annual-fee waiver, null when not applicable
     * @param annualFee       the fee that waiver would avoid
     */
    public Valuation value(CardNetwork network, PaymentRoute route, String mcc, String txnType,
                           BigDecimal spend,
                           BigDecimal acceleratedRate, BigDecimal baseRate,
                           UtilizationCounter rewardCap,
                           UtilizationCounter feeWaiver, BigDecimal annualFee,
                           BigDecimal surchargeRate) {

        RouteEligibility.Verdict verdict =
            RouteEligibility.check(network, route, mcc, txnType, spend);
        if (!verdict.eligible()) {
            // A hard gate, evaluated before any reward maths. Ranking an unusable card by its
            // reward rate produces impossible advice, not merely imperfect advice.
            return Valuation.ineligible(verdict.reason());
        }

        BigDecimal accelerated = rewardCap == null ? spend : rewardCap.acceleratedPortion(spend);
        BigDecimal atBaseRate = spend.subtract(accelerated);

        BigDecimal rewardValue = accelerated.multiply(nz(acceleratedRate))
            .add(atBaseRate.multiply(nz(baseRate)))
            .setScale(2, RoundingMode.HALF_UP);

        // Fee-waiver value: only the portion of this spend that actually closes the remaining
        // gap is worth anything, and it is worth the whole fee it avoids.
        BigDecimal feeWaiverValue = BigDecimal.ZERO;
        if (feeWaiver != null && annualFee != null && feeWaiver.remaining() != null) {
            BigDecimal gap = feeWaiver.remaining();
            if (gap.signum() > 0) {
                BigDecimal closes = spend.min(gap);
                feeWaiverValue = annualFee.multiply(closes)
                    .divide(gap, 2, RoundingMode.HALF_UP);
            }
        }

        BigDecimal surcharge = spend.multiply(nz(surchargeRate)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = rewardValue.add(feeWaiverValue).subtract(surcharge);

        StringBuilder why = new StringBuilder();
        why.append("₹").append(rewardValue.toPlainString()).append(" in rewards");
        if (atBaseRate.signum() > 0) {
            why.append(" (₹").append(atBaseRate.toPlainString())
               .append(" of this spend is past the cap and earns the base rate)");
        }
        if (feeWaiverValue.signum() > 0) {
            why.append("; ₹").append(feeWaiverValue.toPlainString())
               .append(" of annual-fee waiver progress");
        }
        if (surcharge.signum() > 0) {
            why.append("; less ₹").append(surcharge.toPlainString()).append(" surcharge");
        }

        return new Valuation(true, null, rewardValue, BigDecimal.ZERO, feeWaiverValue,
            surcharge, total.setScale(2, RoundingMode.HALF_UP), why.toString());
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
