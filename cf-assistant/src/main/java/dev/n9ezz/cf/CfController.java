package dev.n9ezz.cf;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private final CfGoal goal = new CfGoal();
    private CfParser parser;
    private CfBalance balance;
    private CfNeuralModel model = new CfNeuralModel();
    private String modelScope;
    private long maximumStake = CfSession.STAKE;
    private boolean automatic;
    private boolean balanceFresh;
    private boolean finalBalanceNeeded;
    private boolean balanceWaiting;
    private boolean balanceProbe;
    private int probeMessages;
    private Object balanceConnection;
    private long balanceRequestedAt;
    private long balanceAcceptedAt;
    private boolean hasTransportCommand;
    private long lastTransportCommandAt;
    private static final long BALANCE_TIMEOUT = 10_000;
    private static final long BALANCE_MAX_AGE = 15_000;
    private static final double MIN_AUTO_PROBABILITY = 0.60;
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
        try {
            Properties saved = store.metadata();
            if (saved.containsKey("prefs.goal")) goal.configure(new BigDecimal(saved.getProperty("prefs.goal")), false);
            if (saved.containsKey("prefs.losslimit")) goal.configureLossLimit(new BigDecimal(saved.getProperty("prefs.losslimit")));
            maximumStake = Long.parseLong(saved.getProperty("prefs.maxStake", Long.toString(CfSession.STAKE)));
            if (maximumStake < CfSession.STAKE || maximumStake > CfSession.MAX_STAKE)
                throw new IllegalArgumentException("Invalid stake setting");
        } catch (RuntimeException invalid) { throw new IOException("Повреждены настройки цели", invalid); }
        loadConfiguration();
    }

    private void loadConfiguration() throws IOException {
        // A failed reload must never leave the previous enabled templates active.
        parser = new CfParser(CfParser.defaultProperties());
        balance = new CfBalance(CfBalance.defaultProperties());
        configurationError = "Конфигурация не загружена";
        Path path = directory.resolve("config.properties");
        if (!Files.exists(path)) {
            try (Writer writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) {
                Properties defaults = CfParser.defaultProperties();
                defaults.putAll(CfBalance.defaultProperties());
                defaults.setProperty("model.scope", "RaidMine-monety");
                defaults.store(writer,
                        "Use exact verified server messages; read README before enabling automation");
            }
        }
        try {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path)) { properties.load(reader); }
            parser = new CfParser(properties);
            balance = new CfBalance(properties);
            String nextScope = scope(properties);
            if (!nextScope.equals(modelScope)) {
                Properties saved = store.metadata();
                Properties weights = new Properties();
                for (String key : saved.stringPropertyNames())
                    if (key.startsWith("nn.")) weights.setProperty(key.substring(3), saved.getProperty(key));
                model = nextScope.equals(saved.getProperty("model.scope")) && !weights.isEmpty()
                        ? CfNeuralModel.fromProperties(weights) : new CfNeuralModel();
                // A reconnect/start is not evidence that the next outcome directly
                // follows the previous one; keep weights, break sequence features.
                model.breakSequence();
                modelScope = nextScope;
            }
            configurationError = null;
        }
        catch (IllegalArgumentException invalid) {
            parser = new CfParser(CfParser.defaultProperties());
            balance = new CfBalance(CfBalance.defaultProperties());
            configurationError = invalid.getMessage();
        }
    }

    /** Returns true only for local /cfbot commands, which must never reach the server. */
    public synchronized boolean onChat(String text) {
        String trimmed = text.trim();
        String[] parts = trimmed.split("\\s+");
        String rootCommand = parts[0].toLowerCase(Locale.ROOT);
        boolean manualCf = rootCommand.equals("/cf") || rootCommand.endsWith(":cf");
        boolean manualBalance = rootCommand.equals("/bal") || rootCommand.endsWith(":bal")
                || balance.queryCommand() != null && rootCommand.equals("/" + balance.queryCommand().split(" ", 2)[0].toLowerCase(Locale.ROOT));
        if (manualCf || manualBalance) {
            if (manualCf) {
                model.breakSequence();
                attributionValid = false;
            }
            if (session.snapshot().mode() == CfSession.Mode.RUNNING || session.snapshot().unresolved() || balanceWaiting) {
                // A manual /cf create or GUI join can produce identical replies.
                // Invalidate correlation instead of assigning its result to our bet.
                onDisconnect("Ручная команда /cf или /bal: сессия остановлена, проверьте ставки");
                say(session.snapshot().reason());
            }
            return false;
        }
        if (!parts[0].equalsIgnoreCase("/cfbot")) return false;
        try {
            String action = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "help";
            switch (action) {
                case "start", "auto" -> {
                    boolean useModel = action.equals("auto");
                    if (parts.length != 3) throw new IllegalArgumentException("Используйте /cfbot " + action + " 1..500");
                    if (blocked != null) throw new IllegalStateException(blocked + "; нужен перезапуск клиента");
                    if (configurationError != null) throw new IllegalStateException(configurationError);
                    if (!parser.configured()) throw new IllegalStateException("Сначала настройте точные шаблоны в config/cf-assistant/config.properties и выполните /cfbot reload");
                    if (balanceWaiting) throw new IllegalStateException("Дождитесь ответа /bal или завершения записи");
                    if (useModel && !goal.enabled()) throw new IllegalStateException("Сначала задайте итоговый баланс: /cfbot goal сумма");
                    if ((goal.enabled() || goal.lossLimit() != null) && !balance.configured())
                        throw new IllegalStateException("Для цели нужен точный шаблон баланса монет: /cfbot balance записывает ответ /bal");
                    Object current = transport.connection();
                    if (current == null) throw new IllegalStateException("Нет соединения с игровым миром");
                    if (boundConnection != current) model.breakSequence();
                    if (useModel && !model.predict().usable())
                        throw new IllegalStateException("Нейросеть пока не прошла проверку или потерян контекст последних игр. Сбор данных: /cfbot start 1..500; отчёт: /cfbot ai");
                    session.start(Integer.parseInt(parts[2]), monotonicMillis());
                    automatic = useModel;
                    session.selectStake(CfSession.STAKE);
                    goal.begin();
                    balanceFresh = finalBalanceNeeded = false;
                    boundConnection = current;
                    attributionValid = true;
                    store.log("START", "games=" + parts[2] + ", automatic=" + automatic + ", goal=" + goal.amount(), null, null);
                    say("Сессия: до " + parts[2] + " игр. " + (automatic ? "Выбор ставок нейросетью, максимум " + maximumStake : "Сбор данных по 50 000")
                            + ". /cfbot stop — остановить новые ставки. Не создавайте параллельные ставки.");
                }
                case "goal" -> {
                    requireIdle();
                    if (parts.length != 3) throw new IllegalArgumentException("/cfbot goal сумма|off — итоговый баланс монет");
                    if (parts[2].equalsIgnoreCase("off")) goal.disable();
                    else goal.configure(moneyArgument(parts[2]), false);
                    persist(store.state());
                    say(goal.enabled() ? "Цель по итоговому балансу монет: " + goal.amount().toPlainString() : "Цель выключена");
                }
                case "losslimit" -> {
                    requireIdle();
                    if (parts.length != 3) throw new IllegalArgumentException("/cfbot losslimit сумма|off");
                    goal.configureLossLimit(parts[2].equalsIgnoreCase("off") ? null : moneyArgument(parts[2]));
                    persist(store.state());
                    say("Лимит потери от начального баланса: " + goal.lossLimit());
                }
                case "stake" -> {
                    requireIdle();
                    if (parts.length != 3) throw new IllegalArgumentException("/cfbot stake 50000..5000000 — потолок автоматической ставки");
                    long selected = Long.parseLong(parts[2]);
                    if (selected < CfSession.STAKE || selected > CfSession.MAX_STAKE) throw new IllegalArgumentException("Сумма должна быть 50000..5000000");
                    maximumStake = selected;
                    persist(store.state());
                    say("Максимум автоматической ставки: " + maximumStake + "; сбор данных остаётся по 50 000");
                }
                case "balance" -> {
                    requireIdle();
                    if (balance.queryCommand() == null) throw new IllegalStateException("Не настроена balance.command");
                    Object current = transport.connection();
                    if (current == null) throw new IllegalStateException("Нет игрового соединения");
                    long now = monotonicMillis();
                    if (!maySend(now)) throw new IllegalStateException("Подождите две секунды между командами");
                    requestBalance(current, now, true);
                    say("Ответ /bal записывается 10 секунд в messages.tsv. Нужна строка монет, а не рейдов или койнов.");
                }
                case "ai" -> reportModel();
                case "stop", "record" -> {
                    session.stop("Автоматические ставки остановлены; сбор сообщений продолжается");
                    say(session.snapshot().reason());
                }
                case "reload" -> {
                    requireIdle();
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
                    model.breakSequence();
                    persist(new CfStore.State(false, old.wins(), old.losses()));
                    session.clearUnresolved();
                    attributionValid = false;
                    balanceWaiting = balanceFresh = finalBalanceNeeded = false;
                    balanceConnection = null;
                    goal.begin();
                    say(session.snapshot().reason());
                }
                case "stats", "status" -> report();
                case "help" -> say("/cfbot goal сумма | balance | start 1..500 | auto 1..500 | stake максимум | losslimit сумма|off | ai | stop | stats | reload | reset checked. Перед start/auto закройте прежние ставки.");
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
            if (balanceWaiting && (balanceConnection != connection || nowMillis < balanceRequestedAt
                    || nowMillis - balanceRequestedAt >= BALANCE_TIMEOUT)) {
                balanceWaiting = false;
                finalBalanceNeeded = false;
                if (!balanceProbe) session.stop("Нет свежего точного баланса монет; сессия остановлена");
                else say("Запись ответа /bal закончена; настройте balance.regex по messages.tsv");
                balanceProbe = false;
            }
            boolean screenOpen = transport.screenOpen();
            CfSession.Snapshot before = session.snapshot();
            if (!before.unresolved() && balanceRequired()
                    && (before.mode() == CfSession.Mode.RUNNING || finalBalanceNeeded)) {
                if (connection == null || connection != boundConnection || !attributionValid) {
                    onDisconnect("Баланс нельзя проверить после смены соединения");
                } else if (balanceFresh && (nowMillis < balanceAcceptedAt || nowMillis - balanceAcceptedAt > BALANCE_MAX_AGE)) {
                    balanceFresh = false;
                }
                if (balanceFresh) {
                    finalBalanceNeeded = false;
                    if (goal.reached()) session.stop("Цель достигнута: баланс монет " + goal.current().toPlainString());
                    else if (goal.hitLossLimit()) session.stop("Достигнут лимит потери от начального баланса");
                } else if (!balanceWaiting && !screenOpen && maySend(nowMillis) && connection == boundConnection && connection != null) {
                    requestBalance(connection, nowMillis, false);
                }
            }
            before = session.snapshot();
            if (before.mode() == CfSession.Mode.RUNNING && !before.unresolved()
                    && (!balanceRequired() || balanceFresh) && !balanceWaiting) {
                long stake = automatic ? automaticStake() : CfSession.STAKE;
                if (stake > 0 && balanceRequired()) {
                    long affordable = goal.current().longValue();
                    if (goal.lossLimit() != null) {
                        BigDecimal remaining = goal.lossLimit().subtract(goal.initial().subtract(goal.current()));
                        affordable = Math.min(affordable, Math.max(0, remaining.longValue()));
                    }
                    stake = Math.min(stake, affordable);
                    if (stake < CfSession.STAKE) {
                        session.stop("Баланса или оставшегося лимита недостаточно для минимальной ставки 50 000");
                        stake = 0;
                    }
                }
                if (stake > 0) session.selectStake(stake);
            }
            String command = session.tick(nowMillis,
                    connection != null && connection == boundConnection,
                    screenOpen || balanceWaiting || (balanceRequired() && !balanceFresh) || !maySend(nowMillis));
            if (command != null) {
                CfStore.State old = store.state();
                // No send is allowed before this durable marker. Failed sends may
                // have reached the server, so neither errors nor restarts retry them.
                persist(new CfStore.State(true, old.wins(), old.losses()));
                store.log("SEND", command, Long.toString(session.snapshot().stake()), null);
                balanceFresh = false;
                if (automatic) store.log("MODEL_DECISION", "probability=" + model.predict().probability()
                        + ", stake=" + session.snapshot().stake() + ", NOT_A_GUARANTEE", null, null);
                try { sendCommand(command, nowMillis); }
                catch (Exception failure) { session.failSend(failure.getClass().getSimpleName()); }
            }
        } catch (IOException failure) { storageFailure(failure); }
        catch (Exception failure) { onCaptureFailure("Ошибка интерфейса игры: " + failure.getClass().getSimpleName()); }
        notice();
    }

    public synchronized void onSystemMessage(String text, Object sourceConnection, long nowMillis) {
        if (blocked != null) return;
        CfParser.Parsed message = parser.parse(text);
        try {
            if (receiveBalance(text, sourceConnection, nowMillis, message.signal())) return;
            if (!message.candidate() && message.signal() == null) return;
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
                model.breakSequence();
            }
            CfSession.Snapshot after = session.snapshot();
            boolean completed = after.completed() > before.completed();
            if (!completed && sourceConnection != null && sourceConnection == boundConnection
                    && (message.signal() == CfSession.Signal.WIN || message.signal() == CfSession.Signal.LOSS)) {
                // With no wager ID this might be a duplicate or a missed manual
                // game. Do not treat histories on its two sides as adjacent.
                model.breakSequence();
            }
            if (completed) {
                // observe scores its prediction BEFORE training on this label.
                // Only confirmed tracked games enter the model, never chat guesses.
                model.observe(message.signal() == CfSession.Signal.WIN);
                balanceFresh = false;
                finalBalanceNeeded = balanceRequired();
            }
            store.log(completed ? "RESULT_" + message.signal()
                            : (accepted ? "ACK_" : "OBSERVED_") + (message.signal() == null ? "CANDIDATE" : message.signal()),
                    message.normalized(), message.reportedAmount(), message.opponent());
            if (accepted) {
                CfStore.State old = store.state();
                long wins = Math.addExact(old.wins(), after.wins() - before.wins());
                long losses = Math.addExact(old.losses(), after.losses() - before.losses());
                // Totals and the unresolved flag move together in one state file.
                // The reported amount is logged as text: the fee basis is unknown.
                persist(new CfStore.State(after.unresolved(), wins, losses));
            }
        } catch (IOException failure) { storageFailure(failure); }
        catch (Exception failure) { onCaptureFailure("Невозможно обработать сообщение: " + failure.getClass().getSimpleName()); }
    }

    public synchronized void onDisconnect(String reason) {
        session.stop(reason);
        attributionValid = false;
        boundConnection = null;
        balanceWaiting = balanceFresh = finalBalanceNeeded = false;
        balanceConnection = null;
        model.breakSequence();
    }

    public synchronized void onCaptureFailure(String reason) {
        blocked = reason;
        onDisconnect(reason);
    }

    private void storageFailure(IOException failure) {
        onCaptureFailure("Ошибка локального журнала; новые ставки заблокированы (" + failure.getClass().getSimpleName() + ")");
    }

    private boolean balanceRequired() { return goal.enabled() || goal.lossLimit() != null; }

    private boolean maySend(long now) {
        return !hasTransportCommand || now >= lastTransportCommandAt
                && now - lastTransportCommandAt >= CfSession.CREATE_INTERVAL_MILLIS;
    }

    private void sendCommand(String command, long now) throws Exception {
        hasTransportCommand = true;
        lastTransportCommandAt = now;
        transport.sendCommand(command);
    }

    private void requestBalance(Object connection, long now, boolean probe) throws Exception {
        if (session.snapshot().unresolved()) throw new IllegalStateException("Баланс не запрашивается до результата ставки");
        balanceWaiting = true;
        balanceProbe = probe;
        balanceFresh = false;
        balanceConnection = connection;
        balanceRequestedAt = now;
        probeMessages = 0;
        store.log("BALANCE_QUERY", balance.queryCommand(), null, null);
        try { sendCommand(balance.queryCommand(), now); }
        catch (Exception failure) {
            balanceWaiting = finalBalanceNeeded = false;
            session.stop("Не удалось запросить баланс; автоматического повтора не будет");
            throw failure;
        }
    }

    private boolean receiveBalance(String text, Object sourceConnection, long now, CfSession.Signal cfSignal) throws Exception {
        if (!balanceWaiting || sourceConnection == null || sourceConnection != balanceConnection
                || sourceConnection != transport.connection()) return false;
        if (now < balanceRequestedAt || now - balanceRequestedAt >= BALANCE_TIMEOUT) {
            balanceWaiting = finalBalanceNeeded = false;
            if (!balanceProbe) session.stop("Ответ с балансом опоздал; новые ставки остановлены");
            return false;
        }
        if (balanceProbe) {
            if (probeMessages++ < 128) store.log("BALANCE_SAMPLE", text, null, null);
            return true;
        }
        if (!attributionValid) return false;
        BigDecimal value = balance.parse(text);
        if (value == null) return false;
        if (cfSignal != null) {
            balanceWaiting = finalBalanceNeeded = false;
            session.stop("Шаблон баланса пересекается с ответом CF; проверьте конфигурацию");
            return true;
        }
        // Only an explicitly requested, same-connection, exact coins reply can
        // fund a wager or satisfy the goal. Ignore raids/koins and game payouts.
        goal.accept(value);
        balanceWaiting = false;
        balanceFresh = true;
        finalBalanceNeeded = false;
        balanceAcceptedAt = now;
        store.log("BALANCE_CONFIRMED", text, value.toPlainString(), null);
        if (goal.reached()) session.stop("Цель достигнута: баланс монет " + value.toPlainString());
        else if (goal.hitLossLimit()) session.stop("Достигнут лимит потери от начального баланса");
        return true;
    }

    private long automaticStake() {
        CfNeuralModel.Prediction prediction = model.predict();
        if (!prediction.usable()) {
            session.stop("Нейросеть не проходит проверку на новых результатах; автоматические ставки остановлены");
            return 0;
        }
        if (prediction.probability() < MIN_AUTO_PROBABILITY) {
            // Waiting cannot change history-only inputs. Stop explicitly instead
            // of claiming that a timer will make the next random flip favorable.
            session.stop("Оценка нейросети ниже порога 60%; нужна новая выборка, а не ожидание удачного тика");
            return 0;
        }
        // This bounded sizing rule is a user-controlled heuristic, not Kelly or
        // proof of profitability: the server's exact payout/fee rules are unknown.
        double fraction = Math.min(1, (prediction.probability() - MIN_AUTO_PROBABILITY) / 0.30);
        long amount = CfSession.STAKE + (long) ((maximumStake - CfSession.STAKE) * fraction);
        return Math.max(CfSession.STAKE, amount / CfSession.STAKE * CfSession.STAKE);
    }

    private void requireIdle() {
        if (blocked != null) throw new IllegalStateException(blocked + "; нужен перезапуск клиента");
        if (session.snapshot().mode() == CfSession.Mode.RUNNING || session.snapshot().unresolved() || balanceWaiting)
            throw new IllegalStateException("Сначала остановите сессию и дождитесь результата ставки/баланса");
    }

    private static BigDecimal moneyArgument(String value) {
        if (!value.matches("[0-9]{1,16}(?:\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Укажите сумму цифрами, например 5000000; без k/m и разделителей тысяч");
        return new BigDecimal(value);
    }

    private static String scope(Properties properties) {
        StringBuilder canonical = new StringBuilder(properties.getProperty("model.scope", "RaidMine-monety"));
        for (String key : new String[]{"created.regex", "insufficient.regex", "limit.regex", "win.regex", "loss.regex",
                "balance.regex", "balance.command", "balance.grouping", "balance.decimal"})
            canonical.append('\n').append(key).append('=').append(properties.getProperty(key, ""));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private void persist(CfStore.State state) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("prefs.maxStake", Long.toString(maximumStake));
        if (goal.enabled()) metadata.setProperty("prefs.goal", goal.amount().toPlainString());
        if (goal.lossLimit() != null) metadata.setProperty("prefs.losslimit", goal.lossLimit().toPlainString());
        if (modelScope != null) {
            metadata.setProperty("model.scope", modelScope);
            Properties weights = model.save();
            for (String key : weights.stringPropertyNames()) metadata.setProperty("nn." + key, weights.getProperty(key));
        }
        store.commit(state, metadata);
    }

    private void reportModel() {
        CfNeuralModel.Prediction p = model.predict();
        say("Локальная нейросеть: обучено на " + p.trainedSamples() + ", проверено до обучения на " + p.validationSamples()
                + "; допуск к автоматическому режиму: " + p.usable());
        if (p.validationSamples() > 0) say(String.format(Locale.ROOT,
                "Brier (меньше лучше): сеть %.4f, 50/50 %.4f, частота побед %.4f. Это проверка выборки, не гарантия.",
                p.brier(), p.baselineBrier(), p.empiricalBrier()));
        if (p.usable()) say(String.format(Locale.ROOT, "Оценка следующей победы: %.1f%%; потолок ставки %d. Исход заранее неизвестен.",
                p.probability() * 100, maximumStake));
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
        if (goal.enabled() || goal.lossLimit() != null) say("Баланс монет: " + goal.current() + "; итоговая цель: "
                + goal.target() + "; лимит потери: " + goal.lossLimit() + "; ожидание /bal: " + balanceWaiting);
        reportModel();
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
