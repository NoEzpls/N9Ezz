/* Restored from the supplied 0.2.1 bytecode with CFR 0.152; see provenance.json. */
package dev.n9ezz.cf;

import dev.n9ezz.cf.CfBalance;
import java.math.BigDecimal;

public final class CfGoal {
    private BigDecimal amount;
    private boolean profitTarget;
    private BigDecimal lossLimit;
    private BigDecimal initial;
    private BigDecimal current;
    private BigDecimal target;

    public void configure(BigDecimal bigDecimal, boolean bl) {
        CfGoal.positiveMoney(bigDecimal, "goal");
        this.amount = bigDecimal;
        this.profitTarget = bl;
        this.begin();
    }

    public void disable() {
        this.amount = null;
        this.profitTarget = false;
        this.begin();
    }

    public boolean enabled() {
        return this.amount != null;
    }

    public BigDecimal amount() {
        return this.amount;
    }

    public boolean profitTarget() {
        return this.profitTarget;
    }

    public BigDecimal current() {
        return this.current;
    }

    public BigDecimal initial() {
        return this.initial;
    }

    public BigDecimal target() {
        return this.target;
    }

    public BigDecimal lossLimit() {
        return this.lossLimit;
    }

    public void begin() {
        this.initial = null;
        this.current = null;
        this.target = this.amount != null && !this.profitTarget ? this.amount : null;
    }

    public void accept(BigDecimal bigDecimal) {
        CfGoal.money(bigDecimal, "balance");
        BigDecimal bigDecimal2 = this.target;
        if (this.initial == null && this.enabled() && this.profitTarget) {
            bigDecimal2 = bigDecimal.add(this.amount);
            CfGoal.money(bigDecimal2, "profit target");
        }
        if (this.initial == null) {
            this.initial = bigDecimal;
        }
        this.current = bigDecimal;
        this.target = bigDecimal2;
    }

    public boolean reached() {
        return this.enabled() && this.current != null && this.target != null && this.current.compareTo(this.target) >= 0;
    }

    public boolean canAfford(long l) {
        return l > 0L && this.current != null && this.current.compareTo(BigDecimal.valueOf(l)) >= 0;
    }

    public void configureLossLimit(BigDecimal bigDecimal) {
        if (bigDecimal != null) {
            CfGoal.positiveMoney(bigDecimal, "loss limit");
        }
        this.lossLimit = bigDecimal;
    }

    public boolean hitLossLimit() {
        return this.lossLimit != null && this.initial != null && this.current != null && this.initial.subtract(this.current).compareTo(this.lossLimit) >= 0;
    }

    private static void positiveMoney(BigDecimal bigDecimal, String string) {
        CfGoal.money(bigDecimal, string);
        if (bigDecimal.signum() == 0) {
            throw new IllegalArgumentException(string + " must be positive");
        }
    }

    private static void money(BigDecimal bigDecimal, String string) {
        if (bigDecimal == null || bigDecimal.signum() < 0 || bigDecimal.compareTo(CfBalance.MAX_AMOUNT) > 0 || bigDecimal.scale() > 2) {
            throw new IllegalArgumentException(string + " must be between 0 and 1000000000000000 with at most 2 decimals");
        }
    }
}

