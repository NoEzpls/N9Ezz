/* Restored from the supplied 0.2.1 bytecode with CFR 0.152; see provenance.json. */
package dev.n9ezz.cf;

import dev.n9ezz.cf.CfController;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class CfRuntime
implements CfController.Transport {
    static final int MAX_QUEUED_MESSAGES = 256;
    private final ArrayBlockingQueue<Message> messages = new ArrayBlockingQueue<>(256);
    private final AtomicReference<String> captureFailure = new AtomicReference<>();
    private final AtomicReference<String> disconnect = new AtomicReference<>();
    private final Object generationLock = new Object();
    private long generation;
    private volatile boolean active;
    private final List<Object> listeners = new ArrayList<Object>();
    private final Object bus;
    private final Method subscribe;
    private final Method unsubscribe;
    private final Method singleton;
    private final Method network;
    private final Method send;
    private final Method literal;
    private final Method display;
    private final Method chatMessage;
    private final Method chatCancel;
    private final Method packet;
    private final Method direction;
    private final Method packetText;
    private final Method packetOverlay;
    private final Method textString;
    private final Field player;
    private final Field world;
    private final Field screen;
    private final Class<?> systemPacket;
    private final Constructor<?> consumerListener;
    private final Classes classes;
    private final CfController controller;

    static boolean available(ClassLoader classLoader) {
        try {
            Class.forName("net.minecraft.class_310", false, classLoader);
            Class.forName("pulse.events.EventBusService", false, classLoader);
            return true;
        }
        catch (ClassNotFoundException | LinkageError throwable) {
            return false;
        }
    }

    static CfRuntime install(Path path, ClassLoader classLoader) throws Exception {
        return CfRuntime.install(path, (String string) -> Class.forName(string, false, classLoader));
    }

    static CfRuntime install(Path path, Classes classes) throws Exception {
        CfRuntime cfRuntime = new CfRuntime(path, classes);
        try {
            cfRuntime.listen("pulse.events.ChatSendEvent", cfRuntime::chat);
            cfRuntime.listen("pulse.events.ClientTickEvent", object -> cfRuntime.tick());
            cfRuntime.listen("pulse.events.PacketEvent", cfRuntime::receive);
            for (String string : List.of("WorldClearEvent", "WorldChangeEvent", "GameJoinEvent")) {
                cfRuntime.listen("pulse.events." + string, object -> cfRuntime.invalidate("\u041c\u0438\u0440 \u0438\u043b\u0438 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u0438\u0437\u043c\u0435\u043d\u0438\u043b\u0438\u0441\u044c; \u0441\u0435\u0441\u0441\u0438\u044f \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d\u0430"));
            }
            cfRuntime.active = true;
            return cfRuntime;
        }
        catch (Exception | LinkageError throwable) {
            cfRuntime.active = false;
            for (int i = cfRuntime.listeners.size() - 1; i >= 0; --i) {
                try {
                    cfRuntime.unsubscribe.invoke(cfRuntime.bus, cfRuntime.listeners.get(i));
                    continue;
                }
                catch (Exception | LinkageError throwable2) {
                    throwable.addSuppressed(throwable2);
                }
            }
            throw throwable;
        }
    }

    private CfRuntime(Path path, Classes classes) throws Exception {
        this.classes = classes;
        Class<?> clazz = classes.get("net.minecraft.class_310");
        Class<?> clazz2 = classes.get("net.minecraft.class_2561");
        Class<?> clazz3 = classes.get("pulse.events.ChatSendEvent");
        Class<?> clazz4 = classes.get("pulse.events.PacketEvent");
        Class<?> clazz5 = classes.get("meteordevelopment.orbit.listeners.IListener");
        Class<?> clazz6 = classes.get("meteordevelopment.orbit.IEventBus");
        this.singleton = clazz.getMethod("method_1551", new Class[0]);
        this.network = clazz.getMethod("method_1562", new Class[0]);
        this.player = clazz.getField("field_1724");
        this.world = clazz.getField("field_1687");
        this.screen = clazz.getField("field_1755");
        this.send = classes.get("net.minecraft.class_634").getMethod("method_45730", String.class);
        this.literal = clazz2.getMethod("method_43470", String.class);
        this.display = classes.get("net.minecraft.class_1657").getMethod("method_7353", clazz2, Boolean.TYPE);
        this.textString = clazz2.getMethod("getString", new Class[0]);
        this.chatMessage = clazz3.getMethod("getMessage", new Class[0]);
        this.chatCancel = clazz3.getMethod("setCancelled", Boolean.TYPE);
        this.packet = clazz4.getMethod("packet", new Class[0]);
        this.direction = clazz4.getMethod("direction", new Class[0]);
        this.systemPacket = classes.get("net.minecraft.class_7439");
        this.packetText = this.systemPacket.getMethod("comp_763", new Class[0]);
        this.packetOverlay = this.systemPacket.getMethod("comp_906", new Class[0]);
        this.consumerListener = classes.get("meteordevelopment.orbit.listeners.ConsumerListener").getConstructor(Class.class, Integer.TYPE, Consumer.class);
        this.subscribe = clazz6.getMethod("subscribe", clazz5);
        this.unsubscribe = clazz6.getMethod("unsubscribe", clazz5);
        this.bus = classes.get("pulse.events.EventBusService").getField("EVENT_BUS").get(null);
        this.controller = new CfController(path, this);
    }

    private void listen(String string, Consumer<Object> consumer) throws Exception {
        Object obj = this.consumerListener.newInstance(this.classes.get(string), Integer.MAX_VALUE, consumer);
        this.listeners.add(obj);
        this.subscribe.invoke(this.bus, obj);
    }

    private void chat(Object object) {
        if (!this.active) {
            return;
        }
        try {
            String string = (String)this.chatMessage.invoke(object, new Object[0]);
            boolean bl = CfRuntime.isLocalCommand(string);
            if (bl) {
                this.chatCancel.invoke(object, true);
            }
            this.applyPendingState();
            // Chat commands and ticks both run on the client thread. Process
            // already queued replies before establishing a new manual request;
            // millisecond timestamps alone cannot order events in the same ms.
            // Do not tick the controller here: /cfbot stop must never cause a
            // pending automatic create to be sent before stop is handled.
            this.drainMessages();
            if (this.controller.onChat(string) && !bl) {
                this.chatCancel.invoke(object, true);
            }
        }
        catch (Exception | LinkageError throwable) {
            this.fail("\u041e\u0448\u0438\u0431\u043a\u0430 \u043e\u0431\u0440\u0430\u0431\u043e\u0442\u043a\u0438 \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u043e\u0439 \u043a\u043e\u043c\u0430\u043d\u0434\u044b: " + throwable.getClass().getSimpleName());
        }
    }

    static boolean isLocalCommand(String string) {
        if (string == null) {
            return false;
        }
        String string2 = string.stripLeading();
        return string2.length() >= 6 && string2.regionMatches(true, 0, "/cfbot", 0, 6) && (string2.length() == 6 || Character.isWhitespace(string2.charAt(6)));
    }

    private void receive(Object object) {
        if (!this.active || this.captureFailure.get() != null) {
            return;
        }
        // Preserve arrival time across the network-thread -> client-tick queue.
        // Replacing it with drain time could let an old NO_ACTIVE / balance reply
        // satisfy a verification request that was made after the reply arrived.
        long receivedAt = now();
        try {
            Object eventDirection = this.direction.invoke(object);
            // Keep both spellings supported by the existing custom Loader/Pulse bridge.
            String string = eventDirection instanceof Enum<?> value
                ? value.name() : String.valueOf(eventDirection);
            Object object2;
            if (!string.equals("RECEIVE") && !string.equals("RECIEVE")) {
                return;
            }
            object2 = this.packet.invoke(object, new Object[0]);
            if (!this.systemPacket.isInstance(object2) || Boolean.TRUE.equals(this.packetOverlay.invoke(object2, new Object[0]))) {
                return;
            }
            Object object4 = this.packetText.invoke(object2, new Object[0]);
            String string2 = (String)this.textString.invoke(object4, new Object[0]);
            if (string2 == null || string2.length() > 8192) {
                this.fail("\u0421\u043b\u0438\u0448\u043a\u043e\u043c \u0434\u043b\u0438\u043d\u043d\u043e\u0435 \u0438\u043b\u0438 \u043f\u043e\u0432\u0440\u0435\u0436\u0434\u0451\u043d\u043d\u043e\u0435 \u0441\u0438\u0441\u0442\u0435\u043c\u043d\u043e\u0435 \u0441\u043e\u043e\u0431\u0449\u0435\u043d\u0438\u0435");
                return;
            }
            Object object5 = this.rawConnection();
            Object object6 = this.generationLock;
            synchronized (object6) {
                if (!this.messages.offer(new Message(string2, object5, this.generation, receivedAt))) {
                    this.fail("\u041e\u0447\u0435\u0440\u0435\u0434\u044c \u0441\u0438\u0441\u0442\u0435\u043c\u043d\u044b\u0445 \u0441\u043e\u043e\u0431\u0449\u0435\u043d\u0438\u0439 \u043f\u0435\u0440\u0435\u043f\u043e\u043b\u043d\u0435\u043d\u0430; \u0437\u0430\u0445\u0432\u0430\u0442 \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d");
                }
            }
        }
        catch (Exception | LinkageError throwable) {
            this.fail("\u041e\u0448\u0438\u0431\u043a\u0430 \u0437\u0430\u0445\u0432\u0430\u0442\u0430 \u0441\u043e\u043e\u0431\u0449\u0435\u043d\u0438\u0439: " + throwable.getClass().getSimpleName());
        }
    }

    private void tick() {
        if (!this.active) {
            return;
        }
        try {
            this.applyPendingState();
            this.drainMessages();
            this.controller.onTick(CfRuntime.now());
        }
        catch (Exception | LinkageError throwable) {
            this.fail("\u041e\u0448\u0438\u0431\u043a\u0430 \u0438\u0433\u0440\u043e\u0432\u043e\u0433\u043e \u043e\u0431\u0440\u0430\u0431\u043e\u0442\u0447\u0438\u043a\u0430: " + throwable.getClass().getSimpleName());
        }
    }

    private void drainMessages() {
        Message message;
        while ((message = this.messages.poll()) != null) {
            synchronized (this.generationLock) {
                if (message.generation != this.generation) continue;
                this.controller.onSystemMessage(message.text, message.connection, message.receivedAt);
            }
        }
    }

    private void applyPendingState() {
        String string = this.disconnect.getAndSet(null);
        if (string != null) {
            this.controller.onDisconnect(string);
        }
        if ((string = this.captureFailure.get()) != null) {
            this.controller.onCaptureFailure(string);
        }
    }

    private void invalidate(String string) {
        if (!this.active) {
            return;
        }
        Object object = this.generationLock;
        synchronized (object) {
            ++this.generation;
            this.messages.clear();
            this.disconnect.set(string);
        }
    }

    private void fail(String string) {
        this.captureFailure.compareAndSet(null, string);
        this.messages.clear();
    }

    static long now() {
        return System.nanoTime() / 1000000L;
    }

    private Object client() throws Exception {
        return this.singleton.invoke(null, new Object[0]);
    }

    private Object rawConnection() throws Exception {
        Object object = this.client();
        return object == null ? null : this.network.invoke(object, new Object[0]);
    }

    @Override
    public Object connection() throws Exception {
        Object object = this.client();
        if (object == null || this.player.get(object) == null || this.world.get(object) == null) {
            return null;
        }
        return this.network.invoke(object, new Object[0]);
    }

    @Override
    public boolean screenOpen() throws Exception {
        Object object = this.client();
        return object == null || this.screen.get(object) != null;
    }

    @Override
    public void sendCommand(String string) throws Exception {
        if (this.captureFailure.get() != null || this.disconnect.get() != null) {
            throw new IllegalStateException("\u0417\u0430\u0445\u0432\u0430\u0442 \u0441\u043e\u043e\u0431\u0449\u0435\u043d\u0438\u0439 \u0438\u043b\u0438 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u043d\u044b");
        }
        if (string == null || string.isBlank() || string.startsWith("/")) {
            throw new IllegalArgumentException("\u041e\u0436\u0438\u0434\u0430\u043b\u0430\u0441\u044c \u043a\u043e\u043c\u0430\u043d\u0434\u0430 \u0431\u0435\u0437 \u043d\u0430\u0447\u0430\u043b\u044c\u043d\u043e\u0433\u043e \u0441\u043b\u0435\u0448\u0430");
        }
        Object object = this.connection();
        if (object == null) {
            throw new IllegalStateException("\u041d\u0435\u0442 \u0438\u0433\u0440\u043e\u0432\u043e\u0433\u043e \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f");
        }
        this.send.invoke(object, string);
    }

    @Override
    public void feedback(String string) throws Exception {
        Object object;
        Object object2 = this.client();
        Object object3 = object = object2 == null ? null : this.player.get(object2);
        if (object == null) {
            System.out.println(string);
        } else {
            this.display.invoke(object, this.literal.invoke(null, string), false);
        }
    }

    @FunctionalInterface
    static interface Classes {
        public Class<?> get(String var1) throws ClassNotFoundException;
    }

    private record Message(String text, Object connection, long generation, long receivedAt) {
    }
}

