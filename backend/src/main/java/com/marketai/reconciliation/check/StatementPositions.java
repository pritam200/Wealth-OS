package com.marketai.reconciliation.check;

import com.marketai.portfolio.entity.Holding;
import com.marketai.portfolio.entity.Transaction;
import com.marketai.portfolio.service.PortfolioService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * What the portfolio held on a statement's date, and how a statement line is matched to a
 * portfolio holding. Shared by the checks that compare a depository or registrar statement with
 * the holdings built from trades, so both sides are read the same way.
 */
public final class StatementPositions {

    private StatementPositions() {}

    /**
     * Days before the statement date whose trades may not have reached it yet: an equity trade
     * settles the next working day, so a trade on the Friday before a Monday statement is still
     * in transit. The position as of each of these cutoffs is an acceptable match.
     */
    static final int SETTLEMENT_DAYS = 3;

    /** Units held at the end of {@code date} by the trades on record, or null when there are none to replay. */
    public static BigDecimal unitsAsOf(List<Transaction> txns, LocalDate date) {
        if (txns == null || txns.isEmpty()) return null;
        List<Transaction> upTo = txns.stream()
            .filter(t -> t.getTransactionDate() != null && !t.getTransactionDate().isAfter(date)).toList();
        return PortfolioService.replayPosition(upTo).quantity();
    }

    /**
     * The positions the trades on record allow on a statement dated {@code date}: as of that day
     * and as of each of the {@link #SETTLEMENT_DAYS} before it. A holding with no trades on record
     * can only be compared at its stored quantity, unless it was bought after the statement.
     */
    public static List<BigDecimal> acceptable(Holding h, List<Transaction> txns, LocalDate date) {
        if (txns == null || txns.isEmpty()) {
            if (h.getBuyDate() != null && h.getBuyDate().isAfter(date)) return List.of(BigDecimal.ZERO);
            return List.of(h.getQuantity() != null ? h.getQuantity() : BigDecimal.ZERO);
        }
        java.util.ArrayList<BigDecimal> out = new java.util.ArrayList<>();
        for (int d = 0; d <= SETTLEMENT_DAYS; d++) out.add(unitsAsOf(txns, date.minusDays(d)));
        return out;
    }

    public static boolean same(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return a != null && b != null && a.subtract(b).abs().compareTo(tolerance) <= 0;
    }

    /** "HAL.NS" and "hal" are one listing; null when blank. */
    public static String bareSymbol(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT).replaceAll("\\.(NS|BO|MF)$", "");
    }

    /**
     * A company's name reduced to the words that identify it: "HERO MOTOCORP LIMITED" and
     * "Hero MotoCorp Ltd." are one key. Only the legal suffix and the depository's share-class
     * words are dropped, so two different companies never share a key by accident.
     */
    public static String companyKey(String name) {
        if (name == null) return null;
        String k = name.toLowerCase(Locale.ROOT)
            .replaceAll("#.*$", "")                                       // "#NEW EQUITY SHARES OF RS 2/- AFTER SPLIT"
            .replaceAll("\\b(new\\s+)?equity\\s+shares?\\b.*$", "")       // "EQUITY SHARES OF RS 10/-"
            .replaceAll("\\b(limited|ltd|the|eq|equity)\\b", " ")
            .replaceAll("[^a-z0-9]", "");
        return k.isEmpty() ? null : k;
    }

    public static boolean isFund(Holding h) {
        return h.getSymbol() != null && h.getSymbol().toUpperCase(Locale.ROOT).endsWith(".MF");
    }

    public static String units(BigDecimal v) {
        return v == null ? "0" : v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
