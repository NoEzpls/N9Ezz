package dev.n9ezz.cf;

import java.util.List;

/** Pure state-machine tests: no Minecraft, network, saved data or real stakes. */
public final class CfSessionTest {
    private static int checks;

    public static void main(String[] args) {
        initialBalanceIsRequired();
        oneHundredSequentialGames();
        creationAndSettlementAreExclusive();
        cancelRequiredThenNoActive();
        unsolicitedNoActiveDoesNotClear();
        manualCheckCannotTimeOutIntoReady();
        insufficientFundsDoesNotRetry();
        stopAllowsLateSettlementButNeverAnotherCreate();
        stopBeforeInitialBalanceCancelsPermission();
        pauseBeforeInitialBalanceClosesQuery();
        timeoutsBlockFurtherCreates();
        noResultBeforeCreation();
        duplicateResultsCountOnce();
        disconnectNeedsExplicitRecovery();
        sendFailureKeepsUnresolved();
        restoredPendingCannotBeBlindlyReset();
        boundariesAndClockRollback();
        transitionsExplainWhatHappened();
        System.out.println("CfSessionTest: PASS (" + checks + " checks)");
    }

    private static void initialBalanceIsRequired() {
        CfSession s = new CfSession();
        s.start(1, 0);
        eq(CfSession.State.VERIFYING, s.snapshot().state(), "initial verification state");
        ok(s.awaitingInitialBalance(), "initial gate accessor");
        eq(null, s.tick(1, true, false), "no create before initial balance");
        ok(s.balanceVerified(2), "initial balance accepted");
        eq(null, s.tick(3, true, true), "screen blocks create");
        eq("cf create 50000", s.tick(4, true, false), "first create");
        ok(s.snapshot().unresolved(), "unresolved set before network send");
    }

    private static void oneHundredSequentialGames() {
        CfSession s = ready(100);
        for (int game = 0; game < 100; game++) {
            long now = 10 + game * 4_000L;
            eq("cf create 50000", s.tick(now, true, false), "create number " + (game + 1));
            eq(null, s.tick(now + 1, true, false), "no overlapping create");
            ok(s.onSignal(CfSession.Signal.CREATED, now + 2), "creation ack " + game);
            eq(null, s.tick(now + 3, true, false), "active stake blocks next");
            ok(s.onSignal(game % 2 == 0 ? CfSession.Signal.WIN : CfSession.Signal.LOSS, now + 4), "result " + game);
            ok(s.snapshot().unresolved(), "result alone does not settle");
            eq(null, s.tick(now + 5, true, false), "result requires verification");
            s.beginBalanceVerification(now + 6);
            ok(s.balanceVerified(now + 7), "settlement balance " + game);
            ok(!s.snapshot().unresolved(), "settlement clears marker");
        }
        eq(100, s.snapshot().submitted(), "maximum exactly 100 creates");
        eq(100, s.snapshot().completed(), "100 confirmed results");
        eq(50, s.snapshot().wins(), "win count");
        eq(50, s.snapshot().losses(), "loss count");
        eq(CfSession.State.STOPPED, s.snapshot().state(), "target reached");
        eq(null, s.tick(500_000, true, false), "never create 101st bet");
    }

    private static void creationAndSettlementAreExclusive() {
        CfSession s = ready(2);
        s.tick(10, true, false);
        ok(!s.balanceVerified(11), "unrequested balance cannot clear create");
        s.onSignal(CfSession.Signal.CREATED, 12);
        ok(!s.balanceVerified(13), "balance cannot clear active stake");
        s.onSignal(CfSession.Signal.WIN, 14);
        ok(s.awaitingSettlement(), "settlement-ready gate accessor");
        ok(!s.balanceVerified(15), "result needs explicit fresh query");
        throwsType(IllegalStateException.class, () -> s.selectStake(75_000), "pending stake amount immutable");
        s.beginBalanceVerification(16);
        ok(s.awaitingSettlement(), "settlement-balance gate accessor");
        s.balanceVerified(17);
        ok(!s.awaitingSettlement(), "settled gate closed");
        eq(null, s.tick(2_009, true, false), "2 second create cadence retained");
        eq("cf create 50000", s.tick(2_010, true, false), "create after cadence and settlement");
    }

    private static void cancelRequiredThenNoActive() {
        CfSession s = ready(100);
        s.tick(10, true, false);
        ok(s.onSignal(CfSession.Signal.CANCEL_REQUIRED, 11), "cancel-required accepted");
        eq(CfSession.State.PAUSED, s.snapshot().state(), "server conflict pauses");
        ok(s.snapshot().unresolved(), "cancel-required preserves marker");
        eq(null, s.tick(2_100, true, false), "conflict never retries create");
        s.beginCancelVerification(2_101);
        ok(s.awaitingCancel(), "one explicit cancellation query");
        ok(s.onSignal(CfSession.Signal.NO_ACTIVE, 2_102), "server no-active overrides local marker");
        ok(!s.snapshot().unresolved(), "reconciled pending cleared");
        eq(0, s.snapshot().completed(), "recovery does not invent a win or loss");
        eq(CfSession.State.PAUSED, s.snapshot().state(), "recovery does not resume AFK");
        eq(null, s.tick(5_000, true, false), "must reconfirm a new run");
        s.start(1, 5_001);
        s.balanceVerified(5_002);
        eq("cf create 50000", s.tick(5_003, true, false), "new confirmed run allowed");
    }

    private static void unsolicitedNoActiveDoesNotClear() {
        CfSession s = ready(1);
        s.tick(10, true, false);
        ok(!s.onSignal(CfSession.Signal.NO_ACTIVE, 11), "unsolicited no-active rejected");
        ok(s.snapshot().unresolved(), "no unsolicited erasure");
        s.onSignal(CfSession.Signal.LIMIT, 12);
        ok(s.snapshot().unresolved(), "legacy LIMIT is not an erasure either");
        s.beginCancelVerification(13);
        ok(s.onSignal(CfSession.Signal.CANCELLED, 14), "configured cancellation acknowledgment accepted");
        ok(!s.snapshot().runAllowed(), "cancel confirmation never resumes");
    }

    private static void manualCheckCannotTimeOutIntoReady() {
        CfSession s = new CfSession();
        s.beginCancelVerification(100);
        throwsType(IllegalStateException.class, () -> s.beginCancelVerification(101), "no duplicate check while waiting");
        s.tick(10_100, true, false);
        ok(s.snapshot().unresolved(), "unknown manual check remains unresolved");
        ok(!s.onSignal(CfSession.Signal.NO_ACTIVE, 10_101), "late stale no-active ignored");
        throwsType(IllegalStateException.class, () -> s.start(1, 10_102), "timed-out cancellation blocks new run");
    }

    private static void insufficientFundsDoesNotRetry() {
        CfSession s = ready(10);
        s.tick(10, true, false);
        ok(s.onSignal(CfSession.Signal.INSUFFICIENT, 11), "rejected create recognized");
        ok(!s.snapshot().unresolved(), "server confirms create rejection");
        eq(CfSession.State.PAUSED, s.snapshot().state(), "insufficient funds pauses");
        eq(null, s.tick(20_000, true, false), "no insufficient funds retry loop");
        eq(0, s.snapshot().completed(), "rejected create is not a completed game");
    }

    private static void stopAllowsLateSettlementButNeverAnotherCreate() {
        CfSession s = ready(100);
        s.tick(10, true, false);
        s.stop("Пользователь остановил сессию");
        ok(!s.snapshot().runAllowed(), "stop clears permission immediately");
        ok(s.onSignal(CfSession.Signal.CREATED, 11), "in-flight creation acknowledgment retained");
        ok(s.onSignal(CfSession.Signal.WIN, 12), "late result can settle exactly one game");
        s.beginBalanceVerification(13);
        ok(s.balanceVerified(14), "late settlement balanced");
        eq(CfSession.State.STOPPED, s.snapshot().state(), "late settlement returns stopped");
        eq(1, s.snapshot().completed(), "late result counted once");
        eq(null, s.tick(20_000, true, false), "late messages cannot restart run");
    }

    private static void stopBeforeInitialBalanceCancelsPermission() {
        CfSession s = new CfSession();
        s.start(100, 1);
        s.stop("stop");
        ok(!s.balanceVerified(2), "late initial balance no longer authorizes run");
        eq(null, s.tick(3, true, false), "zero stakes after pre-send stop");
        eq(0, s.snapshot().submitted(), "nothing submitted");
    }

    private static void timeoutsBlockFurtherCreates() {
        CfSession initial = new CfSession();
        initial.start(10, 0);
        eq(null, initial.tick(10_000, true, false), "initial balance timeout");
        eq(CfSession.State.PAUSED, initial.snapshot().state(), "initial timeout pauses");
        ok(!initial.balanceVerified(10_001), "late initial balance rejected");

        CfSession creation = ready(10);
        creation.tick(10, true, false);
        creation.tick(10_010, true, false);
        eq(CfSession.State.PAUSED, creation.snapshot().state(), "create timeout pauses");
        ok(!creation.onSignal(CfSession.Signal.CREATED, 10_011), "expired create attribution rejected");
        ok(creation.snapshot().unresolved(), "create timeout preserves pending");

        CfSession result = ready(10);
        result.tick(10, true, false);
        result.onSignal(CfSession.Signal.CREATED, 11);
        result.stop("stop before result");
        result.tick(180_011, true, false);
        eq(CfSession.State.PAUSED, result.snapshot().state(), "result times out even after stop");
        ok(result.snapshot().unresolved(), "result timeout preserves pending");

        CfSession settlement = ready(10);
        settlement.tick(10, true, false);
        settlement.onSignal(CfSession.Signal.CREATED, 11);
        settlement.onSignal(CfSession.Signal.LOSS, 12);
        settlement.beginBalanceVerification(13);
        settlement.tick(10_013, true, false);
        ok(!settlement.balanceVerified(10_014), "expired settlement balance rejected");
        ok(settlement.snapshot().unresolved(), "settlement timeout cannot release slot");
        eq(1, settlement.snapshot().completed(), "confirmed result remains counted");
    }

    private static void pauseBeforeInitialBalanceClosesQuery() {
        CfSession s = new CfSession();
        s.start(100, 1);
        s.pause("disconnect during initial balance");
        ok(!s.awaitingInitialBalance(), "pause discards initial balance query");
        ok(!s.balanceVerified(2), "late initial balance after pause rejected");
        s.start(1, 3);
        ok(s.awaitingInitialBalance(), "fresh confirmed run obtains a new balance query");
        eq(null, s.tick(4, true, false), "new run still waits for fresh balance");
    }

    private static void noResultBeforeCreation() {
        CfSession s = ready(10);
        s.tick(10, true, false);
        ok(!s.onSignal(CfSession.Signal.WIN, 11), "unattributable result rejected");
        eq(0, s.snapshot().completed(), "unattributable result not counted");
        ok(!s.snapshot().runAllowed(), "unknown result disables AFK");
        ok(s.snapshot().unresolved(), "unknown result preserves pending");
    }

    private static void duplicateResultsCountOnce() {
        CfSession s = ready(2);
        s.tick(10, true, false);
        s.onSignal(CfSession.Signal.CREATED, 11);
        ok(!s.onSignal(CfSession.Signal.CREATED, 12), "duplicate creation ignored");
        s.onSignal(CfSession.Signal.LOSS, 13);
        ok(!s.onSignal(CfSession.Signal.LOSS, 14), "duplicate result ignored");
        s.beginBalanceVerification(15);
        ok(!s.onSignal(CfSession.Signal.WIN, 16), "contradictory result does not count twice");
        s.balanceVerified(17);
        ok(!s.onSignal(CfSession.Signal.LOSS, 18), "late idle duplicate ignored");
        eq(1, s.snapshot().completed(), "one completion only");
        eq(1, s.snapshot().losses(), "one loss only");
    }

    private static void disconnectNeedsExplicitRecovery() {
        CfSession s = ready(2);
        s.tick(10, true, false);
        s.tick(11, false, false);
        eq(CfSession.State.PAUSED, s.snapshot().state(), "disconnect pauses");
        ok(!s.onSignal(CfSession.Signal.CREATED, 12), "old connection ACK cannot bind new connection");
        eq(null, s.tick(5_000, true, false), "reconnect does not restart");
        ok(s.snapshot().unresolved(), "disconnect keeps pending");
    }

    private static void sendFailureKeepsUnresolved() {
        CfSession s = ready(2);
        s.tick(10, true, false);
        s.failSend("synthetic failure after write");
        eq(CfSession.State.ERROR, s.snapshot().state(), "send failure state");
        ok(s.snapshot().unresolved(), "write may have reached server");
        eq(null, s.tick(5_000, true, false), "no send retry");
        throwsType(IllegalStateException.class, () -> s.start(2, 5_001), "error pending blocks restart");
    }

    private static void restoredPendingCannotBeBlindlyReset() {
        CfSession s = new CfSession();
        s.markUnresolved();
        eq(CfSession.State.PAUSED, s.snapshot().state(), "restored unresolved pauses");
        throwsType(IllegalStateException.class, s::clearUnresolved, "reset cannot invent server confirmation");
        throwsType(IllegalStateException.class, () -> s.start(1, 0), "pending migration blocks run");
        s.beginCancelVerification(1);
        s.onSignal(CfSession.Signal.NO_ACTIVE, 2);
        ok(!s.snapshot().unresolved(), "server recovery clears migrated flag");
        eq(0, s.snapshot().completed(), "migration does not count observed candidates");
    }

    private static void boundariesAndClockRollback() {
        CfSession s = new CfSession();
        throwsType(IllegalArgumentException.class, () -> s.start(0, 0), "zero target rejected");
        throwsType(IllegalArgumentException.class, () -> s.start(501, 0), "over-limit target rejected");
        throwsType(IllegalArgumentException.class, () -> s.selectStake(49_999), "below minimum stake rejected");
        throwsType(IllegalArgumentException.class, () -> s.selectStake(5_000_001), "above maximum stake rejected");
        s.start(1, 100);
        ok(!s.balanceVerified(99), "clock rollback cannot authorize create");
        eq(CfSession.State.PAUSED, s.snapshot().state(), "clock rollback pauses");
        eq(null, s.tick(101, true, false), "forward clock does not resume");
    }

    private static void transitionsExplainWhatHappened() {
        CfSession s = ready(1);
        s.tick(10, true, false);
        s.onSignal(CfSession.Signal.CREATED, 11);
        s.onSignal(CfSession.Signal.WIN, 12);
        s.beginBalanceVerification(13);
        s.balanceVerified(14);
        List<CfSession.Transition> transitions = s.drainTransitions();
        eq(7, transitions.size(), "each real transition logged");
        eq(CfSession.State.VERIFYING, transitions.get(0).to(), "log initial verification");
        eq(CfSession.State.SETTLING, transitions.get(4).to(), "log settlement gate");
        eq(12L, transitions.get(4).atMillis(), "log event time");
        ok(transitions.stream().allMatch(t -> !t.reason().isBlank()), "all transition reasons supplied");
        eq(0, s.drainTransitions().size(), "drain does not repeat transitions");
    }

    private static CfSession ready(int target) {
        CfSession session = new CfSession();
        session.start(target, 0);
        ok(session.balanceVerified(1), "initial balance setup");
        return session;
    }

    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(Object expected, Object actual, String message) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
        }
    }

    private static void throwsType(Class<? extends Throwable> type, Runnable action, String message) {
        checks++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError(message + ": unexpected exception", error);
        }
        throw new AssertionError(message + ": exception was not thrown");
    }
}
