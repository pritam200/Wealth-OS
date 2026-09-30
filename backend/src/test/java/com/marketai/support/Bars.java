package com.marketai.support;

import com.marketai.market.entity.PriceHistory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Price-bar fixtures for analytics tests: real data from resources, and seeded synthetic series. */
public final class Bars {

    private Bars() {}

    /** Real NSE daily bars (Yahoo chart API) from src/test/resources/fixtures, oldest first. */
    public static List<PriceHistory> fixture(String name) {
        List<PriceHistory> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                Bars.class.getResourceAsStream("/fixtures/" + name), StandardCharsets.UTF_8))) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",");
                out.add(bar(LocalDate.parse(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                        Double.parseDouble(f[3]), Double.parseDouble(f[4]), Long.parseLong(f[5])));
            }
        } catch (Exception e) {
            throw new IllegalStateException("fixture " + name, e);
        }
        return out;
    }

    public static PriceHistory bar(LocalDate d, double o, double h, double l, double c, long v) {
        return PriceHistory.builder().symbol("TEST").date(d)
                .open(bd(o)).high(bd(h)).low(bd(l)).close(bd(c)).adjClose(bd(c)).volume(v).interval("1d").build();
    }

    /** Geometric random walk with daily log-σ {@code sigma}, zero drift, weekdays only, ending on {@code last}. */
    public static List<PriceHistory> randomWalk(int n, double sigma, long seed, LocalDate last) {
        Random rnd = new Random(seed);
        List<LocalDate> dates = weekdaysEndingOn(last, n);
        List<PriceHistory> out = new ArrayList<>();
        double c = 1000;
        for (LocalDate d : dates) {
            double prev = c;
            c = prev * Math.exp(sigma * rnd.nextGaussian());
            double hi = Math.max(prev, c) * (1 + Math.abs(rnd.nextGaussian()) * sigma * 0.3);
            double lo = Math.min(prev, c) * (1 - Math.abs(rnd.nextGaussian()) * sigma * 0.3);
            out.add(bar(d, prev, hi, lo, c, 100_000 + rnd.nextInt(50_000)));
        }
        return out;
    }

    public static List<LocalDate> weekdaysEndingOn(LocalDate last, int n) {
        List<LocalDate> d = new ArrayList<>();
        LocalDate x = last;
        while (d.size() < n) {
            if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY) d.add(0, x);
            x = x.minusDays(1);
        }
        return d;
    }

    public static List<Double> closes(List<PriceHistory> bars) {
        return bars.stream().map(b -> b.getClose().doubleValue()).toList();
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
