package com.marketai.market.quality;

import com.marketai.market.entity.PriceHistory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * Turns stored daily rows into a {@link DailySeries}: sorts them oldest first, rejects rows
 * that cannot be real bars, removes duplicates and any bar for a session not yet finished, and
 * records every gap, abnormal move and staleness it sees. Nothing is repaired or estimated —
 * a bad bar is excluded and reported, never "fixed".
 */
public final class PriceSeriesValidator {

    private PriceSeriesValidator() {}

    /** Open/close may sit this far outside the high–low range before the bar is rejected (rounding). */
    static final double OHLC_TOLERANCE = 0.005;
    /** Calendar gaps longer than this many missing weekdays are reported (NSE holiday runs are shorter). */
    static final int MAX_MISSING_WEEKDAYS = 4;
    /** Close-to-close moves beyond ±20% in a day are reported. */
    static final double ABNORMAL_LOG_MOVE = Math.log(1.20);
    /** Newest bar more than this many sessions behind the last close ⇒ STALE_DATA. */
    public static final int STALE_AFTER_SESSIONS = 3;
    /** Warnings older than this many bars are reported but do not lower the status. */
    static final int STATUS_WINDOW = 250;
    /** Split/bonus ratios whose signature an unadjusted series would show. */
    private static final double[] SPLIT_RATIOS = {2, 3, 4, 5, 10, 1.5, 1.25};

    public static DailySeries validate(String symbol, List<PriceHistory> rows, LocalDate expectedSession, int minBars) {
        List<DataIssue> issues = new ArrayList<>();
        int rejected = 0;

        // Sort and de-duplicate: one bar per date, preferring the most recently written row.
        Map<LocalDate, PriceHistory> byDate = new TreeMap<>();
        for (PriceHistory p : rows == null ? List.<PriceHistory>of() : rows) {
            if (p == null || p.getDate() == null) { rejected++; continue; }
            if (p.getInterval() != null && !"1d".equals(p.getInterval())) continue;
            if (p.getDate().isAfter(expectedSession)) {
                rejected++;
                issues.add(new DataIssue("UNFINISHED_CANDLE", DataIssue.INFO, p.getDate(),
                        "Bar for a session that has not closed yet — excluded."));
                continue;
            }
            String invalid = invalidReason(p);
            if (invalid != null) {
                rejected++;
                issues.add(new DataIssue("INVALID_OHLC", DataIssue.WARNING, p.getDate(), invalid + " — bar excluded."));
                continue;
            }
            PriceHistory prev = byDate.get(p.getDate());
            if (prev != null) {
                rejected++;
                issues.add(new DataIssue("DUPLICATE_CANDLE", DataIssue.INFO, p.getDate(),
                        "Two stored bars for one date — kept the most recently updated."));
                if (newer(prev, p)) continue;
            }
            byDate.put(p.getDate(), p);
        }
        List<PriceHistory> bars = new ArrayList<>(byDate.values());

        for (int i = 1; i < bars.size(); i++) {
            LocalDate a = bars.get(i - 1).getDate(), b = bars.get(i).getDate();
            int missing = weekdaysBetween(a, b) - 1;
            if (missing > MAX_MISSING_WEEKDAYS) {
                issues.add(new DataIssue("MISSING_DATES", DataIssue.WARNING, b,
                        missing + " weekday(s) without a bar between " + a + " and " + b + "."));
            }
            double prevClose = bars.get(i - 1).getClose().doubleValue();
            double close = bars.get(i).getClose().doubleValue();
            double move = Math.log(close / prevClose);
            if (Math.abs(move) > ABNORMAL_LOG_MOVE) {
                Double ratio = splitSignature(bars.get(i - 1), bars.get(i));
                if (ratio != null) {
                    issues.add(new DataIssue("POSSIBLE_UNADJUSTED_SPLIT", DataIssue.WARNING, b,
                            String.format("Close moved %.2f → %.2f, the signature of an unadjusted %s:1 split or bonus.",
                                    prevClose, close, trim(ratio))));
                } else {
                    issues.add(new DataIssue("ABNORMAL_MOVE", DataIssue.WARNING, b,
                            String.format("Close moved %+.1f%% in one session (%.2f → %.2f).",
                                    (Math.exp(move) - 1) * 100, prevClose, close)));
                }
            }
        }

        int recent = Math.min(20, bars.size());
        long noVolume = bars.subList(bars.size() - recent, bars.size()).stream()
                .filter(p -> p.getVolume() == null || p.getVolume() <= 0).count();
        if (recent > 0 && noVolume > 0) {
            issues.add(new DataIssue("VOLUME_UNAVAILABLE", DataIssue.INFO, null,
                    noVolume + " of the last " + recent + " bars have no volume — volume measures use only bars that do."));
        }

        int behind = bars.isEmpty() ? Integer.MAX_VALUE : weekdaysBetween(bars.get(bars.size() - 1).getDate(), expectedSession);
        SeriesStatus status;
        if (bars.size() < minBars) {
            status = SeriesStatus.INSUFFICIENT_DATA;
        } else if (behind > STALE_AFTER_SESSIONS) {
            status = SeriesStatus.STALE_DATA;
            issues.add(new DataIssue("STALE", DataIssue.WARNING, bars.get(bars.size() - 1).getDate(),
                    "Newest bar is " + behind + " session(s) behind " + expectedSession + "."));
        } else {
            // Only recent warnings lower the status: a 2020 crash day is history, not a defect
            // in the data the current analysis uses.
            LocalDate window = bars.get(Math.max(0, bars.size() - STATUS_WINDOW)).getDate();
            boolean warning = issues.stream().anyMatch(i -> i.isWarning()
                    && (i.date() == null || !i.date().isBefore(window)));
            status = warning ? SeriesStatus.DATA_QUALITY_WARNING : SeriesStatus.OK;
        }

        PriceHistory last = bars.isEmpty() ? null : bars.get(bars.size() - 1);
        return new DailySeries(symbol, List.copyOf(bars), status, List.copyOf(issues), rejected, expectedSession,
                bars.isEmpty() ? -1 : behind,
                last != null ? last.getProvider() : null,
                last != null ? (last.getUpdatedAt() != null ? last.getUpdatedAt() : last.getIngestedAt()) : null);
    }

    static String invalidReason(PriceHistory p) {
        if (!positive(p.getOpen()) || !positive(p.getHigh()) || !positive(p.getLow()) || !positive(p.getClose())) {
            return "Missing, zero or negative price";
        }
        double o = p.getOpen().doubleValue(), h = p.getHigh().doubleValue(), l = p.getLow().doubleValue(), c = p.getClose().doubleValue();
        if (h < l) return String.format("High %.2f below low %.2f", h, l);
        double tol = h * OHLC_TOLERANCE;
        if (o > h + tol || o < l - tol || c > h + tol || c < l - tol) {
            return String.format("Open %.2f / close %.2f outside the high–low range %.2f–%.2f", o, c, l, h);
        }
        return null;
    }

    private static boolean positive(java.math.BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static boolean newer(PriceHistory kept, PriceHistory candidate) {
        java.time.LocalDateTime a = kept.getUpdatedAt(), b = candidate.getUpdatedAt();
        if (a == null) return false;
        return b == null || a.isAfter(b);
    }

    /**
     * An unadjusted split rescales the whole bar: close AND open jump by the same standard ratio
     * and the two bars' ranges do not overlap. A genuine crash or rally (Adani Jan 2023, the
     * 4 Jun 2024 election-day fall) opens near the prior close or trades back into its range, so
     * a close-to-close ratio alone near 1.25 is not evidence of a split.
     */
    public static Double splitSignature(PriceHistory prev, PriceHistory cur) {
        double pc = prev.getClose().doubleValue(), c = cur.getClose().doubleValue();
        Double k = splitRatio(pc / c);
        if (k == null) return null;
        Double openK = splitRatio(pc / cur.getOpen().doubleValue());
        if (openK == null || !openK.equals(k)) return null;
        boolean down = c < pc;
        boolean separated = down ? cur.getHigh().doubleValue() < prev.getLow().doubleValue()
                                 : cur.getLow().doubleValue() > prev.getHigh().doubleValue();
        return separated ? k : null;
    }

    private static Double splitRatio(double r) {
        double x = r >= 1 ? r : 1 / r;
        for (double k : SPLIT_RATIOS) if (Math.abs(x / k - 1) < 0.03) return k;
        return null;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Weekdays strictly after {@code from} up to and including {@code to}. */
    public static int weekdaysBetween(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) return 0;
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) n++;
        }
        return n;
    }
}
