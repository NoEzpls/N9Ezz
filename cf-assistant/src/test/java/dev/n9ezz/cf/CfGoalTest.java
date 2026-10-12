package dev.n9ezz.cf;

import java.math.BigDecimal;

public final class CfGoalTest {
    private static int checks;

    public static void main(String[] args) {
        CfGoal goal = new CfGoal();
        check(!goal.enabled() && !goal.reached(), "no implicit goal");
        check(goal.current() == null && goal.initial() == null && goal.target() == null, "no invented balance");
        check(!goal.canAfford(50000), "unknown balance cannot fund a stake");
        goal.configure(money("1000000"), false);
        check(goal.enabled() && !goal.profitTarget(), "absolute balance goal configured");
        eq(goal.amount(), "1000000", "configured amount retained");
        eq(goal.target(), "1000000", "absolute target independent of first balance");
        check(!goal.reached(), "configured target alone does not claim success");
        goal.accept(money("600000.25"));
        eq(goal.initial(), "600000.25", "first exact server balance anchors session");
        eq(goal.current(), "600000.25", "current exact server balance");
        check(goal.canAfford(50000), "affordable stake");
        check(!goal.canAfford(600001), "cannot afford balance overshoot");
        check(!goal.canAfford(0) && !goal.canAfford(-1), "nonpositive stake invalid");
        check(!goal.reached(), "below target");
        goal.accept(money("999999.99"));
        check(!goal.reached(), "one cent below target");
        goal.accept(money("1000000"));
        check(goal.reached(), "exact target reached");
        eq(goal.initial(), "600000.25", "later balance does not reset baseline");
        goal.accept(money("1200000.50"));
        check(goal.reached(), "overshoot reaches target");
        goal.accept(money("999999"));
        check(!goal.reached(), "latest balance defines target status");

        goal.configureLossLimit(money("100000"));
        goal.accept(money("500000.26"));
        check(!goal.hitLossLimit(), "one cent before loss cap");
        goal.accept(money("500000.25"));
        check(goal.hitLossLimit(), "exact session loss cap");
        goal.configureLossLimit(null);
        check(!goal.hitLossLimit() && goal.lossLimit() == null, "loss cap explicitly disabled");

        goal.configure(money("75000.50"), true);
        check(goal.profitTarget() && goal.target() == null && goal.current() == null, "profit goal awaits fresh baseline");
        goal.accept(money("125000.25"));
        eq(goal.target(), "200000.75", "profit added exactly to initial balance");
        goal.accept(money("200000.74"));
        check(!goal.reached(), "profit one cent short");
        goal.accept(money("200000.75"));
        check(goal.reached(), "profit exact target reached");
        goal.configureLossLimit(money("50000"));
        goal.begin();
        check(goal.current() == null && goal.initial() == null && goal.target() == null, "new session discards old snapshots");
        check(goal.enabled() && goal.lossLimit() != null, "new session keeps only configured settings");
        check(!goal.hitLossLimit() && !goal.canAfford(50000), "fresh balance required after restart");
        goal.accept(money("25000"));
        eq(goal.target(), "100000.50", "profit target recomputed for new session");
        check(!goal.canAfford(50000), "cannot fund stake even if goal higher");

        goal.disable();
        check(!goal.enabled() && goal.current() == null && goal.target() == null && !goal.profitTarget(), "disable clears goal and observations");
        goal.accept(money("75000"));
        check(goal.canAfford(50000) && !goal.reached(), "balances useful without active target");
        goal.accept(money("25000"));
        check(goal.hitLossLimit(), "loss cap independent of target enablement");
        goal.accept(BigDecimal.ZERO);
        check(!goal.canAfford(1), "zero balance never affordable");

        CfGoal overflow = new CfGoal();
        overflow.configure(money("1"), true);
        rejects(() -> overflow.accept(CfBalance.MAX_AMOUNT), "profit target overflow");
        check(overflow.initial() == null && overflow.current() == null && overflow.target() == null, "overflow does not partially accept snapshot");
        overflow.accept(money("999999999999999"));
        eq(overflow.target(), "1000000000000000", "maximum profit target exact");
        overflow.accept(CfBalance.MAX_AMOUNT);
        check(overflow.reached(), "maximum target reachable");

        CfGoal invalid = new CfGoal();
        for (BigDecimal value : new BigDecimal[]{null, money("-1"), money("1000000000000000.01"), money("1.001")}) {
            rejects(() -> invalid.configure(value, false), "invalid goal");
            rejects(() -> invalid.accept(value), "invalid balance");
            if (value != null) rejects(() -> invalid.configureLossLimit(value), "invalid loss limit");
        }
        rejects(() -> invalid.configure(BigDecimal.ZERO, false), "zero target invalid");
        rejects(() -> invalid.configureLossLimit(BigDecimal.ZERO), "zero loss limit invalid");
        invalid.configure(money("100"), false);
        invalid.accept(money("50"));
        rejects(() -> invalid.configure(money("-1"), true), "bad new settings rejected");
        eq(invalid.target(), "100", "invalid reconfiguration preserves valid target");
        eq(invalid.current(), "50", "invalid reconfiguration preserves snapshot");
        rejects(() -> invalid.accept(money("-1")), "negative later balance");
        eq(invalid.current(), "50", "invalid balance preserves snapshot");
        System.out.println("CfGoalTest: " + checks + " checks passed");
    }

    private static BigDecimal money(String value) { return new BigDecimal(value); }

    private static void eq(BigDecimal actual, String expected, String description) {
        check(actual != null && actual.compareTo(money(expected)) == 0, description);
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
