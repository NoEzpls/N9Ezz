package dev.n9ezz.cf;

/** Descriptive binomial statistics, never a prediction or a reason to raise the stake. */
public final class CfStatistics {
    private static final double Z95 = 1.959963984540054;

    private CfStatistics() {}

    public record Summary(long n, double rate, double lower, double upper) {}

    /** Wilson 95% interval assumes independent trials with a fixed underlying win probability. */
    public static Summary summarize(long wins, long losses) {
        if (wins < 0 || losses < 0) throw new IllegalArgumentException("counts must be nonnegative");
        final long n;
        try {
            n = Math.addExact(wins, losses);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("sample size overflow", ex);
        }
        if (n == 0) return new Summary(0, Double.NaN, 0.0, 1.0);
        double rate = (double) wins / n;
        double zSquared = Z95 * Z95;
        double denominator = 1.0 + zSquared / n;
        double center = (rate + zSquared / (2.0 * n)) / denominator;
        double margin = Z95 * Math.sqrt(rate * (1.0 - rate) / n + zSquared / (4.0 * n * n)) / denominator;
        return new Summary(n, rate, Math.max(0.0, center - margin), Math.min(1.0, center + margin));
    }
}
