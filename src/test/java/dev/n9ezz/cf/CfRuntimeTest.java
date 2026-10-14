package dev.n9ezz.cf;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reflection-contract tests only: these fakes do not emulate a real Minecraft server. */
public final class CfRuntimeTest {
    private static int assertions;
    private static final String BALANCE = "Ваш баланс: 2,785,000$ | 0 рейдов | 0 коинов";
    private static final String RESULT = "┃ Победа! Вы выиграли 42,500 \uE23A\uF801 (-15%) у ᴍɪɴᴇʀ ExamplePlayer";

    public static void main(String[] args) throws Exception {
        hooksAndLocalCommands();
        nativeTransport();
        messageFilteringAndMainThreadQueue();
        staleConnectionCannotConfirmBalance();
        staleQueuedReplyCannotConfirmLaterCancel();
        worldChangesInvalidateQueueAndStopRun();
        captureOverflowStopsTransport();
        failedInstallationUnsubscribes();
        check(!CfRuntime.available(new ClassLoader(null) {}), "absent game runtime must not appear available");
        System.out.println("CfRuntimeTest: " + assertions + " assertions passed (fake reflection bridge; no live game)");
    }

    private static void hooksAndLocalCommands() throws Exception {
        Fixture f = new Fixture();
        check(f.bus.listeners.size() == 6, "all six native hooks subscribed");
        check(f.bus.listeners.stream().allMatch(it -> it.priority == Integer.MAX_VALUE), "local command hook priority preserved");
        ChatSendEvent help = f.chat("  /CFBOT help");
        check(help.cancelled, "local commands cancelled before reaching server");
        check(f.client.field_1724.feedback.stream().anyMatch(s -> s.contains("/cfbot start")), "controller local help displayed");
        check(f.client.connection.sent.isEmpty(), "help sends no server command");
        check(!f.chat("hello").cancelled, "ordinary chat remains available");
        check(!f.chat("/cfbotother").cancelled, "prefix lookalike is not local command");
        check(f.chat("/cfbot unknown").cancelled, "invalid local subcommands never leak to server");
        check(!CfRuntime.isLocalCommand(null), "null is not a local command");
    }

    private static void nativeTransport() throws Exception {
        Fixture f = new Fixture();
        check(f.runtime.connection() == f.client.connection, "network handler identity retained");
        check(!f.runtime.screenOpen(), "no screen detected");
        f.client.field_1755 = new Object();
        check(f.runtime.screenOpen(), "open screen detected");
        f.client.field_1755 = null;
        f.runtime.sendCommand("bal");
        check(f.client.connection.sent.equals(List.of("bal")), "normal native command method used without slash");
        expectFailure(() -> f.runtime.sendCommand("/bal"), "slash rejected by transport");
        expectFailure(() -> f.runtime.sendCommand(""), "empty command rejected");
        f.runtime.feedback("local feedback");
        check(f.client.field_1724.feedback.contains("local feedback"), "feedback uses player local display");
        check(!f.client.field_1724.lastOverlay, "feedback is not action-bar overlay");
        f.client.field_1687 = null;
        check(f.runtime.connection() == null, "unloaded world has no usable connection");
        expectFailure(() -> f.runtime.sendCommand("bal"), "disconnected transport cannot send");
    }

    private static void messageFilteringAndMainThreadQueue() throws Exception {
        Fixture f = new Fixture();
        check(f.chat("/cfbot balancecheck").cancelled, "balancecheck stays local");
        f.tick();
        check(f.client.connection.sent.equals(List.of("bal")), "one queued balance request sent");
        f.bus.post(new PacketEvent(new PlayerChatPacket(BALANCE), Direction.RECEIVE));
        f.bus.post(new PacketEvent(new SystemPacket(new Text(BALANCE), true), Direction.RECEIVE));
        f.bus.post(new PacketEvent(new SystemPacket(new Text(BALANCE), false), Direction.SEND));
        f.tick();
        check(!f.log().contains("BALANCE_CONFIRMED"), "player packets, overlays and outgoing packets cannot confirm balance");
        f.bus.post(new PacketEvent(new SystemPacket(new Text(BALANCE), false), Direction.RECIEVE));
        check(!f.log().contains("BALANCE_CONFIRMED"), "network callback only enqueues; controller waits for client tick");
        f.tick();
        check(f.log().contains("BALANCE_CONFIRMED") && f.log().contains("2785000"), "legacy RECIEVE spelling is accepted on client tick");
        check(f.client.connection.sent.equals(List.of("bal")), "balance reply produces no automatic additional query or bet");
    }

    private static void staleConnectionCannotConfirmBalance() throws Exception {
        Fixture f = new Fixture();
        f.chat("/cfbot balancecheck");
        f.tick();
        Connection original = f.client.connection;
        f.receive(BALANCE);
        f.client.connection = new Connection();
        f.tick();
        check(!f.log().contains("BALANCE_CONFIRMED"), "message tagged with previous handler cannot confirm new connection balance");
        check(f.snapshot().state() == CfSession.State.PAUSED, "handler replacement pauses controller");
        check(!f.snapshot().runAllowed(), "handler replacement disables further automatic bets");
        check(original.sent.equals(List.of("bal")) && f.client.connection.sent.isEmpty(), "reconnect does not retry command");
    }

    private static void worldChangesInvalidateQueueAndStopRun() throws Exception {
        for (Object event : List.of(new WorldClearEvent(), new WorldChangeEvent(), new GameJoinEvent())) {
            Fixture f = new Fixture();
            f.chat("/cfbot start 100");
            Matcher token = Pattern.compile("/cfbot confirm ([a-z0-9-]+)").matcher(String.join("\n", f.client.field_1724.feedback));
            check(token.find(), "start shows confirmation token");
            f.chat("/cfbot confirm " + token.group(1));
            check(f.snapshot().runAllowed(), "explicit confirmation authorizes session before disconnect");
            f.receive(RESULT);
            f.bus.post(event);
            expectFailure(() -> f.runtime.sendCommand("bal"), "pending world change blocks transport immediately");
            f.tick();
            check(f.snapshot().state() == CfSession.State.PAUSED && !f.snapshot().runAllowed(), "world event pauses session");
            check(!f.log().contains("OBSERVED_WIN") && !f.log().contains("CONFIRMED_WIN"), "queued prior-generation outcome discarded");
            check(f.snapshot().completed() == 0, "discarded message cannot count as game");
            f.tick();
            check(f.client.connection.sent.isEmpty(), "subsequent tick never resumes after world change");
        }
    }

    private static void staleQueuedReplyCannotConfirmLaterCancel() throws Exception {
        Fixture f = new Fixture();
        f.receive("┃ У вас нет активной ставки.");
        // Deliberately do not wait for a tick: the message can share the request's
        // millisecond, so merely comparing millisecond timestamps is insufficient.
        check(!f.chat("/cf cancel").cancelled, "manual cancellation remains a native server command");
        check(f.snapshot().unresolved(), "new cancel request is unresolved before its own response");
        f.tick();
        check(f.snapshot().unresolved(), "previously queued NO_ACTIVE cannot confirm a later cancel request");
        check(!f.log().contains("CONFIRMED_NO_ACTIVE"), "stale cancel response is not journaled as confirmed");
        check(f.client.connection.sent.isEmpty(), "bridge never duplicates manual cancellation");
        f.receive("┃ У вас нет активной ставки.");
        f.tick();
        check(!f.snapshot().unresolved(), "fresh reply to the new cancel request clears unresolved state");
        check(f.log().contains("CONFIRMED_NO_ACTIVE"), "only fresh cancel response is confirmed");
    }

    private static void captureOverflowStopsTransport() throws Exception {
        Fixture f = new Fixture();
        for (int i = 0; i <= CfRuntime.MAX_QUEUED_MESSAGES; i++) f.receive("system notice " + i);
        expectFailure(() -> f.runtime.sendCommand("bal"), "capture overflow closes send path before next tick");
        f.tick();
        check(f.snapshot().state() == CfSession.State.ERROR && !f.snapshot().runAllowed(), "overflow becomes durable hard stop");
        f.receive(RESULT);
        f.tick();
        check(f.snapshot().completed() == 0 && !f.log().contains("CONFIRMED_WIN"), "capture after overflow cannot fabricate outcome");
        check(f.chat("/cfbot status").cancelled, "status remains local after capture failure");
        check(f.client.connection.sent.isEmpty(), "overflow caused no network command");
    }

    private static void failedInstallationUnsubscribes() throws Exception {
        EventBusService.EVENT_BUS = new FakeBus();
        EventBusService.EVENT_BUS.failOnSubscribe = 3;
        Minecraft.INSTANCE = new Minecraft();
        Path directory = Files.createTempDirectory("cf-runtime-install-failure-");
        expectFailure(() -> CfRuntime.install(directory, CfRuntimeTest::resolve), "partial installation surfaces failure");
        check(EventBusService.EVENT_BUS.listeners.isEmpty(), "all subscribed listeners removed after partial installation failure");
    }

    private static final class Fixture {
        final FakeBus bus;
        final Minecraft client;
        final Path directory;
        final CfRuntime runtime;
        Fixture() throws Exception {
            bus = new FakeBus();
            client = new Minecraft();
            EventBusService.EVENT_BUS = bus;
            Minecraft.INSTANCE = client;
            directory = Files.createTempDirectory("cf-runtime-test-");
            runtime = CfRuntime.install(directory, CfRuntimeTest::resolve);
        }
        ChatSendEvent chat(String message) { ChatSendEvent event = new ChatSendEvent(message); bus.post(event); return event; }
        void tick() { bus.post(new ClientTickEvent()); }
        void receive(String text) { bus.post(new PacketEvent(new SystemPacket(new Text(text), false), Direction.RECEIVE)); }
        String log() throws Exception { Path path = directory.resolve("messages.tsv"); return Files.exists(path) ? Files.readString(path) : ""; }
        CfSession.Snapshot snapshot() throws Exception {
            Field field = CfRuntime.class.getDeclaredField("controller");
            field.setAccessible(true);
            return ((CfController) field.get(runtime)).snapshot();
        }
    }

    private static Class<?> resolve(String name) throws ClassNotFoundException {
        return switch (name) {
            case "net.minecraft.class_310" -> Minecraft.class;
            case "net.minecraft.class_2561" -> Text.class;
            case "net.minecraft.class_634" -> Connection.class;
            case "net.minecraft.class_1657" -> Player.class;
            case "net.minecraft.class_7439" -> SystemPacket.class;
            case "pulse.events.EventBusService" -> EventBusService.class;
            case "pulse.events.ChatSendEvent" -> ChatSendEvent.class;
            case "pulse.events.ClientTickEvent" -> ClientTickEvent.class;
            case "pulse.events.PacketEvent" -> PacketEvent.class;
            case "pulse.events.WorldClearEvent" -> WorldClearEvent.class;
            case "pulse.events.WorldChangeEvent" -> WorldChangeEvent.class;
            case "pulse.events.GameJoinEvent" -> GameJoinEvent.class;
            case "meteordevelopment.orbit.listeners.IListener" -> Listener.class;
            case "meteordevelopment.orbit.IEventBus" -> Bus.class;
            case "meteordevelopment.orbit.listeners.ConsumerListener" -> ConsumerListener.class;
            default -> throw new ClassNotFoundException(name);
        };
    }

    public interface Listener {}
    public interface Bus { void subscribe(Listener listener); void unsubscribe(Listener listener); }
    public static final class ConsumerListener implements Listener {
        final Class<?> type;
        final int priority;
        final Consumer<Object> callback;
        public ConsumerListener(Class<?> type, int priority, Consumer<Object> callback) { this.type = type; this.priority = priority; this.callback = callback; }
    }
    public static final class FakeBus implements Bus {
        final List<ConsumerListener> listeners = new ArrayList<>();
        int subscribeCount, failOnSubscribe;
        public void subscribe(Listener listener) {
            if (++subscribeCount == failOnSubscribe) throw new IllegalStateException("synthetic subscription failure");
            listeners.add((ConsumerListener) listener);
        }
        public void unsubscribe(Listener listener) { listeners.remove(listener); }
        void post(Object event) { for (ConsumerListener listener : List.copyOf(listeners)) if (listener.type.isInstance(event)) listener.callback.accept(event); }
    }
    public static final class EventBusService { public static FakeBus EVENT_BUS; }
    public static final class Minecraft {
        static Minecraft INSTANCE;
        public Player field_1724 = new Player();
        public Object field_1687 = new Object();
        public Object field_1755;
        Connection connection = new Connection();
        public static Minecraft method_1551() { return INSTANCE; }
        public Connection method_1562() { return connection; }
    }
    public static final class Connection {
        final List<String> sent = new ArrayList<>();
        public void method_45730(String command) { sent.add(command); }
    }
    public static final class Player {
        final List<String> feedback = new ArrayList<>();
        boolean lastOverlay;
        public void method_7353(Text text, boolean overlay) { feedback.add(text.getString()); lastOverlay = overlay; }
    }
    public static final class Text {
        final String value;
        Text(String value) { this.value = value; }
        public static Text method_43470(String value) { return new Text(value); }
        public String getString() { return value; }
    }
    public static final class ChatSendEvent {
        final String message;
        boolean cancelled;
        ChatSendEvent(String message) { this.message = message; }
        public String getMessage() { return message; }
        public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }
    public enum Direction { RECEIVE, RECIEVE, SEND }
    public record PacketEvent(Object packet, Direction direction) {}
    public record SystemPacket(Text comp_763, boolean comp_906) {}
    public record PlayerChatPacket(String message) {}
    public static final class ClientTickEvent {}
    public static final class WorldClearEvent {}
    public static final class WorldChangeEvent {}
    public static final class GameJoinEvent {}
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void expectFailure(Action action, String message) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (Exception expected) { failed = true; }
        check(failed, message);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
