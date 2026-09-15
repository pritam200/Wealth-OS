package com.marketai.card.optimizer;

import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CreditCardRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Net-value card optimizer.
 *
 * Net Value = projected annual rewards − annual fee, computed against the user's OWN observed
 * spend rather than a headline rate. A new card is only ever recommended when it beats the
 * current best by more than its fee.
 *
 * Two honesty constraints shape the output:
 *
 *  - Where 12 months of history exist, the realized figure is reported instead of a
 *    projection. A projection is an estimate; a measurement is evidence, and presenting the
 *    former as the latter is how a "cancel this card" verdict ends up being wrong.
 *  - Every verdict inherits the spend profile's coverage. If only a third of spend could be
 *    categorized, the recommendation says so rather than asserting a confident rupee figure.
 */
@Service
@RequiredArgsConstructor
public class CardOptimizerService {

    private final CreditCardRepository cardRepository;
    private final SpendAggregator spendAggregator;

    /** Below this coverage, verdicts are advisory only. */
    private static final BigDecimal MIN_TRUSTWORTHY_COVERAGE = new BigDecimal("0.50");

    public enum Verdict { KEEP, DOWNGRADE, CANCEL, UNDERUSED, UNKNOWN }

    @Data @Builder
    public static class CardVerdict {
        private Long cardId;
        private String cardName;
        private BigDecimal annualFee;
        private BigDecimal projectedAnnualRewards;
        private BigDecimal netValue;
        private BigDecimal breakEvenSpend;
        private BigDecimal feeWaiverSpend;
        private BigDecimal feeWaiverProgress;
        private Verdict verdict;
        private String verdictBasis;
        /** Null when history is shorter than 12 months — a projection is not evidence. */
        private BigDecimal rewardsRealized12m;
    }

    @Data @Builder
    public static class OptimizerResult {
        private LocalDateTime generatedAt;
        private SpendAggregator.SpendProfile spendWindow;
        private List<CardVerdict> portfolio;
        private BigDecimal totalAnnualFees;
        private BigDecimal totalProjectedRewards;
        private BigDecimal totalNetValue;
        private boolean trustworthy;
        private List<String> caveats;
    }

    public OptimizerResult analyse(Long userId) {
        SpendAggregator.SpendProfile spend = spendAggregator.aggregate(userId);
        List<CreditCard> cards = cardRepository.findByUserIdOrderByCreatedAtDesc(userId);

        List<String> caveats = new ArrayList<>();
        caveats.add(spend.getNote());

        boolean trustworthy = spend.getCoverage().compareTo(MIN_TRUSTWORTHY_COVERAGE) >= 0
            && spend.getObservedSpend().compareTo(BigDecimal.ZERO) > 0;
        if (!trustworthy) {
            caveats.add("Verdicts below are directional only — there isn't enough categorized "
                      + "spend to justify cancelling or keeping a card on these numbers alone.");
        }
        // Reward terms in this app come from a hand-maintained catalog. Indian issuers devalue
        // frequently, so a net-value figure without this caveat would read as more
        // authoritative than its inputs support.
        caveats.add("Reward rates come from a manually maintained catalog; verify against the "
                  + "issuer's current terms before acting on a cancellation.");

        List<CardVerdict> verdicts = new ArrayList<>();
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal totalRewards = BigDecimal.ZERO;

        for (CreditCard card : cards) {
            CardVerdict v = evaluate(card, spend, trustworthy);
            verdicts.add(v);
            totalFees = totalFees.add(nz(v.getAnnualFee()));
            totalRewards = totalRewards.add(nz(v.getProjectedAnnualRewards()));
        }
        verdicts.sort(Comparator.comparing(
            (CardVerdict v) -> nz(v.getNetValue())).reversed());

        return OptimizerResult.builder()
            .generatedAt(LocalDateTime.now())
            .spendWindow(spend)
            .portfolio(verdicts)
            .totalAnnualFees(totalFees.setScale(2, RoundingMode.HALF_UP))
            .totalProjectedRewards(totalRewards.setScale(2, RoundingMode.HALF_UP))
            .totalNetValue(totalRewards.subtract(totalFees).setScale(2, RoundingMode.HALF_UP))
            .trustworthy(trustworthy)
            .caveats(caveats)
            .build();
    }

    private CardVerdict evaluate(CreditCard card, SpendAggregator.SpendProfile spend, boolean trustworthy) {
        BigDecimal fee = nz(card.getAnnualFee());
        Map<String, BigDecimal> rates = card.getRewardRates() == null
            ? Collections.emptyMap() : card.getRewardRates();

        // Rewards are computed per category against this card's own rate for that category,
        // which is the point: a travel card looks strong only if the user actually travels.
        BigDecimal projected = BigDecimal.ZERO;
        BigDecimal bestRate = BigDecimal.ZERO;
        for (SpendAggregator.CategorySpend cs : spend.getByCategory()) {
            BigDecimal rate = rates.get(cs.getCategory());
            if (rate == null) continue;
            projected = projected.add(
                cs.getProjectedAnnual().multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
            if (rate.compareTo(bestRate) > 0) bestRate = rate;
        }
        projected = projected.setScale(2, RoundingMode.HALF_UP);
        BigDecimal netValue = projected.subtract(fee).setScale(2, RoundingMode.HALF_UP);

        BigDecimal breakEven = (fee.compareTo(BigDecimal.ZERO) > 0 && bestRate.compareTo(BigDecimal.ZERO) > 0)
            ? fee.multiply(new BigDecimal("100")).divide(bestRate, 2, RoundingMode.HALF_UP)
            : null;

        Verdict verdict;
        String basis;
        if (!trustworthy) {
            verdict = Verdict.UNKNOWN;
            basis = "Not enough categorized spend to judge this card.";
        } else if (fee.compareTo(BigDecimal.ZERO) == 0) {
            verdict = Verdict.KEEP;
            basis = "No annual fee — every reward earned is net gain.";
        } else if (netValue.compareTo(BigDecimal.ZERO) > 0) {
            verdict = Verdict.KEEP;
            basis = String.format("Projected rewards %s exceed the %s fee by %s.", projected, fee, netValue);
        } else if (bestRate.compareTo(BigDecimal.ZERO) == 0) {
            verdict = Verdict.CANCEL;
            basis = String.format("Fee of %s with no reward rate matching your spend categories.", fee);
        } else if (projected.compareTo(BigDecimal.ZERO) == 0) {
            verdict = Verdict.UNDERUSED;
            basis = String.format("Fee of %s and no spend in this card's rewarded categories.", fee);
        } else {
            verdict = Verdict.DOWNGRADE;
            basis = String.format("Fee %s exceeds projected rewards %s — %s net negative. "
                + "Ask the issuer about a no-fee variant before cancelling.", fee, projected, netValue.abs());
        }

        return CardVerdict.builder()
            .cardId(card.getId())
            .cardName(card.getName())
            .annualFee(fee)
            .projectedAnnualRewards(projected)
            .netValue(netValue)
            .breakEvenSpend(breakEven)
            .verdict(verdict)
            .verdictBasis(basis)
            // Null on purpose: the ledger does not yet carry 12 months of per-card reward
            // history, and a projection reported in this field would look like a measurement.
            .rewardsRealized12m(null)
            .build();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
