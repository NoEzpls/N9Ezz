package dev.n9ezz.cf;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/** Offline contract tests against fake reflection targets, not a Minecraft launch. */
public final class CfRuntimeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        installationAndVanillaTransport();
        localCommandsNeverLeak();
        queuedReceiptsAdvanceOnlyOnTick();
        queuedReceiptCannotMoveSessionClockBackward();
        passiveCaptureDoesNotSpend();
        worldChangeInvalidatesQueuedResults();
        changedHandlerCannotSettlePreviousBet();
        overflowBlocksFurtherCommands();
        malformedSystemMessageBlocksCapture();
        partialSubscriptionFailureRollsBack();
        System.out.println("CfRuntimeTest: " + checks + " checks passed (fake host; gameplay UNRUN)");
    }

    private static void installationAndVanillaTransport() throws Exception {
        try (Fixture f = new Fixture(false)) {
            CfRuntime runtime = f.install();
            equal(FakeClient.singletonCalls, 0, "install must not touch a half-constructed Minecraft singleton");
            equal(f.bus.listeners.size(), 6, "all six hooks installed");
            equal(f.bus.typedSubscriptions, 6, "exact IListener overload used");
            equal(f.bus.objectSubscriptions, 0, "annotation-scanning overload unused");
            for (FakeConsumerListener listener : f.bus.listeners) {
                equal(listener.priority, Integer.MAX_VALUE, "cancellation listener precedes ordinary host listeners");
            }
            equal(runtime.connection(), f.client.handler, "active world reports vanilla handler identity");
            check(!runtime.screenOpen(), "closed GUI reported");
            f.client.field_1755 = new Object();
            check(runtime.screenOpen(), "open GUI reported");
            f.client.field_1755 = null;
            runtime.sendCommand("list");
            equal(f.client.handler.commands, List.of("list"), "ordinary handler receives command without slash");
            expect(IllegalArgumentException.class, () -> runtime.sendCommand("/list"), "slash-prefixed transport rejected");
            expect(IllegalArgumentException.class, () -> runtime.sendCommand(" "), "blank transport rejected");
            f.client.field_1687 = null;
            equal(runtime.connection(), null, "missing world is disconnected");
            expect(IllegalStateException.class, () -> runtime.sendCommand("list"), "no command without a world");
            f.client.field_1687 = new Object();
            FakePlayer oldPlayer = f.client.field_1724;
            f.client.field_1724 = null;
            equal(runtime.connection(), null, "missing player is disconnected");
            f.client.field_1724 = oldPlayer;
            runtime.feedback("adapter probe");
            equal(oldPlayer.overlays, List.of(false), "feedback uses regular local chat, not an overlay");
            check(oldPlayer.messages.getFirst().contains("adapter probe"), "literal text reaches local player display");
        }
    }

    private static void localCommandsNeverLeak() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.install();
            check(!f.chat("hello everybody").cancelled, "ordinary chat left alone");
            check(!f.chat("/cfbotextra stats").cancelled, "similar command prefix left alone");
            check(f.chat("/cfbot").cancelled, "local help cancelled");
            check(f.chat(" \t/CfBoT stats").cancelled, "case and leading ASCII whitespace accepted");
            check(f.chat("/cfbot start 500").cancelled, "disabled automation command still cancelled");
            f.tick();
            equal(f.client.handler.commands.size(), 0, "default configuration never creates bets");
            check(f.chat("/cfbot start invalid").cancelled, "invalid arguments never reach server");
            f.client.field_1724.failDisplay = true;
            check(f.chat("/cfbot status").cancelled, "display failure cannot leak a local command");
            check(!f.chat("/cf create 50000").cancelled, "ordinary user server command left alone");
            check(CfRuntime.isLocalCommand("/cfbot\nstatus"), "whitespace separator recognized");
            check(!CfRuntime.isLocalCommand(null), "null is not a local command");
        }
    }

    private static void queuedReceiptsAdvanceOnlyOnTick() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.install();
            check(f.chat("/cfbot start 1").cancelled, "start command intercepted");
            equal(f.client.handler.commands.size(), 0, "start itself does not transmit");
            f.client.field_1755 = new Object();
            f.tick();
            equal(f.client.handler.commands.size(), 0, "open screen postpones send");
            f.client.field_1755 = null;
            f.tick();
            equal(f.client.handler.commands, List.of("cf create 50000"), "tick sends one fixed-stake command");
            check(f.client.handler.pendingBeforeEachSend.getFirst(), "durable unresolved flag saved before transport call");
            check(f.state().unresolved(), "sent bet remains pending");

            String before = f.journal();
            f.packet(Flow.SEND, "[CF] CREATED", false);
            f.packet(Flow.RECEIVE, "[CF] CREATED", true);
            f.bus.post(new FakePacketEvent(new Object(), Flow.RECEIVE));
            f.tick();
            equal(f.journal(), before, "outgoing, overlay and unrelated packet objects ignored");

            // A separate thread models the host's Netty callback. No journal/UI work
            // is permitted on that thread; the subsequent main-thread tick owns it.
            Thread netty = new Thread(() -> f.packet(Flow.RECEIVE, "[CF] CREATED", false), "fake-netty");
            netty.start();
            netty.join();
            equal(f.journal(), before, "inbound receipt remains queued before tick");
            f.tick();
            check(f.journal().contains("ACK_CREATED"), "tick processes creation receipt");
            equal(f.client.handler.commands.size(), 1, "receipt cannot send a second unresolved bet");
            equal(f.state().wins(), 0L, "creation is not a win");

            f.packet(Flow.RECIEVE, "§a[CF] WIN 85000 Steve", false);
            equal(f.state().wins(), 0L, "result also stays queued until tick");
            f.tick();
            equal(f.state().wins(), 1L, "legacy RECIEVE flow name supported");
            equal(f.state().losses(), 0L, "win does not increment losses");
            check(!f.state().unresolved(), "confirmed result clears pending marker");
            check(f.journal().contains("RESULT_WIN\t[CF] WIN 85000 Steve\t85000\tSteve"), "literal amount and opponent journaled");
            f.packet(Flow.RECEIVE, "[CF] WIN 85000 Steve", false);
            f.tick();
            equal(f.state().wins(), 1L, "duplicate result not counted twice");
            equal(f.client.handler.commands.size(), 1, "one-game target never creates another bet");
        }
    }

    private static void passiveCaptureDoesNotSpend() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.install();
            f.packet(Flow.RECEIVE, "[CF] Непроверенное сообщение о ставке", false);
            equal(f.journal(), "", "recording waits for main-thread tick");
            f.tick();
            check(f.journal().contains("OBSERVED_CANDIDATE"), "unconfigured candidate recorded for calibration");
            equal(f.state().wins(), 0L, "approximate text is not a confirmed result");
            equal(f.client.handler.commands.size(), 0, "passive capture never creates a bet");
        }
    }

    private static void queuedReceiptCannotMoveSessionClockBackward() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CfRuntime runtime = f.install();
            f.chat("/cfbot start 2");
            f.tick();
            f.packet(Flow.RECEIVE, "[CF] CREATED", false);
            // Model a Netty receipt arriving just after a runtime queue drain but
            // before that frame advances the controller's monotonic tick time.
            // An older capture timestamp must not be fed back as session time.
            Thread.sleep(3);
            var field = CfRuntime.class.getDeclaredField("controller");
            field.setAccessible(true);
            CfController controller = (CfController) field.get(runtime);
            controller.onTick(CfRuntime.now());
            f.tick();
            equal(controller.snapshot().mode(), CfSession.Mode.RUNNING,
                    "queued receipt uses processing clock after an intervening controller tick");
            equal(controller.snapshot().phase(), CfSession.Phase.WAITING_RESULT,
                    "queued creation receipt remains valid across frame interleaving");
            f.packet(Flow.RECEIVE, "[CF] WIN 85000 Steve", false);
            f.tick();
            equal(f.state().wins(), 1L, "result settles after receipt/tick race");
            equal(f.client.handler.commands.size(), 1, "race does not duplicate sending");
        }
    }

    private static void worldChangeInvalidatesQueuedResults() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CfRuntime runtime = f.install();
            f.chat("/cfbot start 2");
            f.tick();
            f.packet(Flow.RECEIVE, "[CF] CREATED", false);
            f.tick();
            String before = f.journal();
            f.packet(Flow.RECEIVE, "[CF] WIN 85000 Steve", false);
            f.bus.post(new FakeWorldChange());
            expect(IllegalStateException.class, () -> runtime.sendCommand("list"), "pending disconnect blocks transport immediately");
            check(f.chat("/cfbot start 2").cancelled, "next local command applies pending disconnect before starting");
            f.tick();
            equal(f.journal(), before, "world-change generation discards queued old result");
            check(f.state().unresolved(), "disconnect keeps unresolved stake");
            equal(f.state().wins(), 0L, "invalidated result never counted");
            equal(f.client.handler.commands.size(), 1, "disconnect cannot auto-resume");

            // A later event on the new world is observed, but cannot settle an old
            // bet whose correlation was invalidated by the world transition.
            f.packet(Flow.RECEIVE, "[CF] WIN 85000 Steve", false);
            f.tick();
            equal(f.state().wins(), 0L, "post-transition result has no old attribution");
            check(f.state().unresolved(), "post-transition observation cannot clear crash marker");
        }
    }

    private static void changedHandlerCannotSettlePreviousBet() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.install();
            f.chat("/cfbot start 2");
            f.tick();
            f.packet(Flow.RECEIVE, "[CF] CREATED", false);
            FakeNetwork old = f.client.handler;
            f.client.handler = new FakeNetwork(f.directory);
            f.tick();
            check(f.journal().contains("OBSERVED_CREATED"), "queued receipt bound to old handler is only observed");
            check(f.state().unresolved(), "handler replacement preserves pending marker");
            equal(old.commands.size(), 1, "old handler received only original send");
            equal(f.client.handler.commands.size(), 0, "new handler never auto-resumes");
        }
    }

    private static void overflowBlocksFurtherCommands() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CfRuntime runtime = f.install();
            f.chat("/cfbot start 2");
            f.tick();
            for (int i = 0; i <= CfRuntime.MAX_QUEUED_MESSAGES; i++) {
                f.packet(Flow.RECEIVE, "ordinary server notice " + i, false);
            }
            expect(IllegalStateException.class, () -> runtime.sendCommand("list"), "overflow blocks transport before next tick");
            f.tick();
            f.chat("/cfbot stop");
            f.chat("/cfbot reset checked");
            f.chat("/cfbot start 2");
            f.tick();
            equal(f.client.handler.commands.size(), 1, "overflow stays blocked even after explicit state reset");
            check(f.client.field_1724.messages.stream().anyMatch(s -> s.contains("переполнена")), "capture failure is visible locally");
        }
    }

    private static void malformedSystemMessageBlocksCapture() throws Exception {
        try (Fixture f = new Fixture(true)) {
            CfRuntime runtime = f.install();
            f.packet(Flow.RECEIVE, "x".repeat(8_193), false);
            expect(IllegalStateException.class, () -> runtime.sendCommand("list"), "oversized inbound message blocks transport");
            f.tick();
            f.chat("/cfbot start 1");
            f.tick();
            equal(f.client.handler.commands.size(), 0, "oversized message cannot lead to a wager");
        }
    }

    private static void partialSubscriptionFailureRollsBack() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.bus.failAfterAdding = 3;
            expect(InvocationTargetException.class, f::install, "subscription failure escapes install");
            equal(f.bus.typedSubscriptions, 3, "failure occurred partway through registration");
            equal(f.bus.listeners.size(), 0, "even failed-after-add listener is removed");
            equal(f.bus.unsubscribedTypes, List.of(FakePacketEvent.class, FakeTick.class, FakeChat.class), "rollback removes in reverse order");
            equal(f.bus.objectSubscriptions, 0, "rollback path never scans annotations");
            equal(FakeClient.singletonCalls, 0, "failed install never touches game singleton");
            FakeChat event = new FakeChat("/cfbot start 1");
            f.bus.post(event);
            check(!event.cancelled, "no live callbacks remain after failed install");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path directory = Files.createTempDirectory("cf-runtime-test-");
        final FakeBus bus = new FakeBus();
        final FakeClient client = new FakeClient(directory);

        Fixture(boolean configured) throws IOException {
            FakeEventBusService.EVENT_BUS = bus;
            FakeClient.instance = client;
            FakeClient.singletonCalls = 0;
            if (configured) {
                Properties p = CfParser.defaultProperties();
                p.setProperty("enabled", "true");
                p.setProperty("created.regex", "^\\[CF\\] CREATED$");
                p.setProperty("insufficient.regex", "^\\[CF\\] INSUFFICIENT$");
                p.setProperty("limit.regex", "^\\[CF\\] LIMIT$");
                p.setProperty("win.regex", "^\\[CF\\] WIN (?<amount>[0-9]+) (?<opponent>[A-Za-z0-9_]{1,16})$");
                p.setProperty("loss.regex", "^\\[CF\\] LOSS (?<amount>[0-9]+) (?<opponent>[A-Za-z0-9_]{1,16})$");
                try (Writer writer = Files.newBufferedWriter(directory.resolve("config.properties"))) {
                    p.store(writer, "Artificial test messages; not RaidMine templates");
                }
            }
        }

        CfRuntime install() throws Exception { return CfRuntime.install(directory, CfRuntimeTest::resolve); }
        FakeChat chat(String text) { FakeChat chat = new FakeChat(text); bus.post(chat); return chat; }
        void tick() { bus.post(new FakeTick()); }
        void packet(Flow flow, String text, boolean overlay) {
            bus.post(new FakePacketEvent(new FakeSystemPacket(new FakeText(text), overlay), flow));
        }
        CfStore.State state() throws IOException { return new CfStore(directory).state(); }
        String journal() throws IOException {
            Path journal = directory.resolve("messages.tsv");
            return Files.exists(journal) ? Files.readString(journal) : "";
        }
        @Override public void close() throws IOException {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Class<?> resolve(String name) throws ClassNotFoundException {
        Class<?> result = Map.ofEntries(
                Map.entry("net.minecraft.class_310", FakeClient.class),
                Map.entry("net.minecraft.class_2561", FakeText.class),
                Map.entry("net.minecraft.class_634", FakeNetwork.class),
                Map.entry("net.minecraft.class_1657", FakePlayer.class),
                Map.entry("net.minecraft.class_7439", FakeSystemPacket.class),
                Map.entry("pulse.events.ChatSendEvent", FakeChat.class),
                Map.entry("pulse.events.ClientTickEvent", FakeTick.class),
                Map.entry("pulse.events.PacketEvent", FakePacketEvent.class),
                Map.entry("pulse.events.WorldClearEvent", FakeWorldClear.class),
                Map.entry("pulse.events.WorldChangeEvent", FakeWorldChange.class),
                Map.entry("pulse.events.GameJoinEvent", FakeGameJoin.class),
                Map.entry("pulse.events.EventBusService", FakeEventBusService.class),
                Map.entry("meteordevelopment.orbit.listeners.IListener", FakeListener.class),
                Map.entry("meteordevelopment.orbit.IEventBus", FakeBusApi.class),
                Map.entry("meteordevelopment.orbit.listeners.ConsumerListener", FakeConsumerListener.class)
        ).get(name);
        if (result == null) throw new ClassNotFoundException(name);
        return result;
    }

    public interface FakeListener { }
    public interface FakeBusApi {
        void subscribe(FakeListener listener);
        void subscribe(Object target);
        void unsubscribe(FakeListener listener);
    }
    public static final class FakeConsumerListener implements FakeListener {
        final Class<?> type;
        final int priority;
        final Consumer<Object> callback;
        public FakeConsumerListener(Class<?> type, int priority, Consumer<Object> callback) {
            this.type = type; this.priority = priority; this.callback = callback;
        }
    }
    public static final class FakeBus implements FakeBusApi {
        final List<FakeConsumerListener> listeners = new ArrayList<>();
        final List<Class<?>> unsubscribedTypes = new ArrayList<>();
        int typedSubscriptions, objectSubscriptions, failAfterAdding;
        @Override public void subscribe(FakeListener listener) {
            listeners.add((FakeConsumerListener) listener);
            typedSubscriptions++;
            if (typedSubscriptions == failAfterAdding) throw new IllegalStateException("fake subscribe failure after add");
        }
        @Override public void subscribe(Object target) { objectSubscriptions++; }
        @Override public void unsubscribe(FakeListener listener) {
            unsubscribedTypes.add(((FakeConsumerListener) listener).type);
            listeners.remove(listener);
        }
        void post(Object event) {
            for (FakeConsumerListener listener : List.copyOf(listeners)) {
                if (listener.type == event.getClass()) listener.callback.accept(event);
            }
        }
    }
    public static final class FakeEventBusService { public static FakeBus EVENT_BUS; }
    public static final class FakeClient {
        static FakeClient instance;
        static int singletonCalls;
        public FakePlayer field_1724 = new FakePlayer();
        public Object field_1687 = new Object();
        public Object field_1755;
        FakeNetwork handler;
        FakeClient(Path directory) { handler = new FakeNetwork(directory); }
        public static FakeClient method_1551() { singletonCalls++; return instance; }
        public FakeNetwork method_1562() { return handler; }
    }
    public static final class FakeNetwork {
        final Path directory;
        final List<String> commands = new ArrayList<>();
        final List<Boolean> pendingBeforeEachSend = new ArrayList<>();
        FakeNetwork(Path directory) { this.directory = directory; }
        public void method_45730(String command) throws IOException {
            commands.add(command);
            Properties p = new Properties();
            try (Reader reader = Files.newBufferedReader(directory.resolve("state.properties"))) { p.load(reader); }
            pendingBeforeEachSend.add(Boolean.parseBoolean(p.getProperty("unresolved")));
        }
    }
    public static final class FakeText {
        final String text;
        FakeText(String text) { this.text = text; }
        public static FakeText method_43470(String value) { return new FakeText(value); }
        public String getString() { return text; }
    }
    public static final class FakePlayer {
        final List<String> messages = new ArrayList<>();
        final List<Boolean> overlays = new ArrayList<>();
        boolean failDisplay;
        public void method_7353(FakeText text, boolean overlay) {
            if (failDisplay) throw new IllegalStateException("fake local display failure");
            messages.add(text.getString()); overlays.add(overlay);
        }
    }
    public static final class FakeChat {
        final String message;
        boolean cancelled;
        FakeChat(String message) { this.message = message; }
        public String getMessage() { return message; }
        public void setCancelled(boolean value) { cancelled = value; }
    }
    public enum Flow { SEND, RECEIVE, RECIEVE }
    public static final class FakePacketEvent {
        final Object packet;
        final Flow flow;
        FakePacketEvent(Object packet, Flow flow) { this.packet = packet; this.flow = flow; }
        public Object packet() { return packet; }
        public Flow direction() { return flow; }
    }
    public static final class FakeSystemPacket {
        final FakeText text;
        final boolean overlay;
        FakeSystemPacket(FakeText text, boolean overlay) { this.text = text; this.overlay = overlay; }
        public FakeText comp_763() { return text; }
        public boolean comp_906() { return overlay; }
    }
    public static final class FakeTick { }
    public static final class FakeWorldClear { }
    public static final class FakeWorldChange { }
    public static final class FakeGameJoin { }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, Throwing task, String message) throws Exception {
        try { task.run(); }
        catch (Throwable failure) {
            check(type.isInstance(failure), message + ": unexpected " + failure);
            return;
        }
        throw new AssertionError(message + ": no exception");
    }
    private static void equal(Object actual, Object expected, String message) {
        check(java.util.Objects.equals(actual, expected), message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
