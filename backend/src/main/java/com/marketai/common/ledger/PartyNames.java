package com.marketai.common.ledger;

import java.util.Locale;

/**
 * Whether two descriptions of a counterparty ("SWIGGY*BLR", "Swiggy Bangalore") name the same
 * one. Used to recognise a hand-entered transaction and the email that later reports it (or the
 * other way round) as one event.
 */
public final class PartyNames {

    /** Shorter names than this ("ab", "upi") are too generic to count as a match by containment. */
    private static final int MIN_CONTAINED = 4;

    private PartyNames() {}

    /** Lower-case letters and digits only. */
    public static String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Equal after normalising, or one contains the other (at least {@value #MIN_CONTAINED} characters). */
    public static boolean sameParty(String a, String b) {
        String x = normalise(a), y = normalise(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        String shorter = x.length() <= y.length() ? x : y;
        String longer = shorter == x ? y : x;
        return shorter.length() >= MIN_CONTAINED && longer.contains(shorter);
    }

    /** Any of the names on one side matches any on the other. */
    public static boolean anySame(String[] left, String[] right) {
        for (String l : left) for (String r : right) if (sameParty(l, r)) return true;
        return false;
    }
}
