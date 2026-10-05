package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.TransactionType;
import com.marketai.dataplatform.pipeline.Tolerances.Agreement;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether an incoming transaction is another report of one already in the ledger.
 *
 * <p>Deterministic and explainable: every decision carries the list of facts it rests on. The
 * rules, in order:
 * <ol>
 *   <li>The same source re-delivering the same reference is a redelivery.</li>
 *   <li>The same reference from a different source is the same event, provided the asset and
 *       kind of event agree (a reference alone is not trusted across namespaces).</li>
 *   <li>Otherwise the asset, kind of event, date and size must all agree. Equal amounts alone
 *       never match: a ₹10,000 SIP in Fund A is not the ₹10,000 SIP in Fund B.</li>
 *   <li>One source reports an event once. If the only candidate is already backed by the same
 *       source and nothing tells the two apart (no differing references), it is a possible
 *       duplicate for a person to judge, never an automatic merge. Differing references from one
 *       source are proof of two events.</li>
 * </ol>
 */
@Component
public class TransactionMatcher {

    public enum Kind { NONE, SAME_SOURCE_REDELIVERY, EXACT_REFERENCE, COMPOSITE_EXACT, COMPOSITE_TOLERANT, AMBIGUOUS }

    /**
     * @param matchedId       the entry the incoming record is another report of, when {@link #kind} says so
     * @param relatedIds      entries involved but not merged: ambiguous candidates, or same-source rivals
     * @param close           matched, but the figures differ within tolerance
     * @param materialConflict matched by reference, but the figures disagree beyond tolerance
     * @param possibleDuplicate no merge, but an existing entry from the same source looks identical
     */
    public record Decision(Kind kind, Long matchedId, List<Long> relatedIds, boolean close,
                           boolean materialConflict, boolean possibleDuplicate, List<String> explanation) {
        public boolean merges() {
            return kind == Kind.SAME_SOURCE_REDELIVERY || kind == Kind.EXACT_REFERENCE
                || kind == Kind.COMPOSITE_EXACT || kind == Kind.COMPOSITE_TOLERANT;
        }
        public String explanationText() { return String.join("; ", explanation); }
    }

    private static final int REFERENCE_DATE_DAYS = 5;

    public Decision decide(NormalizedTransaction in, Long assetId, Long accountId, boolean accountSpecific,
                           List<LedgerEntryView> pool) {
        return decide(in, assetId, accountId, accountSpecific, pool, false);
    }

    /**
     * @param sameSourceDistinct the caller knows this source's records are already distinct events
     *                           (a migration of rows that passed the old deduplication), so a same-source look-alike is
     *                           a separate transaction rather than a possible duplicate
     */
    public Decision decide(NormalizedTransaction in, Long assetId, Long accountId, boolean accountSpecific,
                           List<LedgerEntryView> pool, boolean sameSourceDistinct) {
        List<String> why = new ArrayList<>();
        List<LedgerEntryView> counting = pool.stream().filter(e -> e.status() == null || e.status().counts()).toList();

        // 1 + 2: references.
        String ref = norm(in.getSourceReference());
        if (ref != null) {
            for (LedgerEntryView e : counting) {
                for (LedgerEntryView.Source s : e.sources()) {
                    if (!ref.equals(norm(s.reference()))) continue;
                    boolean sameSource = s.type() == in.getSourceType() && eqi(s.provider(), in.getSourceProvider());
                    if (sameSource) {
                        why.add("same source " + in.getSourceType() + "/" + in.getSourceProvider() + " delivered reference " + ref + " before");
                        return new Decision(Kind.SAME_SOURCE_REDELIVERY, e.id(), List.of(), false, false, false, why);
                    }
                    if (sanity(in, assetId, e, why)) {
                        why.add("reference " + ref + " already reported by " + s.type());
                        Agreement a = numeric(e, in);
                        boolean conflict = a == Agreement.DISAGREE;
                        if (conflict) why.add("but the figures disagree beyond tolerance");
                        return new Decision(Kind.EXACT_REFERENCE, e.id(), List.of(), a == Agreement.CLOSE, conflict, false, why);
                    }
                }
            }
        }

        // 3 + 4: composite.
        record Scored(LedgerEntryView entry, int score, boolean close, List<String> facts) {}
        List<Scored> scored = new ArrayList<>();
        List<LedgerEntryView> sameSourceRivals = new ArrayList<>();
        for (LedgerEntryView e : counting) {
            if (e.type().family() != in.getType().family()) continue;
            boolean assetOk = assetId != null && assetId.equals(e.assetId());
            boolean cash = assetId == null && e.assetId() == null && isCashFamily(in.getType());
            if (!assetOk && !cash) continue;
            if (!accountCompatible(accountId, accountSpecific, e)) continue;
            long dd = Math.abs(ChronoUnit.DAYS.between(e.date(), in.getTransactionDate()));
            if (dd > Tolerances.DATE_DAYS) continue;
            Agreement a = numeric(e, in);
            if (a == null || a == Agreement.DISAGREE) continue;
            if (dd > 0 && a != Agreement.EXACT) continue;

            List<String> facts = new ArrayList<>();
            facts.add(assetOk ? "same asset" : "cash event, no asset");
            facts.add(in.getType().family() == TransactionType.Family.OTHER ? "same kind" : "same kind of event (" + in.getType().family() + ")");
            facts.add(dd == 0 ? "same date" : "dates " + dd + " day(s) apart");
            facts.add(a == Agreement.EXACT ? "quantity/amount agree" : "quantity/amount agree within tolerance");

            // One source reports an event once. When it gave the earlier entry a reference and gives
            // this record a different one, the source itself is telling them apart (two trade
            // numbers are two trades); when either lacks a reference nothing does, so a person decides.
            List<LedgerEntryView.Source> same = e.sources().stream().filter(s ->
                s.type() == in.getSourceType() && eqi(s.provider(), in.getSourceProvider())).toList();
            if (!same.isEmpty()) {
                boolean provablyDistinct = ref != null && same.stream().allMatch(s -> norm(s.reference()) != null && !ref.equals(norm(s.reference())));
                if (!provablyDistinct && !sameSourceDistinct) sameSourceRivals.add(e);
                continue;
            }
            scored.add(new Scored(e, (dd == 0 ? 50 : 20) + (a == Agreement.EXACT ? 50 : 20), a == Agreement.CLOSE, facts));
        }

        if (!scored.isEmpty()) {
            int best = scored.stream().mapToInt(Scored::score).max().orElse(0);
            List<Scored> top = scored.stream().filter(s -> s.score() == best).toList();
            if (top.size() > 1) {
                why.add(top.size() + " existing transactions match equally well; not merging automatically");
                return new Decision(Kind.AMBIGUOUS, null, top.stream().map(s -> s.entry().id()).toList(), false, false, false, why);
            }
            Scored s = top.get(0);
            why.addAll(s.facts());
            boolean exact = best == 100;
            return new Decision(exact ? Kind.COMPOSITE_EXACT : Kind.COMPOSITE_TOLERANT, s.entry().id(), List.of(),
                s.close() || !exact, false, false, why);
        }
        if (!sameSourceRivals.isEmpty()) {
            why.add("an existing transaction with the same asset, date and size is already reported by the same source ("
                + in.getSourceType() + "); it may be a duplicate or a genuine second transaction");
            return new Decision(Kind.NONE, null, sameSourceRivals.stream().map(LedgerEntryView::id).toList(), false, false, true, why);
        }
        why.add(assetId == null && !isCashFamily(in.getType())
            ? "asset could not be identified, so nothing can be matched"
            : "no existing transaction has the same asset, kind, date and size");
        return new Decision(Kind.NONE, null, List.of(), false, false, false, why);
    }

    /** A reference match is accepted only when the rest of the event is plausibly the same. */
    private boolean sanity(NormalizedTransaction in, Long assetId, LedgerEntryView e, List<String> why) {
        boolean assetOk = Objects.equals(assetId, e.assetId());
        boolean familyOk = in.getType().family() == e.type().family();
        boolean dateOk = Math.abs(ChronoUnit.DAYS.between(e.date(), in.getTransactionDate())) <= REFERENCE_DATE_DAYS;
        if (assetOk && familyOk && dateOk) return true;
        why.add("a reference is shared but " + (!assetOk ? "the asset" : !familyOk ? "the kind of event" : "the date")
            + " differs, so it is not treated as the same event");
        return false;
    }

    private static boolean accountCompatible(Long accountId, boolean accountSpecific, LedgerEntryView e) {
        if (accountId != null && accountId.equals(e.accountId())) return true;
        // A placeholder account (a source that named only the institution) is compatible with a specific one.
        return !accountSpecific || !e.accountSpecific();
    }

    private static boolean isCashFamily(TransactionType t) {
        return switch (t.family()) { case CASH_IN, CASH_OUT, INCOME, TRANSFER -> true; default -> false; };
    }

    /**
     * Compares the figures both sides state. Null means nothing is comparable, so nothing can be
     * matched on size; DISAGREE means a comparable figure differs beyond tolerance.
     */
    static Agreement numeric(LedgerEntryView e, NormalizedTransaction in) {
        Agreement worst = null;
        boolean any = false;
        if (e.quantity() != null && in.getQuantity() != null && e.type().movesUnits() == in.getType().movesUnits()) {
            Agreement q = Tolerances.compare(e.quantity(), in.getQuantity(), Tolerances.QTY_ABS);
            worst = worse(worst, q); any = true;
        }
        BigDecimal best = closestAmountGap(e, in);
        if (best != null) {
            Agreement am = best.compareTo(Tolerances.AMOUNT_ABS) <= 0 ? Agreement.EXACT : relClose(best, e, in) ? Agreement.CLOSE : Agreement.DISAGREE;
            worst = worse(worst, am); any = true;
        }
        if (in.getType().family() == TransactionType.Family.CORPORATE_ACTION && in.getRatioFrom() != null && in.getRatioTo() != null
                && e.ratioFrom() != null && e.ratioTo() != null) {
            boolean same = e.ratioFrom().compareTo(in.getRatioFrom()) == 0 && e.ratioTo().compareTo(in.getRatioTo()) == 0;
            worst = worse(worst, same ? Agreement.EXACT : Agreement.DISAGREE); any = true;
        }
        return any ? worst : null;
    }

    private static BigDecimal closestAmountGap(LedgerEntryView e, NormalizedTransaction in) {
        BigDecimal best = null;
        for (BigDecimal a : new BigDecimal[]{e.netAmount(), e.grossAmount()}) {
            for (BigDecimal b : new BigDecimal[]{in.getNetAmount(), in.getGrossAmount()}) {
                if (a == null || b == null) continue;
                BigDecimal gap = a.subtract(b).abs();
                if (best == null || gap.compareTo(best) < 0) best = gap;
            }
        }
        return best;
    }

    private static boolean relClose(BigDecimal gap, LedgerEntryView e, NormalizedTransaction in) {
        BigDecimal scale = BigDecimal.ZERO;
        for (BigDecimal v : new BigDecimal[]{e.netAmount(), e.grossAmount(), in.getNetAmount(), in.getGrossAmount()})
            if (v != null) scale = scale.max(v.abs());
        return scale.signum() > 0 && gap.divide(scale, 8, java.math.RoundingMode.HALF_UP).compareTo(Tolerances.REL) <= 0;
    }

    private static Agreement worse(Agreement a, Agreement b) {
        if (a == null) return b;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static String norm(String s) { return s == null || s.isBlank() ? null : s.trim().toUpperCase(); }
    private static boolean eqi(String a, String b) { return a == null ? b == null : a.equalsIgnoreCase(b); }
}
