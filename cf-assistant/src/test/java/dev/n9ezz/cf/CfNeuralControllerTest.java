package dev.n9ezz.cf;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** Integration scenarios use artificial server replies and an in-memory transport only. */
public final class CfNeuralControllerTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        absoluteGoalAndPreferencesPersistWithoutStarting();
        freshCoinsBalanceGatesEveryResolvedWager();
        alreadyReachedTargetNeverCreatesWager();
        unknownOrInsufficientCoinsNeverFundWager();
        unconfiguredBalancePreventsGoalSessions();
        balanceTimeoutNeverRetries();
        balanceProbeRecordsWithDisabledTemplate();
        remainingLossBudgetPreventsNextWager();
        unvalidatedModelCannotEnterAutomaticMode();
        onlyAcknowledgedSameConnectionOutcomesTrain();
        learnedModelCountersAndMarkerPersistTogether();
        corruptModelBlocksNewSession();
        actualLearnedModelControlsBoundedAutomaticStake();
        idleManualCfBreaksLearnedSequence();
        discardedPendingWagerBreaksLearnedSequence();
        manualBalanceInvalidatesFinalBalanceQuery();
        System.out.println("CfNeuralControllerTest: " + assertions
                + " assertions passed (offline synthetic replies and real local model training)");
    }

    private static void absoluteGoalAndPreferencesPersistWithoutStarting() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("goal 120000.25");
            f.command("stake 1500000");
            f.command("losslimit 100000");
            Properties state = f.state();
            check(state.getProperty("prefs.goal").equals("120000.25"), "absolute final-balance goal persists exactly");
            check(state.getProperty("prefs.maxStake").equals("1500000"), "optional maximum stake persists");
            check(state.getProperty("prefs.losslimit").equals("100000"), "optional loss budget persists");
            FakeTransport transport = new FakeTransport(f.directory);
            CfController restored = new CfController(f.directory, transport);
            restored.onTick(f.now + 10_000);
            check(transport.sent.isEmpty(), "loading preferences never starts a session");
            restored.onChat("/cfbot start 1");
            long time = Math.max(f.now, CfController.monotonicMillis());
            restored.onTick(time);
            restored.onSystemMessage(coins("120000.25"), transport.connection, time);
            restored.onTick(time + 2_000);
            check(transport.sent.equals(List.of("bal")), "restored goal is final balance, not profit added to initial balance");
            check(restored.snapshot().mode() == CfSession.Mode.STOPPED, "restored reached goal stops");
        }
    }

    private static void freshCoinsBalanceGatesEveryResolvedWager() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("goal 200000");
            f.command("start 2");
            f.tick();
            check(f.transport.sent.equals(List.of("bal")), "first command asks server for exact coins balance");
            check(!pending(f.state()), "balance request cannot reserve a wager marker");
            f.message("Рейды: 900000000");
            f.message("Койны: 900000000");
            f.controller.onSystemMessage(coins("999999999"), new Object(), f.now);
            f.advance(2_000);
            check(f.transport.sent.size() == 1, "unrelated currencies and foreign connections cannot fund a wager");
            f.message(coins("100,000"));
            f.tick();
            check(f.transport.sent.equals(List.of("bal", "cf create 50000")), "named coins amount alone permits fixed training wager");
            f.message(coins("999,999,999"));
            f.message("CREATED");
            f.advance(2_000);
            check(f.transport.sent.size() == 2, "never requests balance while acknowledged wager is unresolved");
            f.message("WIN 999999999 FixturePlayer");
            check(number(f.state(), "nn.trained") == 1, "confirmed win trains once");
            f.tick();
            check(f.transport.sent.equals(List.of("bal", "cf create 50000", "bal")), "confirmed outcome needs fresh server balance before another wager");
            f.message(coins("200000"));
            f.advance(2_000);
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "reaching final coins balance stops");
            check(f.transport.sent.size() == 3, "payout text is not substituted for the final balance");
            check(f.transport.balanceMarkerChecks == 2 && f.transport.wagerMarkerChecks == 1,
                    "balance requests only see clear marker, wager send sees durable pending marker");
        }
    }

    private static void alreadyReachedTargetNeverCreatesWager() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("goal 100000");
            f.command("start 500");
            f.tick();
            f.message(coins("100000"));
            f.advance(50_000);
            check(f.transport.sent.equals(List.of("bal")), "goal already reached needs zero games");
            check(!pending(f.state()), "reached goal creates no unresolved wager");
        }
    }

    private static void unknownOrInsufficientCoinsNeverFundWager() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("goal 1000000");
            f.command("start 2");
            f.tick();
            f.message("Баланс: монеты 1.5m; рейды 999999999; койны 999999999");
            f.advance(2_000);
            check(f.transport.sent.equals(List.of("bal")), "abbreviated unknown coins amount cannot spend");
            f.message(coins("49999.99"));
            f.tick();
            check(f.transport.sent.equals(List.of("bal")), "other large currency values do not supplement insufficient coins");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "insufficient known coins stops session");
        }
    }

    private static void unconfiguredBalancePreventsGoalSessions() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.command("goal 1000000");
            f.command("start 2");
            f.tick();
            check(f.transport.sent.isEmpty(), "known command without exact balance template cannot start goal session");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "goal session rejected before reserve or send");
        }
    }

    private static void balanceTimeoutNeverRetries() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("goal 1000000");
            f.command("start 2");
            f.tick();
            f.advance(10_000);
            f.message(coins("999999"));
            f.advance(50_000);
            check(f.transport.sent.equals(List.of("bal")), "timeout and late balance cannot retry or resume");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "balance deadline stops session");
        }
    }

    private static void balanceProbeRecordsWithDisabledTemplate() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.command("balance");
            f.message("Баланс игрока: монеты 123456; рейды 77; койны 99");
            String log = Files.readString(f.directory.resolve("messages.tsv"));
            check(log.contains("BALANCE_SAMPLE\tБаланс игрока: монеты 123456; рейды 77; койны 99"),
                    "probe records actual multi-currency example even without a configured regex");
            check(f.transport.sent.equals(List.of("bal")), "probe sends only balance query");
            f.advance(20_000);
            check(f.transport.sent.size() == 1 && !pending(f.state()), "probe timeout cannot create or retry wager");
        }
    }

    private static void remainingLossBudgetPreventsNextWager() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("goal 2000000");
            f.command("losslimit 100000");
            f.command("start 3");
            f.tick();
            f.message(coins("1000000"));
            f.advance(2_000);
            f.message("CREATED");
            f.message("LOSS 50000 FixturePlayer");
            f.advance(2_000);
            f.message(coins("940000"));
            f.advance(2_000);
            check(f.transport.sent.equals(List.of("bal", "cf create 50000", "bal")),
                    "minimum wager cannot exceed remaining 40000 loss budget despite sufficient total balance");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "remaining budget below minimum stops");
        }
    }

    private static void unvalidatedModelCannotEnterAutomaticMode() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("goal 2000000");
            f.command("stake 5000000");
            f.command("auto 500");
            f.tick();
            check(f.transport.sent.isEmpty(), "untrained model cannot enter auto or request balance");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "inadequate validation refuses automatic session");
            check(number(f.state(), "nn.trained") == 0, "changing preferences cannot synthesize training samples");
        }
    }

    private static void onlyAcknowledgedSameConnectionOutcomesTrain() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("start 2");
            f.tick();
            f.controller.onSystemMessage("CREATED", new Object(), f.now);
            f.controller.onSystemMessage("WIN 50000 FixturePlayer", new Object(), f.now);
            check(number(f.state(), "nn.trained") == 0, "foreign ACK and outcome cannot train model");
            f.message("WIN 50000 FixturePlayer");
            check(number(f.state(), "nn.trained") == 0, "result without creation acknowledgement cannot train");
            check(pending(f.state()), "unacknowledged outcome retains unresolved marker");
        }
        try (Fixture f = new Fixture(true)) {
            f.command("start 2");
            f.tick();
            f.message("CREATED");
            f.message("CREATED");
            check(number(f.state(), "nn.trained") == 0, "duplicate ACK is never a model label");
            f.message("WIN 50000 FixturePlayer");
            f.message("WIN 50000 FixturePlayer");
            f.controller.onSystemMessage("LOSS 50000 FixturePlayer", new Object(), f.now);
            Properties state = f.state();
            check(number(state, "nn.trained") == 1 && number(state, "wins") == 1 && number(state, "losses") == 0,
                    "exactly one confirmed outcome updates model and counters");
            check(!pending(state), "confirmed result clears marker in the same state snapshot");
        }
    }

    private static void learnedModelCountersAndMarkerPersistTogether() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("start 2");
            f.tick();
            f.message("CREATED");
            f.message("LOSS 50000 FixturePlayer");
            Properties completed = f.state();
            check(number(completed, "nn.trained") == 1 && number(completed, "losses") == 1 && !pending(completed),
                    "one atomic state file contains learned sample, result total and cleared marker");
            f.advance(2_000);
            Properties next = f.state();
            check(pending(next) && number(next, "nn.trained") == 1 && number(next, "losses") == 1,
                    "next send preserves learned state while durably marking the new unresolved wager");
            check(CfNeuralModel.fromProperties(neural(next)).predict().trainedSamples() == 1,
                    "saved weights deserialize consistently with persisted training counter");
            FakeTransport replacement = new FakeTransport(f.directory);
            CfController restored = new CfController(f.directory, replacement);
            restored.onChat("/cfbot start 2");
            restored.onTick(f.now + 50_000);
            check(restored.snapshot().unresolved() && replacement.sent.isEmpty(), "restart preserves model but cannot retry unresolved wager");
            try (var paths = Files.list(f.directory)) {
                check(paths.noneMatch(path -> path.getFileName().toString().startsWith("state-")),
                        "completed atomic replacements leave no temporary state files");
            }
        }
    }

    private static void corruptModelBlocksNewSession() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("goal 2000000");
            Properties broken = f.state();
            broken.setProperty("nn.outputBias", "NaN");
            write(f.directory.resolve("state.properties"), broken);
            FakeTransport replacement = new FakeTransport(f.directory);
            CfController restored = new CfController(f.directory, replacement);
            restored.onChat("/cfbot start 2");
            restored.onTick(f.now + 10_000);
            check(replacement.sent.isEmpty(), "corrupt model must not silently reset and begin spending");
            check(restored.snapshot().mode() == CfSession.Mode.STOPPED, "corrupt learned state blocks session");
        }
    }

    private static void actualLearnedModelControlsBoundedAutomaticStake() throws Exception {
        try (Fixture f = new Fixture(true)) {
            trainAlternating(f);
            CfNeuralModel.Prediction prediction = CfNeuralModel.fromProperties(neural(f.state())).predict();
            check(prediction.usable() && prediction.probability() >= 0.60,
                    "genuinely learned synthetic alternating sequence passes held-before-training evaluation");
            f.command("goal 20000000");
            f.command("stake 1500000");
            f.command("auto 2");
            f.advance(2_000);
            check(f.transport.sent.get(500).equals("bal"), "validated auto still requires a fresh balance");
            f.message(coins("10000000"));
            f.advance(2_000);
            String selected = f.transport.sent.get(501);
            long stake = Long.parseLong(selected.substring("cf create ".length()));
            check(stake > CfSession.STAKE && stake <= 1_500_000, "actual model chooses elevated stake inside explicit user ceiling");
            check(f.controller.snapshot().stake() == stake && pending(f.state()), "selected amount and pending marker agree before transport send");
            f.message("CREATED");
            f.message("WIN 2550000 FixturePlayer");
            f.advance(2_000);
            f.message(coins("11000000"));
            f.advance(2_000);
            check(f.transport.sent.size() == 503, "model's low next-win estimate stops instead of placing a loss-chasing wager");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "low estimate requires new observed data, not a lucky timer");
            check(number(f.state(), "nn.trained") == 501, "automatic confirmed outcome joins same real trained model");
        }
    }

    private static void idleManualCfBreaksLearnedSequence() throws Exception {
        try (Fixture f = new Fixture(true)) {
            trainAlternating(f);
            f.command("goal 20000000");
            check(!f.controller.onChat("/cf create 50000"), "manual CF remains a server command");
            f.command("auto 1");
            f.advance(2_000);
            check(f.transport.sent.size() == 500, "unobserved manual game breaks history needed for auto qualification");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "manual CF cannot retain trained sequence context");
        }
    }

    private static void discardedPendingWagerBreaksLearnedSequence() throws Exception {
        try (Fixture f = new Fixture(true)) {
            trainAlternating(f);
            f.command("start 1");
            f.advance(2_000);
            f.command("stop");
            f.command("reset checked");
            f.command("goal 20000000");
            f.command("auto 1");
            f.advance(2_000);
            check(f.transport.sent.size() == 501, "discarding an unknown pending outcome invalidates sequence qualification");
            check(!pending(f.state()) && f.controller.snapshot().mode() == CfSession.Mode.STOPPED,
                    "explicit reset clears wager marker but cannot invent outcome history");
        }
    }

    private static void manualBalanceInvalidatesFinalBalanceQuery() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.command("goal 2000000");
            f.command("start 1");
            f.tick();
            f.message(coins("1000000"));
            f.advance(2_000);
            f.message("CREATED");
            f.message("LOSS 50000 FixturePlayer");
            f.advance(2_000);
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "count target stops before final balance query reply");
            check(!f.controller.onChat("/bal OtherPlayer"), "manual other-player balance remains server-bound");
            f.message(coins("9000000"));
            String log = Files.readString(f.directory.resolve("messages.tsv"));
            check(!log.contains("BALANCE_CONFIRMED\t" + coins("9000000")),
                    "manual query while stopped invalidates pending final balance attribution");
        }
    }

    private static void trainAlternating(Fixture f) throws Exception {
        f.command("start 500");
        for (int i = 0; i < 500; i++) {
            f.tick();
            check(f.transport.sent.size() == i + 1, "synthetic training creates exactly one wager per confirmed game");
            f.message("CREATED");
            f.message((i % 2 == 0 ? "WIN" : "LOSS") + " 50000 FixturePlayer");
            f.now += 2_000;
        }
        check(number(f.state(), "nn.trained") == 500, "500 confirmed synthetic games really train 500 model samples");
        check(number(f.state(), "wins") == 250 && number(f.state(), "losses") == 250,
                "balanced synthetic sequence has truthful outcome counters");
    }

    private static String coins(String amount) {
        return "Баланс: монеты " + amount + "; рейды 999999999; койны 999999999";
    }

    private static Properties configuration(boolean balanceEnabled) {
        Properties p = CfParser.defaultProperties();
        p.putAll(CfBalance.defaultProperties());
        p.setProperty("enabled", "true");
        p.setProperty("created.regex", "^CREATED$");
        p.setProperty("insufficient.regex", "^INSUFFICIENT$");
        p.setProperty("limit.regex", "^LIMIT$");
        p.setProperty("win.regex", "^WIN (?<amount>[0-9]+) (?<opponent>[A-Za-z0-9_]+)$");
        p.setProperty("loss.regex", "^LOSS (?<amount>[0-9]+) (?<opponent>[A-Za-z0-9_]+)$");
        if (balanceEnabled) p.setProperty("balance.regex", "^Баланс: монеты (?<amount>[0-9,]+(?:\\.[0-9]{1,2})?); рейды [0-9]+; койны [0-9]+$");
        return p;
    }

    private static Properties neural(Properties state) {
        Properties result = new Properties();
        for (String key : state.stringPropertyNames()) {
            if (key.startsWith("nn.")) result.setProperty(key.substring(3), state.getProperty(key));
        }
        return result;
    }

    private static Properties read(Path path) throws IOException {
        Properties result = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) { result.load(reader); }
        return result;
    }

    private static void write(Path path, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path)) { properties.store(writer, "SYNTHETIC TEST FIXTURE ONLY"); }
    }

    private static long number(Properties state, String key) { return Long.parseLong(state.getProperty(key)); }
    private static boolean pending(Properties state) { return Boolean.parseBoolean(state.getProperty("unresolved")); }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static final class FakeTransport implements CfController.Transport {
        final Path directory;
        final List<String> sent = new ArrayList<>();
        final List<String> feedback = new ArrayList<>();
        final Object connection = new Object();
        int wagerMarkerChecks;
        int balanceMarkerChecks;

        FakeTransport(Path directory) { this.directory = directory; }
        public Object connection() { return connection; }
        public boolean screenOpen() { return false; }
        public void feedback(String text) { feedback.add(text); }
        public void sendCommand(String command) throws IOException {
            Properties state = read(directory.resolve("state.properties"));
            if (command.startsWith("cf create ")) {
                check(pending(state), "wager marker is durably present before fake transport send");
                check(state.containsKey("nn.trained") && state.containsKey("nn.outputBias"),
                        "same persisted snapshot includes neural state at send time");
                wagerMarkerChecks++;
            } else {
                check(command.equals("bal") && !pending(state), "only exact balance command can send without pending marker");
                balanceMarkerChecks++;
            }
            sent.add(command);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path directory = Files.createTempDirectory("cf-neural-controller-test-");
        final FakeTransport transport = new FakeTransport(directory);
        final CfController controller;
        long now;

        Fixture(boolean balanceEnabled) throws IOException {
            write(directory.resolve("config.properties"), configuration(balanceEnabled));
            controller = new CfController(directory, transport);
            now = CfController.monotonicMillis();
        }
        void command(String text) {
            check(controller.onChat("/cfbot " + text), "local command stays client-side");
            now = Math.max(now, CfController.monotonicMillis());
        }
        void tick() { controller.onTick(now); }
        void advance(long millis) { now += millis; tick(); }
        void message(String text) { controller.onSystemMessage(text, transport.connection, now); }
        Properties state() throws IOException { return read(directory.resolve("state.properties")); }
        public void close() throws IOException {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
