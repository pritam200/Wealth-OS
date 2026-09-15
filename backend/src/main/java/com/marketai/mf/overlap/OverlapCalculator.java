package com.marketai.mf.overlap;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted portfolio overlap: {@code Σ min(w_A,i, w_B,i)} over commonly held securities.
 *
 * <p>This is the histogram-intersection measure — bounded [0,100], symmetric — and it is what
 * Kuvera documents: overlap is "the sum of the lower weightings of every common stock held
 * between two funds".
 *
 * <p><b>The methodology is published with every result on purpose.</b> Indian tools disagree
 * about what "overlap" means: Advisorkhoj reports a <em>count</em> of common stocks with no
 * stated methodology, which treats a 1% position identically to a 10% one. Our number will
 * legitimately differ from theirs, and a user comparing the two deserves to know why rather than
 * concluding one of us is broken.
 *
 * <p>Weights are percentages of NAV as disclosed, so cash and debt sit in the denominator. Two
 * funds each holding 8% cash can therefore never exceed 92% overlap. That is reported rather
 * than normalised away, because the raw figure reflects real rupee duplication — which is the
 * question the user is actually asking.
 */
@Component
public class OverlapCalculator {

    private static final String METHODOLOGY =
        "Overlap is the sum of the smaller weight of each commonly held stock "
            + "(Σ min(wA, wB)), matched on ISIN, using each scheme's disclosed percentage of NAV. "
            + "Tools that count shared stocks instead will report a different number.";

    public OverlapResult overlap(List<SchemeHolding> a, List<SchemeHolding> b,
                                 LocalDate asOf, LocalDate today) {
        Map<String, SchemeHolding> byIsinB = new HashMap<>();
        for (SchemeHolding h : nz(b)) {
            if (h.isin() != null) byIsinB.put(h.isin().trim().toUpperCase(), h);
        }

        List<OverlapResult.CommonHolding> common = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (SchemeHolding ha : nz(a)) {
            if (ha.isin() == null) continue;
            SchemeHolding hb = byIsinB.get(ha.isin().trim().toUpperCase());
            if (hb == null) continue;

            BigDecimal wa = nzw(ha.pctToNav());
            BigDecimal wb = nzw(hb.pctToNav());
            BigDecimal contribution = wa.min(wb);
            if (contribution.signum() <= 0) continue;

            total = total.add(contribution);
            common.add(new OverlapResult.CommonHolding(
                ha.isin().trim().toUpperCase(),
                ha.instrumentName() != null ? ha.instrumentName() : hb.instrumentName(),
                wa, wb, contribution));
        }

        int stale = asOf == null || today == null ? Integer.MAX_VALUE
            : (int) ChronoUnit.DAYS.between(asOf, today);

        return new OverlapResult(total.setScale(2, RoundingMode.HALF_UP),
            List.copyOf(common), asOf, stale, METHODOLOGY);
    }

    /**
     * Effective exposure to one stock across directly-held shares and fund look-through — the
     * Morningstar X-Ray model.
     *
     * <pre>
     *   EffectiveExposure(S) = DirectValue(S) + Σ_f ( Value(f) × w_f,S )
     * </pre>
     *
     * <p>This is the case no Indian tool covers. Tickertape compares funds to funds; smallcase
     * has no overlap tooling at all. Nobody joins directly-held equity to fund holdings — which
     * is exactly the position of a user who holds both, and exactly how real concentration hides.
     *
     * @param fundValues     rupee value held in each scheme, by scheme code
     * @param fundHoldings   each scheme's disclosed holdings, by scheme code
     */
    public BigDecimal effectiveExposure(String isin, BigDecimal directValue,
                                        Map<String, BigDecimal> fundValues,
                                        Map<String, List<SchemeHolding>> fundHoldings) {
        BigDecimal total = directValue == null ? BigDecimal.ZERO : directValue;
        if (isin == null || fundValues == null || fundHoldings == null) return total;

        String target = isin.trim().toUpperCase();

        for (Map.Entry<String, BigDecimal> e : fundValues.entrySet()) {
            List<SchemeHolding> holdings = fundHoldings.get(e.getKey());
            if (holdings == null || e.getValue() == null) continue;

            for (SchemeHolding h : holdings) {
                if (h.isin() == null || !target.equals(h.isin().trim().toUpperCase())) continue;
                // % of NAV → fraction of the rupee value held in that scheme.
                total = total.add(e.getValue()
                    .multiply(nzw(h.pctToNav()))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private static <T> List<T> nz(List<T> l) { return l == null ? List.of() : l; }
    private static BigDecimal nzw(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
