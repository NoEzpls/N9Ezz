package dev.n9ezz.cf;

public final class CfStatisticsTest {
    private static int checks;

    public static void main(String[] args) {
        CfStatistics.Summary none = CfStatistics.summarize(0, 0);
        check(none.n() == 0 && Double.isNaN(none.rate()), "zero observations has no win-rate estimate");
        check(none.lower() == 0 && none.upper() == 1, "empty interval contains every probability");
        CfStatistics.Summary balanced = CfStatistics.summarize(250, 250);
        check(balanced.n() == 500 && balanced.rate() == 0.5, "500-game balanced sample");
        close(balanced.lower(), 0.456341, 0.000001, "known Wilson lower bound");
        close(balanced.upper(), 0.543659, 0.000001, "known Wilson upper bound");
        close(balanced.lower() + balanced.upper(), 1, 1e-12, "balanced interval symmetry");
        CfStatistics.Summary oneWin = CfStatistics.summarize(1, 0);
        close(oneWin.lower(), 0.20654931437723745, 1e-12, "one win does not establish certainty");
        close(oneWin.upper(), 1, 1e-12, "one win upper boundary");
        CfStatistics.Summary oneLoss = CfStatistics.summarize(0, 1);
        close(oneLoss.upper(), 1 - oneWin.lower(), 1e-12, "one loss is symmetric");
        close(oneLoss.lower(), 0, 1e-12, "one loss lower boundary");
        check(CfStatistics.summarize(500, 0).lower() < 1, "even all observed wins is not a guarantee");
        check(CfStatistics.summarize(0, 500).upper() > 0, "even all observed losses is not a guarantee");
        for (int total = 1; total <= 500; total++) {
            for (int wins = 0; wins <= total; wins++) {
                CfStatistics.Summary s = CfStatistics.summarize(wins, total - wins);
                check(s.lower() >= 0 && s.upper() <= 1 && s.lower() <= s.upper(), "bounded ordered interval");
                check(s.lower() <= s.rate() + 1e-15 && s.upper() >= s.rate() - 1e-15, "sample estimate inside interval");
            }
        }
        CfStatistics.Summary large = CfStatistics.summarize(Long.MAX_VALUE - 1, 1);
        check(Double.isFinite(large.lower()) && Double.isFinite(large.upper()), "large sample does not overflow floating formula");
        rejects(() -> CfStatistics.summarize(-1, 0), "negative wins");
        rejects(() -> CfStatistics.summarize(0, -1), "negative losses");
        rejects(() -> CfStatistics.summarize(Long.MAX_VALUE, 1), "total overflow");
        System.out.println("CfStatisticsTest: " + checks + " checks passed");
    }

    private static void close(double actual, double expected, double tolerance, String description) {
        check(Math.abs(actual - expected) <= tolerance, description + ": " + actual);
    }

    private static void rejects(Runnable operation, String description) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError(description);
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
    }
}
