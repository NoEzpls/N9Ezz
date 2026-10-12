package dev.n9ezz.cf;

/** Self-contained tests: run with java dev.n9ezz.cf.CfSessionTest. */
public final class CfSessionTest {
    private static int checks;

    public static void main(String[] args) {
        boundsAndImmutableStake();
        pendingAndPacing();
        pacingAcrossSessions();
        uiBlocksOnlyNewCommands();
        creationAndResultTimeouts();
        negativeAcknowledgements();
        ambiguousAndDuplicateSignals();
        stopAndLateSettlement();
        disconnectAndSendFailure();
        persistedRecovery();
        extremeTimes();
        System.out.println("CfSessionTest: " + checks + " checks passed");
    }

    private static void boundsAndImmutableStake() {
        CfSession s = new CfSession();
        throwsType(IllegalArgumentException.class, () -> s.start(0, 0));
        throwsType(IllegalArgumentException.class, () -> s.start(501, 0));
        throwsType(IllegalArgumentException.class, () -> s.start(Integer.MIN_VALUE, 0));
        s.start(500, 0);
        throwsType(IllegalStateException.class, () -> s.start(1, 0));
        for (int i = 0; i < 500; i++) {
            long now = 2_000L * i;
            equal("cf create 50000", s.tick(now, true, false));
            equal(i + 1, s.snapshot().submitted());
            check(s.snapshot().unresolved());
            check(s.onSignal(CfSession.Signal.CREATED, now + 1));
            // Long win and loss streaks must never change the amount or pacing.
            check(s.onSignal(i < 250 ? CfSession.Signal.WIN : CfSession.Signal.LOSS, now + 2));
            equal(i + 1, s.snapshot().completed());
            equal(null, s.tick(now + 1_999, true, false));
        }
        equal(250, s.snapshot().wins());
        equal(250, s.snapshot().losses());
        equal(CfSession.Mode.STOPPED, s.snapshot().mode());
        equal(null, s.tick(Long.MAX_VALUE, true, false));
        equal(500, s.snapshot().submitted());
        s.start(1, 1_000_000);
        equal(0, s.snapshot().submitted());
        equal(0, s.snapshot().completed());
        equal("cf create 50000", s.tick(1_000_000, true, false));
        settle(s, 1_000_001, CfSession.Signal.WIN);
        equal(CfSession.Mode.STOPPED, s.snapshot().mode());
    }

    private static void pendingAndPacing() {
        CfSession s = started(3, 100);
        equal("cf create 50000", s.tick(100, true, false));
        equal(null, s.tick(100, true, false));
        equal(null, s.tick(2_100, true, false));
        equal(1, s.snapshot().submitted());
        settle(s, 2_101, CfSession.Signal.LOSS);
        equal("cf create 50000", s.tick(2_103, true, false));
        settle(s, 2_104, CfSession.Signal.WIN);
        equal(null, s.tick(4_102, true, false));
        equal("cf create 50000", s.tick(4_103, true, false));
        equal(3, s.snapshot().submitted());
    }

    private static void uiBlocksOnlyNewCommands() {
        CfSession s = started(2, 0);
        equal(null, s.tick(0, true, true));
        equal(null, s.tick(50_000, true, true));
        equal(0, s.snapshot().submitted());
        equal("cf create 50000", s.tick(50_001, true, false));
        check(s.onSignal(CfSession.Signal.CREATED, 50_002));
        equal(null, s.tick(230_002, true, true));
        equal(CfSession.Mode.STOPPED, s.snapshot().mode());
        check(s.snapshot().unresolved());
    }

    private static void pacingAcrossSessions() {
        CfSession s = started(1, 0);
        equal("cf create 50000", s.tick(0, true, false));
        settle(s, 1, CfSession.Signal.WIN);
        s.start(1, 3);
        equal(null, s.tick(3, true, false));
        equal(null, s.tick(1_999, true, false));
        equal("cf create 50000", s.tick(2_000, true, false));
        equal(1, s.snapshot().submitted());
    }

    private static void creationAndResultTimeouts() {
        CfSession create = started(2, 0);
        create.tick(0, true, false);
        equal(null, create.tick(9_999, true, false));
        equal(CfSession.Mode.RUNNING, create.snapshot().mode());
        equal(null, create.tick(10_000, true, false));
        equal(CfSession.Mode.STOPPED, create.snapshot().mode());
        check(create.snapshot().unresolved());
        equal(null, create.tick(100_000, true, false));
        equal(1, create.snapshot().submitted());
        throwsType(IllegalStateException.class, () -> create.start(2, 100_001));

        CfSession result = started(2, 0);
        result.tick(0, true, false);
        result.onSignal(CfSession.Signal.CREATED, 7);
        equal(null, result.tick(180_006, true, false));
        equal(CfSession.Mode.RUNNING, result.snapshot().mode());
        equal(null, result.tick(180_007, true, false));
        equal(CfSession.Mode.STOPPED, result.snapshot().mode());
        check(result.snapshot().unresolved());
        check(result.onSignal(CfSession.Signal.LOSS, 180_008));
        check(!result.snapshot().unresolved());
        equal(1, result.snapshot().losses());
        equal(null, result.tick(180_009, true, false));

        CfSession lateAck = started(2, 0);
        lateAck.tick(0, true, false);
        check(lateAck.onSignal(CfSession.Signal.CREATED, 10_000));
        equal(CfSession.Mode.STOPPED, lateAck.snapshot().mode());
        equal(CfSession.Phase.WAITING_RESULT, lateAck.snapshot().phase());
        check(lateAck.snapshot().unresolved());
        check(lateAck.onSignal(CfSession.Signal.WIN, 10_001));
        equal(null, lateAck.tick(10_002, true, false));

        CfSession lateResult = started(2, 0);
        lateResult.tick(0, true, false);
        lateResult.onSignal(CfSession.Signal.CREATED, 1);
        check(lateResult.onSignal(CfSession.Signal.WIN, 180_001));
        equal(CfSession.Mode.STOPPED, lateResult.snapshot().mode());
        equal(1, lateResult.snapshot().completed());
        check(!lateResult.snapshot().unresolved());
        equal(null, lateResult.tick(180_002, true, false));
    }

    private static void negativeAcknowledgements() {
        for (CfSession.Signal signal : new CfSession.Signal[] {
                CfSession.Signal.INSUFFICIENT, CfSession.Signal.LIMIT}) {
            CfSession s = started(2, 0);
            s.tick(0, true, false);
            check(s.onSignal(signal, 1));
            equal(CfSession.Mode.STOPPED, s.snapshot().mode());
            check(!s.snapshot().unresolved());
            equal(0, s.snapshot().completed());
            equal(null, s.tick(10_000, true, false));
            s.start(2, 10_001);
            s.tick(10_001, true, false);
            s.onSignal(CfSession.Signal.CREATED, 10_002);
            check(s.onSignal(signal, 10_003));
            equal(CfSession.Mode.STOPPED, s.snapshot().mode());
            check(s.snapshot().unresolved());
            throwsType(IllegalStateException.class, () -> s.start(2, 10_004));
        }
    }

    private static void ambiguousAndDuplicateSignals() {
        CfSession s = started(2, 0);
        for (CfSession.Signal signal : CfSession.Signal.values()) {
            check(!s.onSignal(signal, 0));
        }
        equal(0, s.snapshot().completed());
        s.tick(0, true, false);
        check(s.onSignal(CfSession.Signal.WIN, 1));
        equal(CfSession.Mode.STOPPED, s.snapshot().mode());
        equal(0, s.snapshot().wins());
        check(s.snapshot().unresolved());
        equal(null, s.tick(5_000, true, false));

        CfSession duplicate = started(2, 0);
        duplicate.tick(0, true, false);
        check(duplicate.onSignal(CfSession.Signal.CREATED, 1));
        check(!duplicate.onSignal(CfSession.Signal.CREATED, 179_999));
        duplicate.tick(180_001, true, false);
        equal(CfSession.Mode.STOPPED, duplicate.snapshot().mode());
        check(duplicate.onSignal(CfSession.Signal.WIN, 180_002));
        check(!duplicate.onSignal(CfSession.Signal.WIN, 180_003));
        check(!duplicate.onSignal(CfSession.Signal.LOSS, 180_004));
        equal(1, duplicate.snapshot().completed());
        equal(1, duplicate.snapshot().wins());
        equal(0, duplicate.snapshot().losses());
    }

    private static void stopAndLateSettlement() {
        CfSession s = started(2, 0);
        s.tick(0, true, false);
        throwsType(IllegalStateException.class, s::clearUnresolved);
        s.stop("manual");
        equal("manual", s.snapshot().reason());
        check(s.snapshot().unresolved());
        equal(null, s.tick(1_000, true, false));
        throwsType(IllegalStateException.class, () -> s.start(2, 1_000));
        check(s.onSignal(CfSession.Signal.CREATED, 1_001));
        check(s.onSignal(CfSession.Signal.LOSS, 1_002));
        check(!s.snapshot().unresolved());
        equal(CfSession.Mode.STOPPED, s.snapshot().mode());
        equal("manual", s.snapshot().reason());
        equal(null, s.tick(5_000, true, false));
        s.start(2, 5_001);
        equal("cf create 50000", s.tick(5_001, true, false));
        s.stop("stop before manual recovery");
        s.clearUnresolved();
        equal(CfSession.Mode.STOPPED, s.snapshot().mode());
        equal(CfSession.Phase.IDLE, s.snapshot().phase());
        equal(null, s.tick(10_000, true, false));
        check(!s.onSignal(CfSession.Signal.WIN, 10_001));
    }

    private static void disconnectAndSendFailure() {
        CfSession before = started(2, 0);
        equal(null, before.tick(0, false, false));
        equal(CfSession.Mode.STOPPED, before.snapshot().mode());
        check(!before.snapshot().unresolved());
        equal(null, before.tick(1, true, false));

        CfSession after = started(2, 0);
        after.tick(0, true, false);
        after.tick(1, false, false);
        check(after.snapshot().unresolved());
        equal(null, after.tick(2_000, true, false));
        equal(1, after.snapshot().submitted());
        throwsType(IllegalStateException.class, () -> after.start(2, 3_000));

        CfSession failed = started(2, 0);
        equal("cf create 50000", failed.tick(0, true, false));
        failed.failSend("socket closed after write");
        equal(CfSession.Mode.STOPPED, failed.snapshot().mode());
        check(failed.snapshot().unresolved());
        equal(null, failed.tick(2_000, true, false));
        throwsType(IllegalStateException.class, () -> failed.start(1, 2_001));
        settle(failed, 2_002, CfSession.Signal.WIN);
        equal(CfSession.Mode.STOPPED, failed.snapshot().mode());
        equal(null, failed.tick(4_000, true, false));
    }

    private static void persistedRecovery() {
        CfSession s = new CfSession();
        s.markUnresolved();
        check(s.snapshot().unresolved());
        equal(CfSession.Phase.RECOVERY_REQUIRED, s.snapshot().phase());
        equal(CfSession.Mode.STOPPED, s.snapshot().mode());
        throwsType(IllegalStateException.class, () -> s.start(1, 0));
        for (CfSession.Signal signal : CfSession.Signal.values()) {
            check(!s.onSignal(signal, 0));
            check(s.snapshot().unresolved());
        }
        s.clearUnresolved();
        check(!s.snapshot().unresolved());
        equal(null, s.tick(0, true, false));
        s.start(1, 0);
        throwsType(IllegalStateException.class, s::markUnresolved);
        equal("cf create 50000", s.tick(0, true, false));
    }

    private static void extremeTimes() {
        CfSession rollback = started(2, 100);
        equal(null, rollback.tick(99, true, false));
        equal(CfSession.Mode.STOPPED, rollback.snapshot().mode());
        equal(0, rollback.snapshot().submitted());

        CfSession minimum = started(2, Long.MIN_VALUE);
        equal("cf create 50000", minimum.tick(Long.MIN_VALUE, true, false));
        settle(minimum, Long.MIN_VALUE + 1, CfSession.Signal.WIN);
        equal(null, minimum.tick(Long.MIN_VALUE + 1_999, true, false));
        equal("cf create 50000", minimum.tick(Long.MIN_VALUE + 2_000, true, false));
        minimum.tick(Long.MAX_VALUE, true, false);
        equal(CfSession.Mode.STOPPED, minimum.snapshot().mode());
        check(minimum.snapshot().unresolved());

        CfSession overflow = started(2, Long.MAX_VALUE - 1);
        equal("cf create 50000", overflow.tick(Long.MAX_VALUE - 1, true, false));
        equal(null, overflow.tick(Long.MAX_VALUE, true, false));
        equal(CfSession.Mode.RUNNING, overflow.snapshot().mode());
        equal(null, overflow.tick(Long.MIN_VALUE, true, false));
        equal(CfSession.Mode.STOPPED, overflow.snapshot().mode());
        check(overflow.snapshot().unresolved());
        equal(null, overflow.tick(0, true, false));

        CfSession backwardsReply = started(2, 100);
        backwardsReply.tick(100, true, false);
        backwardsReply.onSignal(CfSession.Signal.CREATED, 101);
        check(backwardsReply.onSignal(CfSession.Signal.WIN, 99));
        equal(1, backwardsReply.snapshot().wins());
        equal(CfSession.Mode.STOPPED, backwardsReply.snapshot().mode());
        equal(null, backwardsReply.tick(5_000, true, false));
    }

    private static CfSession started(int target, long now) {
        CfSession s = new CfSession();
        s.start(target, now);
        return s;
    }

    private static void settle(CfSession s, long now, CfSession.Signal result) {
        check(s.onSignal(CfSession.Signal.CREATED, now));
        check(s.onSignal(result, now + 1));
    }

    private static void equal(Object expected, Object actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static void check(boolean ok) {
        checks++;
        if (!ok) {
            throw new AssertionError("Check " + checks + " failed");
        }
    }

    private static void throwsType(Class<? extends Throwable> type, Runnable action) {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError("Expected " + type.getName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type.getName());
    }
}
