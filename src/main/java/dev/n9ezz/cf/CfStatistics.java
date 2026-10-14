/* Restored from the supplied 0.2.1 bytecode with CFR 0.152; see provenance.json. */
package dev.n9ezz.cf;

public final class CfStatistics {
    private static final double Z95 = 1.959963984540054;

    private CfStatistics() {
    }

    public static Summary summarize(long l, long l2) {
        long l3;
        if (l < 0L || l2 < 0L) {
            throw new IllegalArgumentException("counts must be nonnegative");
        }
        try {
            l3 = Math.addExact(l, l2);
        }
        catch (ArithmeticException arithmeticException) {
            throw new IllegalArgumentException("sample size overflow", arithmeticException);
        }
        if (l3 == 0L) {
            return new Summary(0L, Double.NaN, 0.0, 1.0);
        }
        double d = (double)l / (double)l3;
        double d2 = 3.8414588206941254;
        double d3 = 1.0 + d2 / (double)l3;
        double d4 = (d + d2 / (2.0 * (double)l3)) / d3;
        double d5 = 1.959963984540054 * Math.sqrt(d * (1.0 - d) / (double)l3 + d2 / (4.0 * (double)l3 * (double)l3)) / d3;
        return new Summary(l3, d, Math.max(0.0, d4 - d5), Math.min(1.0, d4 + d5));
    }

    public record Summary(long n, double rate, double lower, double upper) {
    }
}

