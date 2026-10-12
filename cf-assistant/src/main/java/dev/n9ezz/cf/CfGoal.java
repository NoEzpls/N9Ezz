package dev.n9ezz.cf;

import java.math.BigDecimal;

/** An exact money target based exclusively on server balance snapshots from this session. */
public final class CfGoal {
    private BigDecimal amount;
    private boolean profitTarget;
    private BigDecimal lossLimit;
    private BigDecimal initial;
    private BigDecimal current;
    private BigDecimal target;

    public void configure(BigDecimal amount, boolean profitTarget) {
        positiveMoney(amount, "goal");
        this.amount = amount;
        this.profitTarget = profitTarget;
        begin();
    }

    public void disable() {
        amount = null;
        profitTarget = false;
        begin();
    }

    public boolean enabled() { return amount != null; }
    public BigDecimal amount() { return amount; }
    public boolean profitTarget() { return profitTarget; }
    public BigDecimal current() { return current; }
    public BigDecimal initial() { return initial; }
    public BigDecimal target() { return target; }
    public BigDecimal lossLimit() { return lossLimit; }

    /** Each explicit start needs a fresh balance; persisted observations cannot fund a new run. */
    public void begin() {
        initial = null;
        current = null;
        target = amount != null && !profitTarget ? amount : null;
    }

    public void accept(BigDecimal balance) {
        money(balance, "balance");
        BigDecimal nextTarget = target;
        if (initial == null && enabled() && profitTarget) {
            nextTarget = balance.add(amount);
            // Reject impossible target arithmetic before retaining any part of this snapshot.
            money(nextTarget, "profit target");
        }
        if (initial == null) initial = balance;
        current = balance;
        target = nextTarget;
    }

    public boolean reached() {
        return enabled() && current != null && target != null && current.compareTo(target) >= 0;
    }

    public boolean canAfford(long integerStake) {
        return integerStake > 0 && current != null && current.compareTo(BigDecimal.valueOf(integerStake)) >= 0;
    }

    /** null disables the optional session loss limit; it never changes or increases a stake. */
    public void configureLossLimit(BigDecimal limit) {
        if (limit != null) positiveMoney(limit, "loss limit");
        lossLimit = limit;
    }

    public boolean hitLossLimit() {
        return lossLimit != null && initial != null && current != null
                && initial.subtract(current).compareTo(lossLimit) >= 0;
    }

    private static void positiveMoney(BigDecimal value, String label) {
        money(value, label);
        if (value.signum() == 0) throw new IllegalArgumentException(label + " must be positive");
    }

    private static void money(BigDecimal value, String label) {
        if (value == null || value.signum() < 0 || value.compareTo(CfBalance.MAX_AMOUNT) > 0
                || value.scale() > 2) {
            throw new IllegalArgumentException(label + " must be between 0 and 1000000000000000 with at most 2 decimals");
        }
    }
}
