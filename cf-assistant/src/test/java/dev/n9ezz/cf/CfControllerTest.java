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

/** Integration checks use synthetic templates and transports, never a live server. */
public final class CfControllerTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        defaultConfigurationNeverSends();
        invalidConfigurationRequiresSuccessfulReload();
        malformedReloadCannotReuseOldEnabledConfiguration();
        fiveHundredGamesAreBoundedAndFixedStake();
        restartRetainsUnresolvedMarkerWithoutResending();
        failedSendIsNeverRetried();
        resultsRequireAcknowledgementAndCurrentConnection();
        unknownCandidatePausesAndInvalidatesAttribution();
        manualCfInvalidatesAttribution();
        connectionChangePausesWithStickyMarker();
        explicitDisconnectDoesNotResume();
        failedMarkerWritePreventsSend();
        failedJournalWritePreventsSend();
        malformedStateRejectsConstruction();
        negativeAcknowledgementsStopWithoutRetry();
        stopAllowsConfirmedPendingResultWithoutResuming();
        resetRequiresExplicitStoppedAcknowledgement();
        openScreenDefersSend();
        journalEscapesUntrustedFields();
        System.out.println("CfControllerTest: " + assertions + " assertions passed (offline synthetic fixtures)");
    }

    private static void defaultConfigurationNeverSends() throws Exception {
        try (Fixture f = new Fixture(false)) {
            check(!f.controller.onChat("ordinary chat"), "ordinary chat remains server-bound");
            check(f.controller.onChat("/cfbot start 500"), "local commands are consumed");
            f.tick();
            check(f.transport.sent.isEmpty(), "default config cannot spend");
            check(!f.controller.snapshot().unresolved(), "disabled config does not mark a stake");
            check(Files.exists(f.directory.resolve("config.properties")), "default config created");
            f.message("CF неизвестный ответ");
            check(Files.readString(f.directory.resolve("messages.tsv")).contains("OBSERVED_CANDIDATE"),
                    "record mode retains candidate messages for calibration");
        }
    }

    private static void invalidConfigurationRequiresSuccessfulReload() throws Exception {
        try (Fixture f = new Fixture(true)) {
            Properties bad = configuration();
            bad.setProperty("created.regex", "CREATED");
            writeProperties(f.directory.resolve("config.properties"), bad);
            f.controller.onChat("/cfbot reload");
            f.start(1);
            f.tick();
            check(f.transport.sent.isEmpty(), "unanchored template blocks automation");
            writeProperties(f.directory.resolve("config.properties"), configuration());
            f.controller.onChat("/cfbot reload");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "reload alone never starts");
            f.start(1);
            f.tick();
            check(f.transport.sent.size() == 1, "valid reviewed config permits explicit start");
        }
    }

    private static void malformedReloadCannotReuseOldEnabledConfiguration() throws Exception {
        try (Fixture f = new Fixture(true)) {
            Files.writeString(f.directory.resolve("config.properties"), "enabled=" + '\\' + "uZZZZ\n");
            f.controller.onChat("/cfbot reload");
            f.start(1);
            f.tick();
            check(f.transport.sent.isEmpty(), "malformed properties must invalidate old enabled config");
        }
    }

    private static void fiveHundredGamesAreBoundedAndFixedStake() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(500);
            for (int i = 0; i < 500; i++) {
                f.tick();
                check(f.transport.sent.size() == i + 1, "exactly one creation per resolved game");
                check(f.transport.sent.get(i).equals("cf create 50000"), "stake never follows winning streaks");
                check(f.state().unresolved(), "durable marker remains before ACK");
                f.tick();
                check(f.transport.sent.size() == i + 1, "pending creation prevents second stake");
                f.message("CREATED");
                f.message("CREATED");
                check(f.controller.snapshot().completed() == i, "ACKs never count results");
                f.message((i % 2 == 0 ? "WIN" : "LOSS") + " 85000 FixturePlayer");
                f.message((i % 2 == 0 ? "WIN" : "LOSS") + " 85000 FixturePlayer");
                check(f.controller.snapshot().completed() == i + 1, "duplicate immediate result not counted twice");
                check(!f.state().unresolved(), "confirmed result clears durable marker");
                f.now += CfSession.CREATE_INTERVAL_MILLIS;
            }
            f.tick();
            check(f.transport.sent.size() == 500, "target bounds total creations");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "target stops session");
            check(f.state().wins() == 250 && f.state().losses() == 250, "persistent exact outcome totals");
            check(f.transport.markerChecks == 500, "every fake send observes marker already on disk");
            String log = Files.readString(f.directory.resolve("messages.tsv"));
            check(log.contains("RESULT_WIN\tWIN 85000 FixturePlayer\t85000\tFixturePlayer"),
                    "journal retains reported amount and opponent without fee inference");
        }
    }

    private static void restartRetainsUnresolvedMarkerWithoutResending() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(3);
            f.tick();
            FakeTransport replacement = new FakeTransport(f.directory);
            CfController restored = new CfController(f.directory, replacement);
            check(restored.snapshot().unresolved(), "restart recovers unresolved stake");
            check(restored.snapshot().phase() == CfSession.Phase.RECOVERY_REQUIRED, "restart requires explicit recovery");
            restored.onChat("/cfbot start 3");
            restored.onTick(f.now + 1_000_000);
            restored.onSystemMessage("CREATED", replacement.connection, f.now + 1_000_001);
            restored.onSystemMessage("WIN 85000 FixturePlayer", replacement.connection, f.now + 1_000_002);
            check(replacement.sent.isEmpty(), "restart never automatically resends");
            check(restored.snapshot().unresolved(), "uncorrelated late replies cannot clear recovered marker");
            check(f.state().wins() == 0, "uncorrelated late result not recorded as confirmed win");
        }
    }

    private static void failedSendIsNeverRetried() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.transport.failSend = true;
            f.start(3);
            f.tick();
            check(f.transport.sent.size() == 1, "send attempted once");
            check(f.state().unresolved(), "send exception preserves durable marker");
            f.transport.failSend = false;
            f.now += 1_000_000;
            f.tick();
            f.start(3);
            f.tick();
            check(f.transport.sent.size() == 1, "ambiguous send failure cannot retry or restart");
        }
    }

    private static void resultsRequireAcknowledgementAndCurrentConnection() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(2);
            f.tick();
            f.controller.onSystemMessage("CREATED", new Object(), f.now);
            check(f.controller.snapshot().phase() == CfSession.Phase.CREATING, "foreign connection ACK ignored");
            f.message("WIN 85000 FixturePlayer");
            check(f.controller.snapshot().completed() == 0, "result without ACK not counted");
            check(f.state().wins() == 0 && f.state().losses() == 0, "unconfirmed result leaves totals unchanged");
            check(f.state().unresolved(), "unconfirmed result preserves pending marker");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "unexpected result stops spending");
        }
    }

    private static void unknownCandidatePausesAndInvalidatesAttribution() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(3);
            f.tick();
            f.message("CREATED");
            f.message("CF ставка: неизвестный новый формат");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "unknown CF candidate stops spending");
            f.message("WIN 85000 FixturePlayer");
            f.now += 5_000;
            f.tick();
            check(f.state().unresolved(), "unknown candidate prevents later result attribution");
            check(f.state().wins() == 0, "later result remains observation only");
            check(f.transport.sent.size() == 1, "unknown candidate never triggers another stake");
        }
    }

    private static void manualCfInvalidatesAttribution() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(3);
            f.tick();
            f.message("CREATED");
            check(!f.controller.onChat(" /CF create 50000 "), "manual CF command still reaches server");
            f.message("WIN 85000 FixturePlayer");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "manual CF pauses automation");
            check(f.state().unresolved() && f.state().wins() == 0, "manual command invalidates attribution");
        }
    }

    private static void connectionChangePausesWithStickyMarker() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(3);
            f.tick();
            Object old = f.transport.connection;
            f.message("CREATED");
            f.transport.connection = new Object();
            f.now += 2_000;
            f.tick();
            f.controller.onSystemMessage("WIN 85000 FixturePlayer", old, f.now);
            f.message("WIN 85000 FixturePlayer");
            check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "new connection identity pauses");
            check(f.state().unresolved() && f.state().wins() == 0, "neither old nor new connection settles stale stake");
            check(f.transport.sent.size() == 1, "new connection does not resume session");
        }
    }

    private static void explicitDisconnectDoesNotResume() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(3);
            f.tick();
            f.controller.onDisconnect("fixture disconnect");
            f.now += 10_000;
            f.tick();
            check(f.transport.sent.size() == 1, "disconnect hook stops even before next game tick");
            check(f.state().unresolved(), "disconnect preserves recovery marker");
        }
    }

    private static void failedMarkerWritePreventsSend() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(2);
            Path state = f.directory.resolve("state.properties");
            Files.delete(state);
            Files.createDirectory(state);
            Files.writeString(state.resolve("prevent-replacement"), "fixture");
            f.tick();
            check(f.transport.sent.isEmpty(), "failed atomic marker replacement prevents all sends");
            Files.delete(state.resolve("prevent-replacement"));
            Files.delete(state);
            f.now += 50_000;
            f.tick();
            f.start(2);
            f.tick();
            check(f.transport.sent.isEmpty(), "storage failure remains blocked after filesystem recovers");
        }
    }

    private static void failedJournalWritePreventsSend() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(2);
            Files.delete(f.directory.resolve("messages.tsv"));
            Files.createDirectory(f.directory.resolve("messages.tsv"));
            f.tick();
            check(f.transport.sent.isEmpty(), "failed send journal stops command dispatch");
            check(f.state().unresolved(), "marker precedes journal write and remains conservative");
        }
    }

    private static void malformedStateRejectsConstruction() throws Exception {
        String[] invalid = {
                "unresolved=maybe\nwins=0\nlosses=0\n",
                "unresolved=false\nwins=-1\nlosses=0\n",
                "unresolved=false\nwins=9223372036854775807\nlosses=1\n",
                "unresolved=false\nwins=NaN\nlosses=0\n",
                "unresolved=false\nwins=0\n"
        };
        for (String text : invalid) {
            try (Fixture f = new Fixture(true)) {
                Files.writeString(f.directory.resolve("state.properties"), text);
                boolean rejected = false;
                try { new CfController(f.directory, new FakeTransport(f.directory)); }
                catch (IOException expected) { rejected = true; }
                check(rejected, "malformed durable state rejects controller startup");
                check(f.transport.sent.isEmpty(), "state validation cannot send commands");
            }
        }
    }

    private static void negativeAcknowledgementsStopWithoutRetry() throws Exception {
        for (String response : List.of("INSUFFICIENT", "LIMIT")) {
            try (Fixture f = new Fixture(true)) {
                f.start(3);
                f.tick();
                f.message(response);
                check(!f.state().unresolved(), "definitive creation rejection clears pending marker");
                check(f.controller.snapshot().mode() == CfSession.Mode.STOPPED, "negative ACK stops session");
                f.now += 200_000;
                f.tick();
                check(f.transport.sent.size() == 1, "negative ACK never automatically retries");
                check(f.state().wins() == 0 && f.state().losses() == 0, "rejected creation is not a completed game");
            }
        }
    }

    private static void stopAllowsConfirmedPendingResultWithoutResuming() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(3);
            f.tick();
            f.message("CREATED");
            f.controller.onChat("/cfbot stop");
            check(f.state().unresolved(), "stop does not cancel server bet");
            f.message("LOSS 50000 FixturePlayer");
            check(!f.state().unresolved() && f.state().losses() == 1, "known pending result may settle stopped session");
            f.now += 5_000;
            f.tick();
            check(f.transport.sent.size() == 1, "late result does not resume spending");
        }
    }

    private static void resetRequiresExplicitStoppedAcknowledgement() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.start(3);
            f.tick();
            f.controller.onChat("/cfbot reset checked");
            check(f.state().unresolved(), "reset forbidden while session runs");
            f.controller.onChat("/cfbot stop");
            f.controller.onChat("/cfbot reset");
            check(f.state().unresolved(), "reset needs checked acknowledgement");
            f.controller.onChat("/cfbot reset checked");
            check(!f.state().unresolved() && !f.controller.snapshot().unresolved(), "explicit recovery clears both states");
            f.now += 5_000;
            f.tick();
            check(f.transport.sent.size() == 1, "reset does not auto-resume");
        }
    }

    private static void openScreenDefersSend() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.transport.screenOpen = true;
            f.start(1);
            f.tick();
            check(f.transport.sent.isEmpty() && !f.state().unresolved(), "screen-open tick creates no stake");
            f.transport.screenOpen = false;
            f.tick();
            check(f.transport.sent.size() == 1, "screen close permits pending explicit session");
        }
    }

    private static void journalEscapesUntrustedFields() throws Exception {
        try (Fixture f = new Fixture(false)) {
            CfStore store = new CfStore(f.directory);
            store.log("TYPE", "CF line\twith\nnew\rline\\", "50\t000", "player\nname");
            List<String> lines = Files.readAllLines(f.directory.resolve("messages.tsv"));
            check(lines.size() == 2, "embedded newlines cannot forge journal rows");
            check(lines.get(1).split("\t", -1).length == 5, "embedded tabs cannot forge journal columns");
            check(lines.get(1).contains("CF line\\twith\\nnew\\rline\\\\"), "control characters escaped reversibly");
        }
    }

    private static Properties configuration() {
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("created.regex", "^CREATED$");
        p.setProperty("insufficient.regex", "^INSUFFICIENT$");
        p.setProperty("limit.regex", "^LIMIT$");
        p.setProperty("win.regex", "^WIN (?<amount>[0-9]+) (?<opponent>[A-Za-z0-9_]+)$");
        p.setProperty("loss.regex", "^LOSS (?<amount>[0-9]+) (?<opponent>[A-Za-z0-9_]+)$");
        return p;
    }

    private static void writeProperties(Path path, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path)) { properties.store(writer, "SYNTHETIC TEST FIXTURES ONLY"); }
    }

    private static CfStore.State readState(Path directory) throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(directory.resolve("state.properties"))) { p.load(reader); }
        return new CfStore.State(Boolean.parseBoolean(p.getProperty("unresolved")),
                Long.parseLong(p.getProperty("wins")), Long.parseLong(p.getProperty("losses")));
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    private static final class FakeTransport implements CfController.Transport {
        final Path directory;
        final List<String> sent = new ArrayList<>();
        final List<String> feedback = new ArrayList<>();
        Object connection = new Object();
        boolean screenOpen;
        boolean failSend;
        int markerChecks;

        FakeTransport(Path directory) { this.directory = directory; }
        public Object connection() { return connection; }
        public boolean screenOpen() { return screenOpen; }
        public void sendCommand(String command) throws IOException {
            check(readState(directory).unresolved(), "transport must observe persisted unresolved marker BEFORE send");
            markerChecks++;
            sent.add(command);
            if (failSend) throw new IOException("synthetic ambiguous send error");
        }
        public void feedback(String message) { feedback.add(message); }
    }

    private static final class Fixture implements AutoCloseable {
        final Path directory = Files.createTempDirectory("cf-controller-test-");
        final FakeTransport transport = new FakeTransport(directory);
        final CfController controller;
        long now;

        Fixture(boolean enabled) throws IOException {
            if (enabled) writeProperties(directory.resolve("config.properties"), configuration());
            controller = new CfController(directory, transport);
            now = CfController.monotonicMillis();
        }
        void start(int target) {
            check(controller.onChat("/cfbot start " + target), "local start consumed");
            now = Math.max(now, CfController.monotonicMillis());
        }
        void tick() { controller.onTick(now); }
        void message(String message) { controller.onSystemMessage(message, transport.connection, now); }
        CfStore.State state() throws IOException { return readState(directory); }
        public void close() throws IOException {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
