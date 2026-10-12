package dev.n9ezz.cf;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Reflection bridge for the pinned Minecraft 1.21.4 intermediary/Pulse host. */
final class CfRuntime implements CfController.Transport {
    static final int MAX_QUEUED_MESSAGES = 256;
    private record Message(String text, Object connection, long generation) { }
    @FunctionalInterface
    interface Classes { Class<?> get(String name) throws ClassNotFoundException; }

    private final ArrayBlockingQueue<Message> messages = new ArrayBlockingQueue<>(MAX_QUEUED_MESSAGES);
    private final AtomicReference<String> captureFailure = new AtomicReference<>();
    private final AtomicReference<String> disconnect = new AtomicReference<>();
    private final Object generationLock = new Object();
    private long generation;
    private volatile boolean active;
    private final List<Object> listeners = new ArrayList<>();
    private final Object bus;
    private final Method subscribe, unsubscribe, singleton, network, send, literal, display;
    private final Method chatMessage, chatCancel, packet, direction, packetText, packetOverlay, textString;
    private final Field player, world, screen;
    private final Class<?> systemPacket;
    private final Constructor<?> consumerListener;
    private final Classes classes;
    private final CfController controller;

    static boolean available(ClassLoader loader) {
        try {
            Class.forName("net.minecraft.class_310", false, loader);
            Class.forName("pulse.events.EventBusService", false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    static CfRuntime install(Path directory, ClassLoader loader) throws Exception {
        return install(directory, name -> Class.forName(name, false, loader));
    }

    // The resolver is injectable so reflection names, rollback and transport behavior
    // can be checked offline without claiming that a live Minecraft launch was tested.
    static CfRuntime install(Path directory, Classes classes) throws Exception {
        CfRuntime runtime = new CfRuntime(directory, classes);
        try {
            runtime.listen("pulse.events.ChatSendEvent", runtime::chat);
            runtime.listen("pulse.events.ClientTickEvent", ignored -> runtime.tick());
            runtime.listen("pulse.events.PacketEvent", runtime::receive);
            for (String event : List.of("WorldClearEvent", "WorldChangeEvent", "GameJoinEvent")) {
                runtime.listen("pulse.events." + event,
                        ignored -> runtime.invalidate("Мир или соединение изменились; сессия остановлена"));
            }
            runtime.active = true;
            return runtime;
        } catch (Exception | LinkageError failure) {
            runtime.active = false;
            for (int i = runtime.listeners.size() - 1; i >= 0; i--) {
                try { runtime.unsubscribe.invoke(runtime.bus, runtime.listeners.get(i)); }
                catch (Exception | LinkageError rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    private CfRuntime(Path directory, Classes classes) throws Exception {
        this.classes = classes;
        Class<?> client = classes.get("net.minecraft.class_310");
        Class<?> text = classes.get("net.minecraft.class_2561");
        Class<?> chat = classes.get("pulse.events.ChatSendEvent");
        Class<?> packetEvent = classes.get("pulse.events.PacketEvent");
        Class<?> listener = classes.get("meteordevelopment.orbit.listeners.IListener");
        Class<?> eventBus = classes.get("meteordevelopment.orbit.IEventBus");
        // Validate every required accessor before exposing a local start command.
        singleton = client.getMethod("method_1551");
        network = client.getMethod("method_1562");
        player = client.getField("field_1724");
        world = client.getField("field_1687");
        screen = client.getField("field_1755");
        send = classes.get("net.minecraft.class_634").getMethod("method_45730", String.class);
        literal = text.getMethod("method_43470", String.class);
        display = classes.get("net.minecraft.class_1657").getMethod("method_7353", text, boolean.class);
        textString = text.getMethod("getString");
        chatMessage = chat.getMethod("getMessage");
        chatCancel = chat.getMethod("setCancelled", boolean.class);
        packet = packetEvent.getMethod("packet");
        direction = packetEvent.getMethod("direction");
        systemPacket = classes.get("net.minecraft.class_7439");
        packetText = systemPacket.getMethod("comp_763");
        packetOverlay = systemPacket.getMethod("comp_906");
        consumerListener = classes.get("meteordevelopment.orbit.listeners.ConsumerListener")
                .getConstructor(Class.class, int.class, Consumer.class);
        subscribe = eventBus.getMethod("subscribe", listener);
        unsubscribe = eventBus.getMethod("unsubscribe", listener);
        // Initializing this one host service only constructs Orbit's event bus; it
        // does not initialize Pulse modules or inspect a half-constructed game.
        bus = classes.get("pulse.events.EventBusService").getField("EVENT_BUS").get(null);
        controller = new CfController(directory, this);
    }

    private void listen(String name, Consumer<Object> callback) throws Exception {
        Object listener = consumerListener.newInstance(classes.get(name), Integer.MAX_VALUE, callback);
        // Use the IListener overload: subscribe(Object) instead scans annotations.
        // Add before invoking so rollback also covers a bus that throws after adding.
        listeners.add(listener);
        subscribe.invoke(bus, listener);
    }

    private void chat(Object event) {
        if (!active) return;
        try {
            String fullText = (String) chatMessage.invoke(event);
            // Cancel the local command BEFORE controller/file operations can fail.
            // Never leak /cfbot to a server because an exception interrupted handling.
            boolean local = isLocalCommand(fullText);
            if (local) chatCancel.invoke(event, true);
            applyPendingState();
            if (controller.onChat(fullText) && !local) chatCancel.invoke(event, true);
        } catch (Exception | LinkageError failure) {
            fail("Ошибка обработки локальной команды: " + failure.getClass().getSimpleName());
        }
    }

    static boolean isLocalCommand(String text) {
        if (text == null) return false;
        String command = text.stripLeading();
        return command.length() >= 6 && command.regionMatches(true, 0, "/cfbot", 0, 6)
                && (command.length() == 6 || Character.isWhitespace(command.charAt(6)));
    }

    private void receive(Object event) {
        if (!active || captureFailure.get() != null) return;
        try {
            Object flow = direction.invoke(event);
            String flowName = flow instanceof Enum<?> e ? e.name() : String.valueOf(flow);
            if (!flowName.equals("RECEIVE") && !flowName.equals("RECIEVE")) return;
            Object value = packet.invoke(event);
            if (!systemPacket.isInstance(value) || Boolean.TRUE.equals(packetOverlay.invoke(value))) return;
            Object text = packetText.invoke(value);
            String message = (String) textString.invoke(text);
            if (message == null || message.length() > 8_192) {
                fail("Слишком длинное или повреждённое системное сообщение");
                return;
            }
            // PacketEvent is on the Netty thread. Extract immutable text, capture
            // only the active handler identity, and enqueue; no UI or file I/O here.
            // This host event has no source socket field. Identity is the observed
            // active handler, not proof of an old socket's origin after reconnect.
            Object connection = rawConnection();
            synchronized (generationLock) {
                if (!messages.offer(new Message(message, connection, generation))) {
                    fail("Очередь системных сообщений переполнена; захват остановлен");
                }
            }
        } catch (Exception | LinkageError failure) {
            fail("Ошибка захвата сообщений: " + failure.getClass().getSimpleName());
        }
    }

    private void tick() {
        if (!active) return;
        try {
            applyPendingState();
            Message next;
            while ((next = messages.poll()) != null) {
                synchronized (generationLock) {
                    if (next.generation != generation) continue;
                    // Serialize world invalidation with dispatch, so an old-world
                    // result cannot pass a generation check and settle afterward.
                    // All controller I/O still runs on this client thread.
                    // A receipt can race the previous tick's queue drain. Use the
                    // processing clock so it cannot appear to travel backwards.
                    controller.onSystemMessage(next.text, next.connection, now());
                }
            }
            // Process receipts before deadlines/new sends, on the normal game tick.
            controller.onTick(now());
        } catch (Exception | LinkageError failure) {
            fail("Ошибка игрового обработчика: " + failure.getClass().getSimpleName());
        }
    }

    private void applyPendingState() {
        String why = disconnect.getAndSet(null);
        if (why != null) controller.onDisconnect(why);
        why = captureFailure.get();
        if (why != null) controller.onCaptureFailure(why);
    }

    private void invalidate(String reason) {
        if (!active) return;
        synchronized (generationLock) {
            generation++;
            messages.clear();
            disconnect.set(reason);
        }
        // No controller/UI calls from unknown callback threads; the very next
        // local command or tick applies this stop before it can start/send anything.
    }

    private void fail(String reason) {
        captureFailure.compareAndSet(null, reason);
        messages.clear();
    }

    static long now() { return System.nanoTime() / 1_000_000L; }

    private Object client() throws Exception { return singleton.invoke(null); }
    private Object rawConnection() throws Exception {
        Object client = client();
        return client == null ? null : network.invoke(client);
    }

    @Override
    public Object connection() throws Exception {
        Object client = client();
        if (client == null || player.get(client) == null || world.get(client) == null) return null;
        return network.invoke(client);
    }

    @Override
    public boolean screenOpen() throws Exception {
        Object client = client();
        return client == null || screen.get(client) != null;
    }

    @Override
    public void sendCommand(String command) throws Exception {
        if (captureFailure.get() != null || disconnect.get() != null) {
            throw new IllegalStateException("Захват сообщений или соединение недоступны");
        }
        if (command == null || command.isBlank() || command.startsWith("/")) {
            throw new IllegalArgumentException("Ожидалась команда без начального слеша");
        }
        Object connection = connection();
        if (connection == null) throw new IllegalStateException("Нет игрового соединения");
        // Vanilla handler owns signing, packet order and transport. No raw packets,
        // artificial timing jitter, movement changes or server-reply cancellation.
        send.invoke(connection, command);
    }

    @Override
    public void feedback(String message) throws Exception {
        Object client = client();
        Object player = client == null ? null : this.player.get(client);
        if (player == null) {
            System.out.println(message);
        } else {
            display.invoke(player, literal.invoke(null, message), false);
        }
    }
}
