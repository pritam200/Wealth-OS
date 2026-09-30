package com.marketai.market.quality;

import com.marketai.market.entity.PriceHistory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The canonical daily series for a symbol: validated bars, oldest first, with the evidence of
 * how current and how clean they are. Every analytical calculation reads from this.
 *
 * @param bars            validated, de-duplicated, completed-session bars, oldest first
 * @param rejected        how many stored bars were excluded, and why, is in {@code issues}
 * @param expectedSession the last session that should have a bar by now
 * @param sessionsBehind  weekdays between the newest bar and {@code expectedSession}
 * @param lastUpdated     when the newest bar was last written from the provider
 */
public record DailySeries(
        String symbol,
        List<PriceHistory> bars,
        SeriesStatus status,
        List<DataIssue> issues,
        int rejected,
        LocalDate expectedSession,
        int sessionsBehind,
        String provider,
        LocalDateTime lastUpdated) {

    public boolean isEmpty() {
        return bars.isEmpty();
    }

    public int size() {
        return bars.size();
    }

    public PriceHistory last() {
        return bars.isEmpty() ? null : bars.get(bars.size() - 1);
    }

    public LocalDate lastBarDate() {
        return bars.isEmpty() ? null : last().getDate();
    }

    public List<Double> closes() {
        return bars.stream().map(b -> b.getClose().doubleValue()).toList();
    }

    public boolean stale() {
        return status == SeriesStatus.STALE_DATA;
    }

    /** True when a warning-level issue falls inside the most recent {@code window} bars. */
    public boolean hasWarningWithin(int window, String code) {
        if (bars.isEmpty()) return false;
        LocalDate from = bars.get(Math.max(0, bars.size() - window)).getDate();
        return issues.stream().anyMatch(i -> i.isWarning() && code.equals(i.code())
                && i.date() != null && !i.date().isBefore(from));
    }
}
