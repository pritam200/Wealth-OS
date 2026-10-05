package com.marketai.dataplatform.pipeline;

import com.marketai.dataplatform.domain.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Derives a position from ledger entries: quantity, weighted-average cost, invested value and
 * realised P&amp;L. Pure and total — replaying the same entries always gives the same answer, and
 * the position is never stored as an input.
 *
 * <p>The cost model is weighted-average, the same as the portfolio's own replay, so the two can be
 * compared. Tax-lot costing is a separate projection of the same entries.
 */
public final class LedgerReplay {
    private LedgerReplay() {}

    public record Entry(Long id, TransactionType type, LocalDate date, BigDecimal quantity, BigDecimal unitPrice,
                        BigDecimal grossAmount, BigDecimal fees, BigDecimal ratioFrom, BigDecimal ratioTo) {}

    /**
     * @param incompleteEntries unit-moving entries that stated no quantity and so could not be applied
     * @param oversold          a sale exceeded the units held; the sale was clamped, not hidden
     * @param unhandledCorporateActions mergers and similar that change identity rather than count
     */
    public record Position(BigDecimal quantity, BigDecimal averageCost, BigDecimal investedValue, BigDecimal realizedPnl,
                           int incompleteEntries, boolean oversold, int unhandledCorporateActions, LocalDate lastActivity) {
        public static Position empty() {
            return new Position(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, false, 0, null);
        }
    }

    public static Position replay(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        // Units in before units out on the same day, so a same-day round trip is not read as an oversell.
        sorted.sort(Comparator.comparing(Entry::date)
            .thenComparing((Entry e) -> -e.type().unitEffect())
            .thenComparing(Entry::id, Comparator.nullsLast(Comparator.naturalOrder())));

        BigDecimal qty = BigDecimal.ZERO, cost = BigDecimal.ZERO, realized = BigDecimal.ZERO;
        int incomplete = 0, unhandled = 0;
        boolean oversold = false;
        LocalDate last = null;

        for (Entry e : sorted) {
            TransactionType t = e.type();
            last = e.date();
            if (t == TransactionType.SPLIT) {
                if (e.ratioFrom() == null || e.ratioTo() == null || e.ratioFrom().signum() <= 0 || e.ratioTo().signum() <= 0) {
                    incomplete++;
                    continue;
                }
                // Units scale; total cost is unchanged, so cost per unit divides.
                qty = qty.multiply(e.ratioTo()).divide(e.ratioFrom(), 6, RoundingMode.HALF_UP);
                continue;
            }
            if (t == TransactionType.MERGER) { unhandled++; continue; }
            if (!t.movesUnits()) continue;
            if (e.quantity() == null || e.quantity().signum() <= 0) { incomplete++; continue; }

            if (t.unitEffect() > 0) {
                BigDecimal price = unitPriceOf(e);
                // A bonus issue adds units at nil cost.
                BigDecimal add = t == TransactionType.BONUS ? BigDecimal.ZERO : price.multiply(e.quantity());
                cost = cost.add(add);
                qty = qty.add(e.quantity());
            } else {
                BigDecimal sellQty = e.quantity().min(qty);
                if (e.quantity().compareTo(qty) > 0) oversold = true;
                if (qty.signum() > 0) {
                    BigDecimal avg = cost.divide(qty, 8, RoundingMode.HALF_UP);
                    BigDecimal proceeds = unitPriceOf(e).multiply(sellQty).subtract(e.fees() == null ? BigDecimal.ZERO : e.fees());
                    realized = realized.add(proceeds.subtract(avg.multiply(sellQty)));
                    cost = cost.subtract(avg.multiply(sellQty));
                    qty = qty.subtract(sellQty);
                }
            }
        }
        if (qty.signum() <= 0) {
            return new Position(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, scale2(realized), incomplete, oversold, unhandled, last);
        }
        BigDecimal avg = cost.divide(qty, 6, RoundingMode.HALF_UP);
        return new Position(qty.setScale(6, RoundingMode.HALF_UP), avg, scale2(cost), scale2(realized), incomplete, oversold, unhandled, last);
    }

    private static BigDecimal unitPriceOf(Entry e) {
        if (e.unitPrice() != null) return e.unitPrice();
        if (e.grossAmount() != null && e.quantity() != null && e.quantity().signum() > 0)
            return e.grossAmount().divide(e.quantity(), 6, RoundingMode.HALF_UP);
        return BigDecimal.ZERO;
    }

    private static BigDecimal scale2(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
}
