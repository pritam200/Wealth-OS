package com.marketai.tax.lot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays a holding's buys and sells first-in-first-out — the statutory default for units —
 * and reports both what each sale realised, split short-term / long-term by the lot it
 * consumed, and which lots are still open.
 *
 * <p>This is the one FIFO replay. Realised gains for tax and the open lots a what-if sale is
 * priced against both come from here, so the two can never disagree about which units were
 * sold. Weighted-average cost stays what the portfolio shows as "what this position cost";
 * tax needs the lots.
 */
public final class FifoLedger {

    /**
     * One buy or sell, in the order it happened — or a split, when {@code splitMultiplier} is
     * set (units and price are then ignored). A bonus allotment is a buy at nil cost.
     */
    public record Trade(String id, LocalDate date, boolean buy, BigDecimal units, BigDecimal pricePerUnit,
                        BigDecimal splitMultiplier) {
        public Trade(String id, LocalDate date, boolean buy, BigDecimal units, BigDecimal pricePerUnit) {
            this(id, date, buy, units, pricePerUnit, null);
        }

        public static Trade split(String id, LocalDate date, BigDecimal multiplier) {
            return new Trade(id, date, false, null, null, multiplier);
        }

        /** The one mapping from a ledger row, so every replay reads the ledger the same way. */
        public static Trade of(com.marketai.portfolio.entity.Transaction t) {
            String id = "TXN-" + t.getId();
            BigDecimal m = t.splitMultiplier();
            if (m != null) return split(id, t.getTransactionDate(), m);
            if (t.getType() == com.marketai.portfolio.entity.Transaction.TransactionType.SPLIT) {
                return new Trade(id, t.getTransactionDate(), false, null, null, null); // unusable ratio: skipped
            }
            return new Trade(id, t.getTransactionDate(), t.addsUnits(), t.getQuantity(), netPrice(t));
        }
    }

    /**
     * Price per unit net of the trade's charges: brokerage and the like are part of what a
     * purchase cost and come off what a sale fetched (s.48). STT is not, and is kept out of
     * {@code charges} by the importer.
     */
    static BigDecimal netPrice(com.marketai.portfolio.entity.Transaction t) {
        BigDecimal p = t.getPrice();
        BigDecimal c = t.getCharges();
        BigDecimal q = t.getQuantity();
        if (p == null || c == null || c.signum() <= 0 || q == null || q.signum() <= 0) return p;
        BigDecimal perUnit = c.divide(q, 10, RoundingMode.HALF_UP);
        return t.addsUnits() ? p.add(perUnit) : p.subtract(perUnit).max(BigDecimal.ZERO);
    }

    /** The part of one sale that consumed one lot. */
    public record Disposal(String saleId, LocalDate soldOn, String lotId, LocalDate acquiredOn,
                           BigDecimal units, BigDecimal proceeds, BigDecimal cost, boolean longTerm) {
        public BigDecimal gain() { return proceeds.subtract(cost); }
    }

    /**
     * @param unmatchedUnits units sold with no earlier purchase to match — a missing buy in the
     *                       history. Their gain is not computed (it would be pure guesswork);
     *                       callers must say so rather than report a clean figure.
     */
    public record Result(List<Disposal> disposals, List<TaxLot> openLots, BigDecimal unmatchedUnits) {

        public BigDecimal shortTermGain(LocalDate from, LocalDate to) { return gain(from, to, false); }

        public BigDecimal longTermGain(LocalDate from, LocalDate to) { return gain(from, to, true); }

        private BigDecimal gain(LocalDate from, LocalDate to, boolean longTerm) {
            return disposals.stream()
                .filter(d -> d.longTerm() == longTerm)
                .filter(d -> (from == null || !d.soldOn().isBefore(from)) && (to == null || !d.soldOn().isAfter(to)))
                .map(Disposal::gain)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        }
    }

    private FifoLedger() {}

    /** @param trades in date order; rows missing a date, quantity or price are skipped */
    public static Result replay(List<Trade> trades) {
        List<TaxLot> lots = new ArrayList<>();
        List<BigDecimal> remaining = new ArrayList<>();
        List<Disposal> disposals = new ArrayList<>();
        BigDecimal unmatched = BigDecimal.ZERO;

        for (Trade t : trades) {
            if (t.splitMultiplier() != null) {
                // Every lot open on the record date is rescaled: more shares, same total cost,
                // same purchase date — the holding period runs from the original purchase.
                BigDecimal m = t.splitMultiplier();
                for (int i = 0; i < lots.size(); i++) {
                    if (remaining.get(i).signum() <= 0) continue;
                    TaxLot l = lots.get(i);
                    BigDecimal units = remaining.get(i).multiply(m);
                    lots.set(i, new TaxLot(l.lotId(), l.acquiredOn(), units,
                        l.costPerUnit().divide(m, 10, RoundingMode.HALF_UP), l.fmv20180131() == null ? null
                            : l.fmv20180131().divide(m, 10, RoundingMode.HALF_UP)));
                    remaining.set(i, units);
                }
                continue;
            }
            if (t.date() == null || t.units() == null || t.pricePerUnit() == null || t.units().signum() <= 0) continue;
            if (t.buy()) {
                lots.add(new TaxLot(t.id(), t.date(), t.units(), t.pricePerUnit(), null));
                remaining.add(t.units());
                continue;
            }
            BigDecimal toSell = t.units();
            for (int i = 0; i < lots.size() && toSell.signum() > 0; i++) {
                BigDecimal avail = remaining.get(i);
                if (avail.signum() <= 0) continue;
                BigDecimal take = avail.min(toSell);
                remaining.set(i, avail.subtract(take));
                toSell = toSell.subtract(take);
                TaxLot lot = lots.get(i);
                disposals.add(new Disposal(t.id(), t.date(), lot.lotId(), lot.acquiredOn(), take,
                    t.pricePerUnit().multiply(take), lot.costPerUnit().multiply(take), lot.isLongTermAsOf(t.date())));
            }
            if (toSell.signum() > 0) unmatched = unmatched.add(toSell);
        }

        List<TaxLot> open = new ArrayList<>();
        for (int i = 0; i < lots.size(); i++) {
            if (remaining.get(i).signum() > 0) {
                TaxLot l = lots.get(i);
                open.add(new TaxLot(l.lotId(), l.acquiredOn(), remaining.get(i), l.costPerUnit(), l.fmv20180131()));
            }
        }
        return new Result(disposals, open, unmatched);
    }
}
