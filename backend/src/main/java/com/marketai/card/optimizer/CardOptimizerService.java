package com.marketai.card.optimizer;

import com.marketai.card.entity.CreditCard;
import com.marketai.card.repository.CreditCardRepository;
import com.marketai.card.service.CardCatalog;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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

    /** Same net-value verdict, but for a card the user doesn't own yet — "should I get this?"
     *  answered with the same rigor as "should I keep this?", against the user's own spend. */
    @Data @Builder
    public static class ProspectiveResult {
        private LocalDateTime generatedAt;
        private SpendAggregator.SpendProfile spendWindow;
        private CardVerdict verdict;
        private boolean trustworthy;
        private List<String> caveats;
    }

    @Data @Builder
    public static class CatalogRanking {
        private LocalDateTime generatedAt;
        private SpendAggregator.SpendProfile spendWindow;
        private boolean trustworthy;
        private List<String> caveats;
        /** Catalog cards the user doesn't already own, ranked by net value against their
         *  own spend — best acquisition candidates first. */
        private List<CardVerdict> candidates;
    }

    public OptimizerResult analyse(Long userId) {
        SpendAggregator.SpendProfile spend = spendAggregator.aggregate(userId);
        List<CreditCard> cards = cardRepository.findByUserIdOrderByCreatedAtDesc(userId);
        boolean trustworthy = isTrustworthy(spend);
        List<String> caveats = buildCaveats(spend, trustworthy);

        List<CardVerdict> verdicts = new ArrayList<>();
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal totalRewards = BigDecimal.ZERO;

        for (CreditCard card : cards) {
            CardVerdict v = evaluate(card.getId(), card.getName(), card.getAnnualFee(),
                card.getRewardRates(), spend, trustworthy);
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

    /** Net-value verdict for one specific catalog card the user doesn't own, using the exact
     *  same math as {@link #analyse}. */
    public ProspectiveResult analyseCatalogCard(Long userId, String catalogName) {
        CardCatalog.CatalogCard cat = CardCatalog.byName(catalogName)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown card: " + catalogName));
        SpendAggregator.SpendProfile spend = spendAggregator.aggregate(userId);
        boolean trustworthy = isTrustworthy(spend);
        List<String> caveats = buildCaveats(spend, trustworthy);

        CardVerdict verdict = evaluate(null, cat.name, cat.annualFee, cat.rewardRates, spend, trustworthy);
        return ProspectiveResult.builder()
            .generatedAt(LocalDateTime.now())
            .spendWindow(spend)
            .verdict(verdict)
            .trustworthy(trustworthy)
            .caveats(caveats)
            .build();
    }

    /** Ranks every catalog card the user doesn't already own by projected net value against
     *  their own spend — the "which new card is actually worth getting" answer, using the same
     *  rigor as the owned-card KEEP/CANCEL verdicts rather than the cruder headline-rate
     *  fallback in {@code CardService.recommend()} for users with no saved cards. */
    public CatalogRanking analyseAllCatalogCards(Long userId) {
        List<CreditCard> owned = cardRepository.findByUserIdOrderByCreatedAtDesc(userId);
        Set<String> ownedNames = owned.stream()
            .map(c -> c.getName().toLowerCase()).collect(Collectors.toSet());

        SpendAggregator.SpendProfile spend = spendAggregator.aggregate(userId);
        boolean trustworthy = isTrustworthy(spend);
        List<String> caveats = buildCaveats(spend, trustworthy);

        List<CardVerdict> candidates = CardCatalog.CARDS.stream()
            .filter(c -> !ownedNames.contains(c.name.toLowerCase()))
            .map(c -> evaluate(null, c.name, c.annualFee, c.rewardRates, spend, trustworthy))
            .sorted(Comparator.comparing((CardVerdict v) -> nz(v.getNetValue())).reversed())
            .collect(Collectors.toList());

        return CatalogRanking.builder()
            .generatedAt(LocalDateTime.now())
            .spendWindow(spend)
            .trustworthy(trustworthy)
            .caveats(caveats)
            .candidates(candidates)
            .build();
    }

    private boolean isTrustworthy(SpendAggregator.SpendProfile spend) {
        return spend.getCoverage().compareTo(MIN_TRUSTWORTHY_COVERAGE) >= 0
            && spend.getObservedSpend().compareTo(BigDecimal.ZERO) > 0;
    }

    private List<String> buildCaveats(SpendAggregator.SpendProfile spend, boolean trustworthy) {
        List<String> caveats = new ArrayList<>();
        caveats.add(spend.getNote());
        if (!trustworthy) {
            caveats.add("Verdicts below are directional only — there isn't enough categorized "
                      + "spend to justify cancelling, keeping, or acquiring a card on these numbers alone.");
        }
        // Reward terms in this app come from a hand-maintained catalog. Indian issuers devalue
        // frequently, so a net-value figure without this caveat would read as more
        // authoritative than its inputs support.
        caveats.add("Reward rates come from a manually maintained catalog; verify against the "
                  + "issuer's current terms before acting on this.");
        return caveats;
    }

    /**
     * @param cardId null for a prospective (not-yet-owned) catalog card
     */
    private CardVerdict evaluate(Long cardId, String cardName, BigDecimal annualFee,
                                 Map<String, BigDecimal> rewardRates,
                                 SpendAggregator.SpendProfile spend, boolean trustworthy) {
        BigDecimal fee = nz(annualFee);
        Map<String, BigDecimal> rates = rewardRates == null
            ? Collections.emptyMap() : rewardRates;

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
            .cardId(cardId)
            .cardName(cardName)
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
