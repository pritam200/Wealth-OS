package com.marketai.forecast.model;

/** Small, dependency-free statistics helpers used by the forecast model and its backtest. */
public final class Stats {

    private Stats() {}

    /** Inverse standard-normal CDF (Acklam's rational approximation, |error| < 1.2e-9). */
    public static double normInv(double p) {
        if (p <= 0 || p >= 1) throw new IllegalArgumentException("p must be in (0,1)");
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double pl = 0.02425;
        if (p < pl) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - pl) return -normInv(1 - p);
        double q = p - 0.5, r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    /** Wilson score interval for a proportion; {low, high}. */
    public static double[] wilson(double p, double n, double z) {
        if (n <= 0) return new double[]{0, 1};
        double z2 = z * z, den = 1 + z2 / n;
        double centre = (p + z2 / (2 * n)) / den;
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / den;
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }
}
