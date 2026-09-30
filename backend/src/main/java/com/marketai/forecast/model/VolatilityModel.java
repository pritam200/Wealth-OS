package com.marketai.forecast.model;

import com.marketai.technical.service.Indicators;

import java.util.List;

/**
 * Realised volatility and its conversion across horizons, stated explicitly:
 *   daily σ      = sample standard deviation of the last {@value #WINDOW} daily log returns
 *   annualised σ = daily σ × √252
 *   horizon σ    = daily σ × √h   (h in trading sessions; assumes independent daily returns)
 * ATR is never used here: it measures the intraday high–low range, which runs 1.3–1.6× the
 * close-to-close σ, so treating it as σ overstated every range.
 */
public final class VolatilityModel {

    private VolatilityModel() {}

    public static final int WINDOW = 120;
    public static final int TRADING_DAYS_PER_YEAR = 252;

    public static final String METHOD = "σ = sample standard deviation of the last 120 daily log returns; "
            + "annualised = σ·√252; horizon = σ·√h trading sessions.";

    /** Daily σ from the returns ending at the last close, or null with fewer than {@value #WINDOW} returns. */
    public static Double dailySigma(List<Double> closes) {
        if (closes.size() < WINDOW + 1) return null;
        return Indicators.stdev(Indicators.logReturns(closes, WINDOW));
    }

    public static double horizonSigma(double dailySigma, int sessions) {
        return dailySigma * Math.sqrt(sessions);
    }

    public static double annualised(double dailySigma) {
        return dailySigma * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }

    /** Price at quantile {@code p} of the zero-drift log-normal over the horizon. */
    public static double quantilePrice(double price, double horizonSigma, double p) {
        return price * Math.exp(horizonSigma * Stats.normInv(p));
    }

    /**
     * "Nifty below X" trigger for a staged deployment: the lower edge of the one-σ range over
     * {@code sessions} from the last close, P·exp(−σ·√h), using realised volatility — shared by
     * Today's Actions and the redemption plan. Null when volatility or price is unavailable
     * or the data is stale.
     */
    public static String pullbackTrigger(com.marketai.technical.dto.TechnicalAnalysisDto ta, int sessions) {
        if (ta == null || ta.getPrice() == null || ta.getDailyVolatilityPct() == null || Boolean.TRUE.equals(ta.getStale())) return null;
        double price = ta.getPrice().doubleValue();
        double hs = horizonSigma(ta.getDailyVolatilityPct().doubleValue() / 100, sessions);
        double level = price * Math.exp(-hs);
        return String.format("Nifty 50 closes below %.0f — a one-σ %d-session pullback (%.1f%%) from the %s close of %.0f",
                level, sessions, (1 - level / price) * 100, ta.getLastBarDate(), price);
    }
}
