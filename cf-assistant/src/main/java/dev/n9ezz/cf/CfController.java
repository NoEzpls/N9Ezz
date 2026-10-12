package dev.n9ezz.cf;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.Properties;

/** Main-thread command handling; transport callbacks never send from a packet thread. */
public final class CfController {
    public interface Transport {
        Object connection() throws Exception;
        boolean screenOpen() throws Exception;
        void sendCommand(String command) throws Exception;
        void feedback(String message) throws Exception;
    }

    private final Path directory;
    private final Transport transport;
    private final CfStore store;
    private final CfSession session = new CfSession();
    private CfParser parser;
    private String configurationError;
    private String blocked;
    private Object boundConnection;
    private boolean attributionValid;
    private String lastNotice = "";

    public CfController(Path directory, Transport transport) throws IOException {
        this.directory = directory;
        this.transport = transport;
        store = new CfStore(directory);
        if (store.state().unresolved()) session.markUnresolved();
        loadConfiguration();
    }

    private void loadConfiguration() throws IOException {
        // A failed reload must never leave the previous enabled templates active.
        parser = new CfParser(CfParser.defaultProperties());
        configurationError = "Конфигурация не загружена";
        Path path = directory.resolve("config.properties");
        if (!Files.exists(path)) {
            try (Writer writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
                CfParser.defaultProperties().store(writer,
                        "Use exact verified server messages; read README before enabling automation");
            }
        }
        try {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path)) { properties.load(reader); }
            parser = new CfParser(properties);
            configurationError = null;
        }
        catch (IllegalArgumentException invalid) {
            parser = new CfParser(CfParser.defaultProperties());
            configurationError = invalid.getMessage();
        }
    }

    /** Returns true only for local /cfbot commands, which must never reach the server. */
    public synchronized boolean onChat(String text) {
        String trimmed = text.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts[0].equalsIgnoreCase("/cf")) {
            if (session.snapshot().mode() == CfSession.Mode.RUNNING || session.snapshot().unresolved()) {
                // A manual /cf create or GUI join can produce identical replies.
                // Invalidate correlation instead of assigning its result to our bet.
                onDisconnect("Ручная команда /cf: сессия остановлена, проверьте ставки");
                say(session.snapshot().reason());
            }
            return false;
        }
        if (!parts[0].equalsIgnoreCase("/cfbot")) return false;
        try {
            String action = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "help";
            switch (action) {
                case "start" -> {
                    if (parts.length != 3) throw new IllegalArgumentException("Используйте /cfbot start 1..500");
                    if (blocked != null) throw new IllegalStateException(blocked + "; нужен перезапуск клиента");
                    if (configurationError != null) throw new IllegalStateException(configurationError);
                    if (!parser.configured()) throw new IllegalStateException("Сначала настройте точные шаблоны в config/cf-assistant/config.properties и выполните /cfbot reload");
                    Object current = transport.connection();
                    if (current == null) throw new IllegalStateException("Нет соединения с игровым миром");
                    session.start(Integer.parseInt(parts[2]), monotonicMillis());
                    boundConnection = current;
                    attributionValid = true;
                    store.log("START", "target=" + parts[2] + ", stake=" + CfSession.STAKE, null, null);
                    say("Сессия: " + parts[2] + " игр по 50 000. Не создавайте другие ставки. /cfbot stop — остановить новые ставки.");
                }
                case "stop", "record" -> {
                    session.stop("Автоматические ставки остановлены; сбор сообщений продолжается");
                    say(session.snapshot().reason());
                }
                case "reload" -> {
                    if (session.snapshot().mode() == CfSession.Mode.RUNNING || session.snapshot().unresolved())
                        throw new IllegalStateException("Нельзя менять шаблоны при запущенной сессии или незавершённой ставке");
                    loadConfiguration();
                    say(configurationError != null ? configurationError : parser.configured()
                            ? "Шаблоны загружены. Сессия не запущена." : "Режим записи: шаблоны не включены.");
                }
                case "reset" -> {
                    if (parts.length != 3 || !parts[2].equalsIgnoreCase("checked"))
                        throw new IllegalArgumentException("Сначала проверьте/закройте ставки на сервере; затем /cfbot reset checked. Команда не отменяет ставки.");
                    if (session.snapshot().mode() == CfSession.Mode.RUNNING)
                        throw new IllegalStateException("Сначала /cfbot stop");
                    CfStore.State old = store.state();
                    store.log("MANUAL_RESET", "User checked outstanding server bets", null, null);
                    store.commit(new CfStore.State(false, old.wins(), old.losses()));
                    session.clearUnresolved();
                    attributionValid = false;
                    say(session.snapshot().reason());
                }
                case "stats", "status" -> report();
                case "help" -> say("/cfbot start 1..500 | stop | stats | reload | reset checked. Ставка 50 000. Перед start закройте прежние ставки. Журнал: config/cf-assistant/messages.tsv");
                default -> throw new IllegalArgumentException("Неизвестная команда. /cfbot help");
            }
        } catch (IOException failure) { storageFailure(failure); say(blocked); }
        catch (Exception failure) { say("CF: " + failure.getMessage()); }
        return true;
    }

    public synchronized void onTick(long nowMillis) {
        if (blocked != null) { notice(); return; }
        try {
            Object connection = transport.connection();
            if (boundConnection != null && boundConnection != connection) {
                onDisconnect("Соединение изменилось; автоматическое продолжение отключено");
            }
            String command = session.tick(nowMillis,
                    connection != null && connection == boundConnection, transport.screenOpen());
            if (command != null) {
                CfStore.State old = store.state();
                // No send is allowed before this durable marker. Failed sends may
                // have reached the server, so neither errors nor restarts retry them.
                store.commit(new CfStore.State(true, old.wins(), old.losses()));
                store.log("SEND", command, Long.toString(CfSession.STAKE), null);
                try { transport.sendCommand(command); }
                catch (Exception failure) { session.failSend(failure.getClass().getSimpleName()); }
            }
        } catch (IOException failure) { storageFailure(failure); }
        catch (Exception failure) { onCaptureFailure("Ошибка интерфейса игры: " + failure.getClass().getSimpleName()); }
        notice();
    }

    public synchronized void onSystemMessage(String text, Object sourceConnection, long nowMillis) {
        if (blocked != null) return;
        CfParser.Parsed message = parser.parse(text);
        if (!message.candidate() && message.signal() == null) return;
        try {
            boolean sameConnection = sourceConnection != null && sourceConnection == boundConnection
                    && sourceConnection == transport.connection() && attributionValid;
            CfSession.Snapshot before = session.snapshot();
            boolean accepted = false;
            if (sameConnection && message.signal() != null) {
                accepted = session.onSignal(message.signal(), nowMillis);
            } else if (sameConnection && before.mode() == CfSession.Mode.RUNNING && message.candidate()) {
                // Approximate text is useful for calibration, never for clearing a
                // pending stake or authorizing another spend.
                session.stop("Неизвестное сообщение CF; проверьте журнал и незавершённую ставку");
                attributionValid = false;
            }
            CfSession.Snapshot after = session.snapshot();
            boolean completed = after.completed() > before.completed();
            store.log(completed ? "RESULT_" + message.signal()
                            : (accepted ? "ACK_" : "OBSERVED_") + (message.signal() == null ? "CANDIDATE" : message.signal()),
                    message.normalized(), message.reportedAmount(), message.opponent());
            if (accepted) {
                CfStore.State old = store.state();
                long wins = Math.addExact(old.wins(), after.wins() - before.wins());
                long losses = Math.addExact(old.losses(), after.losses() - before.losses());
                // Totals and the unresolved flag move together in one state file.
                // The reported amount is logged as text: the fee basis is unknown.
                store.commit(new CfStore.State(after.unresolved(), wins, losses));
            }
        } catch (IOException failure) { storageFailure(failure); }
        catch (Exception failure) { onCaptureFailure("Невозможно обработать сообщение: " + failure.getClass().getSimpleName()); }
    }

    public synchronized void onDisconnect(String reason) {
        session.stop(reason);
        attributionValid = false;
        boundConnection = null;
    }

    public synchronized void onCaptureFailure(String reason) {
        blocked = reason;
        onDisconnect(reason);
    }

    private void storageFailure(IOException failure) {
        onCaptureFailure("Ошибка локального журнала; новые ставки заблокированы (" + failure.getClass().getSimpleName() + ")");
    }

    private void report() {
        CfSession.Snapshot s = session.snapshot();
        CfStore.State total = store.state();
        CfStatistics.Summary stats = CfStatistics.summarize(total.wins(), total.losses());
        say(s.mode() + ": " + s.reason() + ". Сессия " + s.completed() + "/" + s.target()
                + "; побед " + s.wins() + ", поражений " + s.losses() + "; незавершённая: " + s.unresolved());
        if (stats.n() == 0) say("Подтверждённых результатов пока нет. Нейросеть не обучена; прогноз следующей игры отсутствует.");
        else say(String.format(Locale.ROOT,
                "Все сессии: %d игр, побед %.1f%%, интервал Wilson 95%%: %.1f–%.1f%%. Это оценка выборки, не прогноз следующей игры.",
                stats.n(), stats.rate() * 100, stats.lower() * 100, stats.upper() * 100));
        if (blocked != null) say(blocked);
    }

    private void notice() {
        CfSession.Snapshot s = session.snapshot();
        if (s.mode() == CfSession.Mode.STOPPED && !s.reason().equals(lastNotice)) {
            lastNotice = s.reason();
            say(s.reason());
        } else if (s.mode() == CfSession.Mode.RUNNING) lastNotice = "";
    }

    private void say(String message) {
        try { transport.feedback("[CF] " + message); }
        catch (Exception failure) { System.out.println("[CF] " + message); }
    }

    static long monotonicMillis() { return System.nanoTime() / 1_000_000L; }
    synchronized CfSession.Snapshot snapshot() { return session.snapshot(); }
}
