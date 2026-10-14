package dev.n9ezz.cf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller integration tests. Every command goes to an in-memory transport;
 * none of these tests connects to Minecraft or sends a real wager.
 * Message spellings come from the supplied RaidMine sample, with synthetic names.
 */
public final class CfControllerTest {
    private static final String CREATED = "┃ Вы успешно создали ставку.";
    private static final String CANCEL_REQUIRED = "┃ Сначала отмените текущую ставку с помощью /cf cancel";
    private static final String NO_ACTIVE = "┃ У вас нет активной ставки.";
    private static final String WIN = "┃ Победа! Вы выиграли 42,500 \uE23A\uF801 (-15%) у ɢᴏʙʟɪɴ SamplePlayer";
    private static final String LOSS = "┃ Поражение! Вы проиграли 50,000 \uE23A\uF801 игроку sᴇɴɪᴏʀ .SamplePlayer";
    private static final Pattern CONFIRM = Pattern.compile("/cfbot confirm ([A-Za-z0-9_-]+)");
    private static int assertions;
    private static int scenarios;

    public static void main(String[] arguments) throws Exception {
        run("preview never sends a command", CfControllerTest::previewRequiresConfirmation);
        run("confirmation expires", CfControllerTest::confirmationExpires);
        run("stop invalidates confirmation", CfControllerTest::stopInvalidatesConfirmation);
        run("foreign balance cannot authorize a wager", CfControllerTest::foreignBalanceCannotStart);
        run("balance timeout is checked on tick", CfControllerTest::balanceTimeout);
        run("cancel-required then no-active reconciles without retry", CfControllerTest::cancelContradiction);
        run("unrequested no-active is not a recovery confirmation", CfControllerTest::unsolicitedNoActive);
        run("public player chat does not invalidate an active wager", CfControllerTest::publicChatDoesNotInvalidate);
        run("another connection cannot settle our wager", CfControllerTest::foreignConnection);
        run("unknown server CF reply pauses", CfControllerTest::unknownServerReply);
        run("outcome before creation acknowledgement is ambiguous", CfControllerTest::resultBeforeAcknowledgement);
        run("outcome requires a fresh post-result balance", CfControllerTest::settlementGate);
        run("duplicate outcome is never counted twice", CfControllerTest::duplicateOutcome);
        run("stop prevents any later creation", CfControllerTest::stopWhileActive);
        run("disconnect never resumes automatically", CfControllerTest::disconnectStops);
        run("connection replacement pauses", CfControllerTest::connectionReplacement);
        run("insufficient funds ends the attempted run", CfControllerTest::insufficientFunds);
        run("creation timeout retains the unresolved marker", CfControllerTest::creationTimeout);
        run("unfinished wager survives process restart", CfControllerTest::pendingSurvivesRestart);
        run("loss budget includes the next possible loss", CfControllerTest::lossBudget);
        run("packet arrival before prior tick is not clock rollback", CfControllerTest::queuedPacketTiming);
        run("old queued no-active cannot confirm a new cancel", CfControllerTest::staleCancelReply);
        run("a captured ack processed after timeout remains unconfirmed", CfControllerTest::expiredQueuedAck);
        run("absolute balance goal prevents another wager", CfControllerTest::absoluteGoal);
        run("100 wagers remain strictly sequential", CfControllerTest::oneHundredSequential);
        run("status and diagnostics remain available", CfControllerTest::diagnosticsRemainAvailable);
        run("dryrun isolates transport, state, and model", CfControllerTest::dryrunIsolation);
        run("fixed wager above the cap cannot be confirmed", CfControllerTest::fixedAboveCap);
        run("wrong net win amount is not a confirmed result", CfControllerTest::wrongWinAmount);
        run("manual CF menu invalidates attribution", CfControllerTest::manualMenuInvalidates);
        run("no-active contradiction cannot be repaired by an unsolicited win", CfControllerTest::noActiveThenWin);
        run("late settlement query timeout pauses without a fatal error", CfControllerTest::settlementTimeout);
        run("zero loss budget is rejected", CfControllerTest::zeroLossLimit);
        System.out.println("CfControllerTest: " + scenarios + " scenarios, " + assertions + " assertions passed");
    }

    private static void previewRequiresConfirmation() throws Exception {
        try (Harness h = new Harness()) {
            h.chat("/cfbot fixed 50000");
            h.chat("/cfbot stake 50000");
            h.chat("/cfbot losslimit 100000");
            h.preview(100);
            h.tick(5_000);
            check(h.sent.isEmpty(), "preview must not query balance or create a wager");
            String feedback = String.join("\n", h.feedback);
            check(feedback.contains("100") && feedback.contains("50000") && feedback.contains("100000"),
                    "preview must display count, stake, and loss budget");
        }
    }

    private static void confirmationExpires() throws Exception {
        try (Harness h = new Harness()) {
            String token = h.preview(1);
            h.tick(60_000);
            h.chat("/cfbot confirm " + token);
            h.tick(1);
            check(h.sent.isEmpty(), "expired confirmation must not start any commands");
        }
    }

    private static void stopInvalidatesConfirmation() throws Exception {
        try (Harness h = new Harness()) {
            String token = h.preview(1);
            h.chat("/cfbot stop");
            h.chat("/cfbot confirm " + token);
            h.tick(1);
            check(h.sent.isEmpty(), "stop must invalidate a previously displayed confirmation");
        }
    }

    private static void foreignBalanceCannotStart() throws Exception {
        try (Harness h = new Harness()) {
            h.confirm(1);
            h.awaitCommand("bal", 1);
            h.message("Баланс игрока OtherPlayer: 10,000,000$ | 0 рейдов | 0 коинов");
            h.message("ɢ ᴋɪɴɢ OtherPlayer » Ваш баланс: 10,000,000$ | 0 рейдов | 0 коинов");
            h.tick(10_100);
            check(h.creates() == 0, "a foreign balance must not authorize a wager");
            h.assertPaused();
        }
    }

    private static void balanceTimeout() throws Exception {
        try (Harness h = new Harness()) {
            h.confirm(1);
            h.awaitCommand("bal", 1);
            h.tick(10_100);
            h.assertPaused();
            h.tick(60_000);
            check(h.count("bal") == 1 && h.creates() == 0, "balance timeout must not retry forever");
        }
    }

    private static void cancelContradiction() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(100);
            h.message(CANCEL_REQUIRED);
            h.assertPaused();
            check(h.controller.snapshot().unresolved(), "server cancel request means the wager may still exist");
            h.tick(20_000);
            check(h.creates() == 1, "cancel request must not loop cf create");
            check(!h.controller.onChat("/cf cancel"), "manual cancel must still reach the server");
            h.message(NO_ACTIVE);
            check(!h.controller.snapshot().unresolved(), "authoritative no-active reply must clear stale pending state");
            check(h.controller.snapshot().completed() == 0, "no-active must not invent a game outcome");
            h.tick(20_000);
            check(h.creates() == 1, "reconciliation requires a new explicit start confirmation");
            check("false".equals(h.state().getProperty("unresolved")), "reconciliation must persist to disk");
            check(!h.controller.snapshot().reason().isBlank(), "paused recovery must explain why no new wager is sent");
        }
    }

    private static void unsolicitedNoActive() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CREATED);
            h.message(NO_ACTIVE);
            check(h.controller.snapshot().unresolved(), "unrequested no-active cannot silently settle an active wager");
            check(h.controller.snapshot().completed() == 0, "no-active is not a result");
            h.tick(20_000);
            check(h.creates() == 1, "ambiguous no-active must not permit another wager");
        }
    }

    private static void publicChatDoesNotInvalidate() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CREATED);
            h.message("ɢ ᴋɪɴɢ OtherPlayer » all /cf");
            h.message("ɢ ᴋɪɴɢ OtherPlayer » " + WIN);
            check(h.controller.snapshot().state() == CfSession.State.ACTIVE, "public CF chat must leave an active wager attributed");
            check(h.controller.snapshot().completed() == 0, "a quoted win must not increment games");
            h.settle(true);
            check(h.controller.snapshot().completed() == 1, "the following personal win must be accepted");
            check("1".equals(h.state().getProperty("wins")), "only one real result belongs in persistent statistics");
            String log = Files.readString(h.directory.resolve("messages.tsv"));
            check(log.contains("OBSERVED_CANDIDATE"), "public CF candidates should remain available in the diagnostic log");
        }
    }

    private static void foreignConnection() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CREATED);
            h.controller.onSystemMessage(WIN, new Object(), h.now);
            check(h.controller.snapshot().completed() == 0, "another connection cannot settle the pending wager");
            check(h.controller.snapshot().unresolved(), "foreign connection cannot clear unresolved");
        }
    }

    private static void unknownServerReply() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CREATED);
            h.message("┃ Статус вашей ставки неизвестен, обратитесь к администратору.");
            h.assertPaused();
            check(h.controller.snapshot().unresolved(), "unknown server reply must retain pending state");
            h.tick(20_000);
            check(h.creates() == 1, "unknown status must not retry creation");
        }
    }

    private static void resultBeforeAcknowledgement() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(WIN);
            h.assertPaused();
            check(h.controller.snapshot().completed() == 0, "result without acknowledged creation must not count");
            check(h.controller.snapshot().unresolved(), "out-of-order result must preserve uncertainty");
        }
    }

    private static void settlementGate() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CREATED);
            h.message(WIN);
            h.awaitCommand("bal", 2);
            check(h.controller.snapshot().unresolved(), "a result alone must not release the settlement gate");
            h.tick(5_000);
            check(h.creates() == 1, "no next wager before the post-result balance");
            h.balance += 42_500;
            h.replyBalance();
            h.awaitCreates(2);
        }
    }

    private static void duplicateOutcome() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(1);
            h.message(CREATED);
            h.message(WIN);
            h.message(WIN);
            h.awaitCommand("bal", 2);
            h.balance += 42_500;
            h.replyBalance();
            h.message(WIN);
            check(h.controller.snapshot().completed() == 1, "duplicate result must not count twice");
            check("1".equals(h.state().getProperty("wins")), "duplicate result must not train or persist a second win");
        }
    }

    private static void stopWhileActive() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(3);
            h.message(CREATED);
            h.chat("/cfbot stop");
            h.message(WIN);
            h.tick(2_100);
            if (h.count("bal") > 1) {
                h.balance += 42_500;
                h.replyBalance();
            }
            h.tick(200_000);
            check(h.creates() == 1, "late acknowledged settlement must not reverse stop");
        }
    }

    private static void disconnectStops() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(3);
            h.message(CREATED);
            h.controller.onDisconnect("test disconnect");
            h.connection = null;
            h.tick(10_000);
            h.connection = new Object();
            h.tick(200_000);
            h.assertPaused();
            check(h.creates() == 1, "reconnect must not resume an old run");
            check(h.controller.snapshot().unresolved(), "disconnect must retain the uncertain wager");
        }
    }

    private static void connectionReplacement() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(3);
            h.connection = new Object();
            h.tick(1);
            h.assertPaused();
            h.tick(200_000);
            check(h.creates() == 1, "a new server connection must not inherit run authorization");
        }
    }

    private static void insufficientFunds() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(100);
            h.message("┃ У Вас недостаточно средств для создания ставки.");
            h.tick(20_000);
            check(h.creates() == 1, "insufficient funds must not cause automatic retries");
            check(!h.controller.snapshot().unresolved(), "rejected creation is not an outstanding wager");
            check(h.controller.snapshot().completed() == 0, "rejected creation is not a completed game");
        }
    }

    private static void creationTimeout() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(100);
            h.tick(10_100);
            h.assertPaused();
            h.tick(60_000);
            check(h.creates() == 1, "missing acknowledgement must not be retried");
            check(h.controller.snapshot().unresolved(), "unknown send status must retain unresolved");
        }
    }

    private static void pendingSurvivesRestart() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(100);
            check("true".equals(h.state().getProperty("unresolved")), "pending marker must reach disk before sending");
            h.controller = new CfController(h.directory, h, () -> h.now);
            h.tick(200_000);
            check(h.controller.snapshot().unresolved(), "restart must restore pending state");
            check(h.creates() == 1, "restart must never automatically issue another wager");
            h.previewMayFail(1);
            h.tick(20_000);
            check(h.creates() == 1, "new start is blocked until old wager is verified");
        }
    }

    private static void lossBudget() throws Exception {
        try (Harness h = new Harness()) {
            h.chat("/cfbot losslimit 75000");
            h.startBet(100);
            h.message(CREATED);
            h.settle(false);
            h.tick(20_000);
            check(h.creates() == 1, "remaining budget 25000 cannot fund a possible loss of 50000");
            check(h.controller.snapshot().completed() == 1, "the first loss still counts exactly once");
        }
    }

    private static void absoluteGoal() throws Exception {
        try (Harness h = new Harness()) {
            h.chat("/cfbot goal 10042500");
            h.startBet(100);
            h.message(CREATED);
            h.settle(true);
            h.tick(20_000);
            check(h.creates() == 1, "reaching the absolute coins balance must stop new wagers");
        }
    }

    private static void oneHundredSequential() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(100);
            for (int game = 1; game <= 100; game++) {
                h.awaitCreates(game);
                check(h.creates() == game, "only one wager may be in flight, game " + game);
                check(h.controller.snapshot().unresolved(), "each created wager must have a durable unresolved marker");
                h.message(CREATED);
                h.tick(10);
                check(h.creates() == game, "creation acknowledgement does not authorize a second wager");
                h.settle(game % 2 == 0);
                check(h.controller.snapshot().completed() == game, "completed count must follow server outcomes exactly");
            }
            h.tick(500_000);
            check(h.creates() == 100, "start 100 must never submit a 101st wager");
            check(h.controller.snapshot().completed() == 100, "exactly 100 games should have settled");
            check(!h.controller.snapshot().unresolved(), "final settlement must clear pending state");
            for (int i = 1; i < h.sent.size(); i++) {
                check(h.sent.get(i).at() - h.sent.get(i - 1).at() >= 2_000,
                        "all automated commands must respect the 2-second command interval");
            }
            check(h.sent.stream().filter(s -> s.command().startsWith("cf create "))
                    .allMatch(s -> s.command().equals("cf create 50000")),
                    "fixed 50000 and upper limit 50000 must bound every wager");
        }
    }

    private static void diagnosticsRemainAvailable() throws Exception {
        try (Harness h = new Harness()) {
            h.chat("/cfbot selftest");
            check(h.sent.isEmpty(), "selftest is local and must never send a wager or balance query");
            h.chat("/cfbot balancecheck");
            h.awaitCommand("bal", 1);
            h.replyBalance();
            h.chat("/cfbot status");
            h.chat("/cfbot stats");
            h.chat("/cfbot ai");
            check(h.creates() == 0, "diagnostics must never place wagers");
            check(!h.feedback.isEmpty(), "status and diagnostics must produce user feedback");
        }
    }

    private static void dryrunIsolation() throws Exception {
        try (Harness h = new Harness()) {
            byte[] stateBefore = Files.readAllBytes(h.directory.resolve("state.properties"));
            byte[] configBefore = Files.readAllBytes(h.directory.resolve("config.properties"));
            CfSession.Snapshot sessionBefore = h.controller.snapshot();
            h.rejectSends = true;
            h.chat("/cfbot dryrun 100");
            check(h.sent.isEmpty(), "dryrun must never access the live transport");
            check(h.feedback.stream().anyMatch(s -> s.contains("DRYRUN OK: 100")), "dryrun must actually finish 100 synthetic games");
            check(Arrays.equals(stateBefore, Files.readAllBytes(h.directory.resolve("state.properties"))),
                    "dryrun must leave real counters, preferences, and neural weights byte-for-byte unchanged");
            check(Arrays.equals(configBefore, Files.readAllBytes(h.directory.resolve("config.properties"))),
                    "dryrun must not replace the real configuration");
            check(sessionBefore.equals(h.controller.snapshot()), "dryrun must not mutate the live session");
            try (var directories = Files.list(h.directory.resolve("simulations"))) {
                List<Path> simulations = directories.toList();
                check(simulations.size() == 1, "simulation has an isolated output directory");
                Path simulation = simulations.get(0);
                check(Files.exists(simulation.resolve("SIMULATION.txt")), "synthetic data must be visibly labelled");
                Properties simulated = new Properties();
                try (var reader = Files.newBufferedReader(simulation.resolve("state.properties"))) { simulated.load(reader); }
                check("50".equals(simulated.getProperty("wins")) && "50".equals(simulated.getProperty("losses")),
                        "all 100 synthetic outcomes must remain confined to simulation state");
            }
        }
    }

    private static void fixedAboveCap() throws Exception {
        try (Harness h = new Harness()) {
            h.chat("/cfbot fixed 100000");
            h.chat("/cfbot stake 50000");
            int before = h.feedback.size();
            h.chat("/cfbot start 100");
            check(h.feedback.subList(before, h.feedback.size()).stream().noneMatch(s -> CONFIRM.matcher(s).find()),
                    "fixed stake above cap must not produce an actionable confirmation token");
            h.tick(20_000);
            check(h.sent.isEmpty(), "fixed stake above cap must not query or create");
        }
    }

    private static void wrongWinAmount() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CREATED);
            h.message(WIN.replace("42,500", "30,000"));
            check(h.controller.snapshot().completed() == 0, "a win incompatible with the known 15% fee must not count");
            check("0".equals(h.state().getProperty("wins")), "wrong-size win must not train or persist a win");
            h.assertPaused();
            h.tick(20_000);
            check(h.creates() == 1 && h.controller.snapshot().unresolved(), "wrong amount must leave the wager unresolved");
        }
    }

    private static void manualMenuInvalidates() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CREATED);
            check(!h.controller.onChat("/cf"), "manual CF menu command must reach the server");
            h.message(WIN);
            h.replyBalance();
            check(h.controller.snapshot().completed() == 0, "manual menu interference invalidates attribution of the next result");
            check("0".equals(h.state().getProperty("wins")), "ambiguous menu result is observed only");
            h.tick(20_000);
            check(h.creates() == 1 && h.controller.snapshot().unresolved(), "manual interference needs explicit recovery");
        }
    }

    private static void noActiveThenWin() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CREATED);
            h.message(NO_ACTIVE);
            h.message(WIN);
            h.balance += 42_500;
            h.replyBalance();
            h.tick(20_000);
            check(h.creates() == 1, "a late win must not resume a contradictory server state");
            check(h.controller.snapshot().completed() == 0, "contradictory unsolicited replies must not become a confirmed outcome");
            check(h.controller.snapshot().unresolved(), "contradiction remains unresolved until an explicit cancel check");
            h.assertPaused();
        }
    }

    private static void settlementTimeout() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CREATED);
            h.message(WIN);
            h.screenOpen = true; // The client cannot issue the queued post-result /bal.
            h.tick(10_100);
            h.screenOpen = false;
            h.tick(1);
            check(h.controller.snapshot().state() == CfSession.State.PAUSED,
                    "expired settlement queue is recoverable uncertainty, not an internal ERROR: " + h.controller.snapshot());
            check(h.controller.snapshot().unresolved(), "settlement timeout must keep pending state");
            h.tick(20_000);
            check(h.creates() == 1 && h.count("bal") == 1, "expired settlement must not issue a late query or another creation");
        }
    }

    private static void zeroLossLimit() throws Exception {
        try (Harness h = new Harness()) {
            h.chat("/cfbot losslimit 100000");
            h.chat("/cfbot losslimit 0");
            check("100000".equals(h.state().getProperty("prefs.losslimit")), "invalid zero limit must preserve the prior positive budget");
            check(h.sent.isEmpty(), "settings validation must not send commands");
        }
    }

    private static void queuedPacketTiming() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            long receivedAt = h.now + 1;
            h.tick(50); // Packet arrived after the prior queue drain, before this tick.
            h.controller.onSystemMessage(CREATED, h.connection, receivedAt);
            check(h.controller.snapshot().state() == CfSession.State.ACTIVE && h.controller.snapshot().runAllowed(),
                    "normal ACK queue latency must not trigger clock rollback");
            receivedAt = h.now + 1;
            h.tick(50);
            h.controller.onSystemMessage(WIN, h.connection, receivedAt);
            check(h.controller.snapshot().completed() == 1 && h.controller.snapshot().runAllowed(),
                    "normal result queue latency must retain attribution");
            h.awaitCommand("bal", 2);
            receivedAt = h.now + 1;
            h.tick(50);
            h.balance += 42_500;
            h.controller.onSystemMessage("Ваш баланс: " + String.format(Locale.ROOT, "%,d", h.balance)
                    + "$ | 0 рейдов | 0 коинов", h.connection, receivedAt);
            check(!h.controller.snapshot().unresolved() && h.controller.snapshot().runAllowed(),
                    "a fresh requested balance can be older than the previous processing tick");
        }
    }

    private static void staleCancelReply() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            h.message(CANCEL_REQUIRED);
            long receivedAt = h.now;
            h.now += 10;
            check(!h.controller.onChat("/cf cancel"), "manual cancel is forwarded");
            h.controller.onSystemMessage(NO_ACTIVE, h.connection, receivedAt);
            check(h.controller.snapshot().unresolved(), "a reply captured before cancel cannot clear it");
            check(h.controller.snapshot().state() == CfSession.State.VERIFYING, "old reply must leave current check waiting");
            h.message(NO_ACTIVE);
            check(!h.controller.snapshot().unresolved(), "a new correlated reply still clears the pending marker");
            check(!h.controller.snapshot().runAllowed(), "verification does not restart the run");
        }
    }

    private static void expiredQueuedAck() throws Exception {
        try (Harness h = new Harness()) {
            h.startBet(2);
            long receivedAt = h.now + 1;
            h.now += 10_100;
            h.controller.onSystemMessage(CREATED, h.connection, receivedAt);
            check(h.controller.snapshot().unresolved() && !h.controller.snapshot().runAllowed(),
                    "an unprocessed ACK cannot bypass the processing deadline");
            h.tick(10_000);
            check(h.creates() == 1, "late queued ACK never triggers retry");
        }
    }

    private static void run(String name, Checked test) throws Exception {
        try {
            test.run();
            scenarios++;
        } catch (Throwable failure) {
            throw new AssertionError("Controller scenario failed: " + name, failure);
        }
    }

    private static void check(boolean condition, String explanation) {
        assertions++;
        if (!condition) throw new AssertionError(explanation);
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private record Sent(String command, long at) { }

    private static final class Harness implements CfController.Transport, AutoCloseable {
        final Path directory = Files.createTempDirectory("cf022-controller-test-");
        final List<Sent> sent = new ArrayList<>();
        final List<String> feedback = new ArrayList<>();
        long now = 100_000;
        long balance = 10_000_000;
        Object connection = new Object();
        boolean screenOpen;
        boolean rejectSends;
        CfController controller;

        Harness() throws Exception { controller = new CfController(directory, this, () -> now); }
        @Override public Object connection() { return connection; }
        @Override public boolean screenOpen() { return screenOpen; }
        @Override public void sendCommand(String command) throws Exception {
            if (rejectSends) throw new AssertionError("The live transport was used in dryrun: " + command);
            if (command.startsWith("cf create ")) {
                check("true".equals(state().getProperty("unresolved")),
                        "pending must be persisted before transport sees create");
            }
            sent.add(new Sent(command, now));
        }
        @Override public void feedback(String text) { feedback.add(text); }

        void chat(String text) { check(controller.onChat(text), "cfbot command must be consumed locally: " + text); }
        void tick(long delta) { now += delta; controller.onTick(now); }
        void message(String text) { now++; controller.onSystemMessage(text, connection, now); }
        long count(String command) { return sent.stream().filter(s -> s.command().equals(command)).count(); }
        long creates() { return sent.stream().filter(s -> s.command().startsWith("cf create ")).count(); }

        String preview(int target) {
            int from = feedback.size();
            chat("/cfbot start " + target);
            for (int i = feedback.size() - 1; i >= from; i--) {
                Matcher matcher = CONFIRM.matcher(feedback.get(i));
                if (matcher.find()) return matcher.group(1);
            }
            throw new AssertionError("Missing confirmation token in: " + feedback.subList(from, feedback.size()));
        }

        void previewMayFail(int target) { chat("/cfbot start " + target); }
        void confirm(int target) { chat("/cfbot confirm " + preview(target)); }
        void startBet(int target) {
            confirm(target);
            awaitCommand("bal", 1);
            replyBalance();
            awaitCreates(1);
        }
        void replyBalance() {
            message("Ваш баланс: " + String.format(Locale.ROOT, "%,d", balance) + "$ | 0 рейдов | 0 коинов");
        }
        void settle(boolean win) {
            long priorBalanceQueries = count("bal");
            message(win ? WIN : LOSS);
            awaitCommand("bal", priorBalanceQueries + 1);
            balance += win ? 42_500 : -50_000;
            replyBalance();
        }
        void awaitCommand(String command, long total) { await(() -> count(command) >= total, command + " x" + total); }
        void awaitCreates(long total) { await(() -> creates() >= total, "cf create x" + total); }
        void await(BooleanSupplier ready, String description) {
            long deadline = now + 8_000;
            while (!ready.getAsBoolean() && now < deadline) tick(100);
            check(ready.getAsBoolean(), "command not emitted: " + description + "; " + controller.snapshot()
                    + "; feedback=" + feedback);
        }
        void assertPaused() {
            check(controller.snapshot().state() == CfSession.State.PAUSED
                            || controller.snapshot().state() == CfSession.State.ERROR,
                    "uncertainty must pause or error: " + controller.snapshot());
            check(!controller.snapshot().reason().isBlank(), "a pause requires a reason");
        }
        Properties state() throws IOException {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(directory.resolve("state.properties"), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return properties;
        }
        @Override public void close() throws IOException {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
