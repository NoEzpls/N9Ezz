package dev.n9ezz.cf;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;

/** Native 0.2.1 transport contract; orchestration rebuilt around server acknowledgements. */
public final class CfController {
    private static final long COMMAND_INTERVAL = 2_000, BALANCE_TIMEOUT = 10_000, CONFIRM_TIMEOUT = 30_000;
    private final Path directory;
    private final Transport transport;
    private final LongSupplier clock;
    private final CfStore store;
    private final CfSession session = new CfSession();
    private final CfGoal goal = new CfGoal();
    private CfParser parser;
    private CfBalance balance;
    private CfNeuralModel model = new CfNeuralModel();
    private String modelScope, configurationError, blocked;
    private long maximumStake = 50_000, fixedStake = 50_000;
    private Object boundConnection;
    private Confirmation confirmation;
    private long lastCommandAt, balanceSentAt;
    private long cfRequestAt;
    private boolean sentCommand, balanceWaiting;
    private BalancePurpose balancePurpose;
    private BigDecimal expectedSettlement;
    private String lastNotice = "";

    private enum BalancePurpose { INITIAL, SETTLEMENT, CHECK, PROBE }
    private record Confirmation(String token, int count, Object connection, long createdAt) {}

    public CfController(Path directory, Transport transport) throws IOException {
        this(directory, transport, () -> System.nanoTime() / 1_000_000L);
    }

    CfController(Path directory, Transport transport, LongSupplier clock) throws IOException {
        this.directory = directory;
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        this.store = new CfStore(directory);
        if (store.state().unresolved()) session.markUnresolved();
        try {
            Properties saved = store.metadata();
            if (saved.containsKey("prefs.goal")) goal.configure(money(saved.getProperty("prefs.goal")), false);
            if (saved.containsKey("prefs.losslimit")) goal.configureLossLimit(money(saved.getProperty("prefs.losslimit")));
            maximumStake = stake(saved.getProperty("prefs.maxStake", "50000"));
            fixedStake = stake(saved.getProperty("prefs.fixedStake", "50000"));
            loadConfiguration();
            flush();
        } catch (RuntimeException ex) {
            throw new IOException("Повреждены настройки CF; автоматическая игра заблокирована", ex);
        }
    }

    private void loadConfiguration() throws IOException {
        Path file = directory.resolve("config.properties");
        Properties defaults = CfParser.defaultProperties();
        defaults.putAll(CfBalance.defaultProperties());
        defaults.setProperty("model.scope", "RaidMine-monety");
        if (!Files.exists(file)) {
            try (Writer writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
                defaults.store(writer, "N9Ezz CF Assistant 0.2.2 Experimental; exact personal RaidMine messages");
            }
        }
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { config.load(reader); }
        try {
            parser = new CfParser(config);
            balance = new CfBalance(config);
            String scope = scope(config);
            Properties saved = store.metadata();
            if (modelScope == null || !scope.equals(modelScope)) {
                Properties weights = new Properties();
                for (String key : saved.stringPropertyNames())
                    if (key.startsWith("nn.")) weights.setProperty(key.substring(3), saved.getProperty(key));
                // Preserve legacy weights only within their original configuration scope.
                model = scope.equals(saved.getProperty("model.scope")) && !weights.isEmpty()
                        ? CfNeuralModel.fromProperties(weights) : new CfNeuralModel();
                model.breakSequence();
                modelScope = scope;
            }
            configurationError = null;
        } catch (IllegalArgumentException ex) {
            parser = new CfParser(defaults);
            balance = new CfBalance(defaults);
            configurationError = ex.getMessage();
            session.pause("Ошибка конфигурации: " + configurationError);
        }
    }

    public synchronized boolean onChat(String input) {
        if (input == null || input.isBlank()) return false;
        String[] args = input.trim().split("\\s+");
        String command = args[0].toLowerCase(Locale.ROOT);
        boolean local = command.equals("/cfbot");
        try {
            if (!local) {
                boolean cf = command.equals("/cf") || command.endsWith(":cf");
                boolean bal = command.equals("/bal") || command.endsWith(":bal")
                        || (balance.queryCommand() != null && command.equals("/" + balance.queryCommand()));
                if (cf || bal) {
                    confirmation = null;
                    if (cf && args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
                        // A manual cancel is a verification request, NOT a disconnect.
                        // Keeping the connection identity lets NO_ACTIVE repair the stale marker.
                        beginCancel(false);
                    } else {
                        if (session.snapshot().runAllowed() || session.snapshot().unresolved() || balancePurpose != null)
                            session.pause("Ручная команда " + command + "; автоматическая сессия приостановлена");
                        clearBalanceRequest();
                        if (cf) {
                            model.breakSequence();
                            // Manual menu use can accept another stake. The bot can no
                            // longer attribute the next personal result to its one stake.
                            if (session.snapshot().unresolved() || (args.length > 1 && args[1].equalsIgnoreCase("create")))
                                session.markUnresolved();
                        }
                        flush();
                    }
                }
                return false;
            }
            long now = clock.getAsLong();
            String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
            switch (action) {
                case "start", "auto" -> {
                    arity(args, 3, "/cfbot " + action + " 1..500");
                    requireReady();
                    int count = count(args[2]);
                    if (fixedStake > maximumStake) throw new IllegalStateException("Фиксированная сумма выше /cfbot stake; исправьте настройки");
                    Object connection = requireConnection();
                    confirmation = new Confirmation(UUID.randomUUID().toString().substring(0, 8), count, connection, now);
                    BigDecimal worst = BigDecimal.valueOf(fixedStake).multiply(BigDecimal.valueOf(count));
                    say("До " + count + " ПОСЛЕДОВАТЕЛЬНЫХ ставок; сумма каждой " + fixedStake
                            + "; верхний предел " + maximumStake + "; лимит убытка "
                            + (goal.lossLimit() == null ? "off (максимум по числу ставок " + worst.toPlainString() + ")" : goal.lossLimit().toPlainString())
                            + "; итоговая цель " + value(goal.amount()) + ".");
                    if (action.equals("auto")) say("auto использует фиксированную сумму. Нейросеть — диагностика; случайный исход заранее неизвестен.");
                    say("Подтвердите за 30 секунд: /cfbot confirm " + confirmation.token());
                    store.log("PREVIEW", "count=" + count + ", fixed=" + fixedStake + ", cap=" + maximumStake
                            + ", lossLimit=" + value(goal.lossLimit()), null, null);
                }
                case "confirm" -> {
                    arity(args, 3, "/cfbot confirm код");
                    Confirmation pending = confirmation;
                    confirmation = null; // One-use token, including failed/replayed confirmation.
                    if (pending == null || !pending.token().equals(args[2]) || now < pending.createdAt()
                            || now - pending.createdAt() >= CONFIRM_TIMEOUT)
                        throw new IllegalStateException("Подтверждение отсутствует или истекло; выполните /cfbot start заново");
                    requireReady();
                    if (requireConnection() != pending.connection()) throw new IllegalStateException("Соединение изменилось; запуск отменён");
                    boundConnection = pending.connection();
                    session.selectStake(fixedStake);
                    session.start(pending.count(), now);
                    goal.begin();
                    expectedSettlement = null;
                    balancePurpose = BalancePurpose.INITIAL;
                    flush();
                    say("Запуск подтверждён. Проверяется баланс; /cfbot stop немедленно запрещает новые ставки.");
                }
                case "stop", "record" -> {
                    confirmation = null;
                    session.stop("Новые ставки запрещены пользователем");
                    // A result already confirmed before stop may still receive its final /bal.
                    if (balancePurpose != BalancePurpose.SETTLEMENT) clearBalanceRequest();
                    flush();
                }
                case "stake", "fixed" -> {
                    arity(args, 3, "/cfbot " + action + " 50000..5000000");
                    requireSettingsIdle();
                    long amount = stake(args[2]);
                    if (action.equals("stake")) maximumStake = amount; else fixedStake = amount;
                    flush();
                    say("Фиксированная сумма " + fixedStake + "; верхний предел " + maximumStake);
                }
                case "goal", "losslimit" -> {
                    arity(args, 3, "/cfbot " + action + " сумма|off");
                    requireSettingsIdle();
                    BigDecimal amount = args[2].equalsIgnoreCase("off") ? null : money(args[2]);
                    if (action.equals("goal")) { if (amount == null) goal.disable(); else goal.configure(amount, false); }
                    else goal.configureLossLimit(amount);
                    flush();
                    say("Итоговая цель " + value(goal.amount()) + "; лимит убытка " + value(goal.lossLimit()));
                }
                case "balance", "balancecheck" -> {
                    if (balancePurpose != null || session.snapshot().runAllowed() || session.snapshot().unresolved())
                        throw new IllegalStateException("Проверка /bal занята либо ставка не завершена; /cfbot status");
                    checkConfiguration();
                    if (!balance.configured()) throw new IllegalStateException("Настройте точный личный баланс в config.properties");
                    confirmation = null;
                    boundConnection = requireConnection();
                    balancePurpose = action.equals("balance") ? BalancePurpose.PROBE : BalancePurpose.CHECK;
                    say("Один запрос /bal поставлен в очередь; тайм-аут ответа 10 секунд.");
                }
                case "verify" -> { arity(args, 2, "/cfbot verify"); beginCancel(true); }
                case "reset" -> {
                    arity(args, 3, "/cfbot reset checked");
                    if (!args[2].equalsIgnoreCase("checked")) throw new IllegalArgumentException("/cfbot reset checked");
                    // Retain the command, but do not trust a local assertion over server state.
                    session.clearUnresolved();
                    confirmation = null;
                    flush();
                    say("Незавершённой ставки нет. Если требуется проверка сервера: /cf cancel или /cfbot verify.");
                }
                case "reload" -> { requireSettingsIdle(); loadConfiguration(); flush(); say(configurationError == null ? "Конфигурация загружена; автозапуска нет" : configurationError); }
                case "selftest" -> { CfDiagnostics.selftest(); say("selftest: OK — синтетические сообщения и восстановление состояния, реальных ставок нет"); }
                case "test", "dryrun" -> {
                    arity(args, 3, "/cfbot dryrun 1..500");
                    if (session.snapshot().runAllowed() || session.snapshot().unresolved() || balancePurpose != null)
                        throw new IllegalStateException("Сначала завершите/проверьте реальную ставку");
                    confirmation = null;
                    say(CfSimulation.run(directory.resolve("simulations"), count(args[2])));
                }
                case "ai" -> {
                    var p = model.predict();
                    say("Нейросеть: обучающих примеров " + p.trainedSamples() + ", проверочных " + p.validationSamples()
                            + "; Brier=" + p.brier() + ", 50/50=" + p.baselineBrier()
                            + ". Анализ истории не предсказывает случайный CoinFlip; сумма не повышается по прогнозу.");
                }
                case "stats", "status" -> report();
                case "help" -> say("/cfbot start|auto 1..500; confirm код; stop|record; stake сумма (предел); fixed сумма; goal сумма|off; losslimit сумма|off; balance|balancecheck; selftest; status|stats; ai; reload; reset checked; verify (один /cf cancel); dryrun|test 1..500");
                default -> throw new IllegalArgumentException("Неизвестная команда; /cfbot help");
            }
        } catch (IOException ex) { fail("Ошибка записи: " + ex.getClass().getSimpleName()); }
        catch (IllegalArgumentException | IllegalStateException ex) { say(ex.getMessage()); }
        catch (Exception ex) { fail("Ошибка интерфейса: " + ex.getClass().getSimpleName()); }
        notice();
        return local;
    }

    public synchronized void onTick(long now) {
        if (blocked != null) return;
        try {
            Object connection = transport.connection();
            if (confirmation != null && (connection != confirmation.connection() || now < confirmation.createdAt()
                    || now - confirmation.createdAt() >= CONFIRM_TIMEOUT)) {
                confirmation = null;
                session.pause("Подтверждение истекло или соединение изменилось");
            }
            if (boundConnection != null && connection != boundConnection) {
                onDisconnect("Дисконнект или смена сервера; требуется новый запуск с подтверждением");
                return;
            }
            // Tick deadlines even after stop; lack of new chat must never disable timeout checks.
            session.tick(now, connection != null, true);
            if (balanceWaiting && (now < balanceSentAt || now - balanceSentAt >= BALANCE_TIMEOUT)) {
                clearBalanceRequest();
                session.pause("Нет подтверждённого личного /bal за 10 секунд; повторных запросов нет");
            }
            if (balancePurpose == BalancePurpose.INITIAL && !session.awaitingInitialBalance()) clearBalanceRequest();
            if (balancePurpose == BalancePurpose.SETTLEMENT && !session.awaitingSettlement()) clearBalanceRequest();
            boolean screen = transport.screenOpen();
            if (balancePurpose != null) {
                if (!balanceWaiting && !screen && maySend(now)) sendBalance(now);
                flush();
                notice();
                return;
            }
            if (session.snapshot().runAllowed() && session.snapshot().state() == CfSession.State.IDLE) checkBudget();
            String next = session.tick(now, connection != null && connection == boundConnection, screen || !maySend(now));
            if (next != null) cfRequestAt = now;
            flush(); // Durable unresolved marker BEFORE any command can leave the client.
            if (next != null) {
                store.log("SEND", next, Long.toString(session.snapshot().stake()), null);
                send(next);
            }
        } catch (IOException ex) { fail("Ошибка записи: " + ex.getClass().getSimpleName()); }
        catch (Exception ex) { fail("Ошибка отправки/интерфейса: " + ex.getClass().getSimpleName()); }
        notice();
    }

    public synchronized void onSystemMessage(String raw, Object connection, long now) {
        if (blocked != null) return;
        try {
            // 'now' is packet arrival time. The state-machine clock must instead
            // use processing time: a packet arriving just after queue drain may
            // legitimately be older than the previous tick when processed.
            long processedAt = clock.getAsLong();
            CfParser.Parsed parsed = parser.parse(raw);
            boolean ours = connection != null && connection == boundConnection && connection == transport.connection();
            if (balanceWaiting && ours) {
                if (balancePurpose == BalancePurpose.PROBE) store.log("BALANCE_PROBE", raw, null, null);
                BigDecimal amount = balance.parse(raw);
                if (amount != null) {
                    if (now < balanceSentAt || processedAt < balanceSentAt || processedAt - balanceSentAt >= BALANCE_TIMEOUT) {
                        clearBalanceRequest(); session.pause("Поздний ответ /bal; требуется новая проверка");
                    } else {
                        BalancePurpose purpose = balancePurpose;
                        clearBalanceRequest();
                        if (purpose == BalancePurpose.SETTLEMENT && expectedSettlement != null && amount.compareTo(expectedSettlement) != 0) {
                            session.pause("Баланс после результата не совпал с ожидаемым; проверьте ставку и другие изменения монет");
                            store.log("BALANCE_MISMATCH", "expected=" + expectedSettlement + ", actual=" + amount, null, null);
                        } else {
                            goal.accept(amount);
                            if (purpose == BalancePurpose.INITIAL || purpose == BalancePurpose.SETTLEMENT) session.balanceVerified(processedAt);
                            expectedSettlement = null;
                            store.log("BALANCE_CONFIRMED", purpose.name(), amount.toPlainString(), null);
                            say("Баланс монет: " + amount.toPlainString());
                        }
                    }
                    flush(); notice(); return;
                }
            }
            if (parsed.signal() == null) {
                if (parsed.candidate()) {
                    store.log("OBSERVED_CANDIDATE", parsed.normalized(), parsed.reportedAmount(), parsed.opponent());
                    // Public chat mentioning /cf caused the 0.2.1 desync. It is observation only.
                    if (ours && parsed.serverMessage() && !parsed.playerChat()
                            && (session.snapshot().runAllowed() || session.snapshot().unresolved()))
                        session.pause("Нераспознанный ответ сервера CF; проверьте messages.tsv");
                }
                flush(); notice(); return;
            }
            if (ours && parsed.signal() != null && now < cfRequestAt) {
                store.log("OBSERVED_STALE", parsed.normalized(), parsed.reportedAmount(), parsed.opponent());
                return; // A packet received before this request cannot acknowledge it.
            }
            boolean result = parsed.signal() == CfSession.Signal.WIN || parsed.signal() == CfSession.Signal.LOSS;
            // A result must match the single acknowledged stake and the same live connection.
            if (ours && result && session.snapshot().unresolved()) {
                BigDecimal amount = reportedMoney(parsed.reportedAmount());
                if (amount == null || amount.signum() <= 0 || amount.compareTo(BigDecimal.valueOf(session.snapshot().stake())) > 0
                        || (parsed.signal() == CfSession.Signal.LOSS && amount.compareTo(BigDecimal.valueOf(session.snapshot().stake())) != 0)
                        || (parsed.signal() == CfSession.Signal.WIN && amount.compareTo(BigDecimal.valueOf(session.snapshot().stake()).multiply(new BigDecimal("0.85"))) != 0)) {
                    session.pause("Сумма результата не соответствует ставке; исход не подтверждён");
                    store.log("OBSERVED_" + parsed.signal(), parsed.normalized(), parsed.reportedAmount(), parsed.opponent());
                    flush(); notice(); return;
                }
            }
            boolean accepted = ours && session.onSignal(parsed.signal(), processedAt);
            if (accepted && result) {
                BigDecimal amount = reportedMoney(parsed.reportedAmount());
                expectedSettlement = goal.current() == null ? null : goal.current().add(parsed.signal() == CfSession.Signal.WIN ? amount : amount.negate());
                model.observe(parsed.signal() == CfSession.Signal.WIN);
                CfStore.State old = store.state();
                persist(new CfStore.State(session.snapshot().unresolved(),
                        Math.addExact(old.wins(), parsed.signal() == CfSession.Signal.WIN ? 1 : 0),
                        Math.addExact(old.losses(), parsed.signal() == CfSession.Signal.LOSS ? 1 : 0)));
                balancePurpose = BalancePurpose.SETTLEMENT;
                balanceWaiting = false;
            }
            if (ours && !accepted && parsed.signal() == CfSession.Signal.CREATED) {
                session.pause("Неожиданное подтверждение создания; требуется проверка ставки");
                session.markUnresolved();
            } else if (ours && !accepted && session.snapshot().unresolved()
                    && (parsed.signal() == CfSession.Signal.NO_ACTIVE || parsed.signal() == CfSession.Signal.CANCELLED)) {
                // An unsolicited contradiction is not evidence for the currently
                // awaited request. Close attribution until an explicit cancel check.
                session.markUnresolved();
                session.pause("Ответ об отсутствии/отмене ставки без запроса проверки; состояние неоднозначно");
            } else if (ours && !accepted && result && session.snapshot().runAllowed()
                    && !session.snapshot().unresolved()) {
                session.pause("Результат без связанной ставки; новые ставки запрещены до проверки");
                session.markUnresolved();
            }
            store.log((accepted ? "CONFIRMED_" : "OBSERVED_") + parsed.signal(), parsed.normalized(), parsed.reportedAmount(), parsed.opponent());
            if (accepted && (parsed.signal() == CfSession.Signal.CANCEL_REQUIRED || parsed.signal() == CfSession.Signal.LIMIT
                    || parsed.signal() == CfSession.Signal.INSUFFICIENT || parsed.signal() == CfSession.Signal.NO_ACTIVE
                    || parsed.signal() == CfSession.Signal.CANCELLED)) clearBalanceRequest();
            flush();
        } catch (IOException ex) { fail("Ошибка записи: " + ex.getClass().getSimpleName()); }
        catch (Exception ex) { fail("Ошибка обработки сообщения: " + ex.getClass().getSimpleName()); }
        notice();
    }

    public synchronized void onDisconnect(String reason) {
        confirmation = null;
        clearBalanceRequest();
        boundConnection = null;
        expectedSettlement = null;
        model.breakSequence();
        session.pause(reason);
        try { flush(); } catch (IOException ex) { fail("Ошибка записи при дисконнекте"); }
        notice();
    }

    public synchronized void onCaptureFailure(String reason) { fail(reason); }
    synchronized CfSession.Snapshot snapshot() { return session.snapshot(); }

    private void beginCancel(boolean sendCommand) throws Exception {
        if (blocked != null) throw new IllegalStateException(blocked);
        long now = clock.getAsLong();
        if (session.awaitingCancel()) throw new IllegalStateException("Проверка /cf cancel уже ожидает ответа");
        if (sendCommand && !maySend(now)) throw new IllegalStateException("Подождите 2 секунды после предыдущей команды");
        Object connection = requireConnection();
        confirmation = null;
        clearBalanceRequest();
        boundConnection = connection;
        cfRequestAt = now;
        model.breakSequence();
        session.beginCancelVerification(now);
        flush();
        store.log("VERIFY_CANCEL", sendCommand ? "cfbot verify: один cf cancel" : "ручной cf cancel", null, null);
        if (sendCommand) send("cf cancel");
        else { sentCommand = true; lastCommandAt = cfRequestAt = clock.getAsLong(); }
        say("Ожидается ответ на /cf cancel; автоматического продолжения не будет.");
    }

    private void sendBalance(long now) throws Exception {
        if (balancePurpose == BalancePurpose.SETTLEMENT) session.beginBalanceVerification(now);
        balanceWaiting = true;
        balanceSentAt = now;
        flush();
        store.log("SEND_BALANCE", balance.queryCommand(), null, null);
        send(balance.queryCommand());
    }

    private void send(String command) throws Exception {
        if (blocked != null || boundConnection == null || transport.connection() != boundConnection)
            throw new IllegalStateException("Соединение изменилось до отправки");
        sentCommand = true;
        // Start correlation and command spacing immediately before transport,
        // after durable writes; disk latency is not part of the send interval.
        lastCommandAt = clock.getAsLong();
        if (command.startsWith("cf create ") || command.equals("cf cancel")) cfRequestAt = lastCommandAt;
        if (balanceWaiting && command.equals(balance.queryCommand())) balanceSentAt = lastCommandAt;
        transport.sendCommand(command);
    }

    private void checkBudget() {
        if (goal.current() == null) session.pause("Нет подтверждённого баланса");
        else if (goal.reached()) session.stop("Достигнута цель итогового баланса");
        else if (!goal.canAfford(fixedStake)) session.pause("Недостаточно подтверждённых монет для фиксированной суммы");
        else if (goal.lossLimit() != null && goal.initial().subtract(goal.current()).add(BigDecimal.valueOf(fixedStake)).compareTo(goal.lossLimit()) > 0)
            session.pause("Следующая ставка может превысить лимит убытка; новые ставки запрещены");
    }

    private void requireReady() {
        checkConfiguration();
        if (!parser.configured() || !balance.configured()) throw new IllegalStateException("Нужны точные включённые шаблоны CF и личного /bal");
        if (session.snapshot().unresolved()) throw new IllegalStateException("Незавершённая ставка: /cf cancel или /cfbot verify, затем дождитесь ответа сервера");
        if (session.snapshot().runAllowed() || balancePurpose != null || session.awaitingCancel()) throw new IllegalStateException("Сессия или проверка уже выполняется");
    }
    private void checkConfiguration() {
        if (blocked != null) throw new IllegalStateException(blocked);
        if (configurationError != null) throw new IllegalStateException(configurationError);
    }
    private void requireSettingsIdle() {
        if (session.snapshot().runAllowed() || session.snapshot().unresolved() || balancePurpose != null || session.awaitingCancel())
            throw new IllegalStateException("Сначала остановите сессию и завершите проверку ставки");
        confirmation = null;
    }
    private Object requireConnection() throws Exception {
        Object connection = transport.connection();
        if (connection == null) throw new IllegalStateException("Нет игрового соединения");
        return connection;
    }
    private boolean maySend(long now) { return !sentCommand || (now >= lastCommandAt && now - lastCommandAt >= COMMAND_INTERVAL); }
    private void clearBalanceRequest() { balancePurpose = null; balanceWaiting = false; }

    private void flush() throws IOException {
        for (CfSession.Transition transition : session.drainTransitions()) {
            store.log("STATE_TRANSITION", transition.from() + " -> " + transition.to() + "; " + transition.reason()
                    + "; monotonic_ms=" + transition.atMillis() + "; pending=" + session.snapshot().unresolved()
                    + "; submitted=" + session.snapshot().submitted() + "; completed=" + session.snapshot().completed(), null, null);
        }
        CfStore.State old = store.state();
        persist(new CfStore.State(session.snapshot().unresolved(), old.wins(), old.losses()));
    }
    private void persist(CfStore.State state) throws IOException {
        Properties metadata = store.metadata();
        metadata.setProperty("prefs.maxStake", Long.toString(maximumStake));
        metadata.setProperty("prefs.fixedStake", Long.toString(fixedStake));
        metadata.remove("prefs.goal"); metadata.remove("prefs.losslimit");
        if (goal.enabled()) metadata.setProperty("prefs.goal", goal.amount().toPlainString());
        if (goal.lossLimit() != null) metadata.setProperty("prefs.losslimit", goal.lossLimit().toPlainString());
        if (modelScope != null) {
            metadata.setProperty("model.scope", modelScope);
            Properties weights = model.save();
            for (String key : weights.stringPropertyNames()) metadata.setProperty("nn." + key, weights.getProperty(key));
        }
        metadata.setProperty("session.state", session.snapshot().state().name());
        metadata.setProperty("session.reason", session.snapshot().reason());
        // A tick may only inspect a deadline. Avoid fsync and model-file replacement
        // twenty times per second when no persisted state actually changed.
        if (!state.equals(store.state()) || !metadata.equals(store.metadata())) store.commit(state, metadata);
    }
    private void fail(String reason) {
        if (blocked != null) return;
        blocked = reason + "; новые ставки заблокированы до перезапуска клиента";
        confirmation = null;
        clearBalanceRequest();
        session.failSend(blocked);
        try { flush(); } catch (Exception ignored) { /* Do not resume after a failed durable write. */ }
        say(blocked);
    }
    private void report() {
        var s = session.snapshot();
        BigDecimal pnl = goal.current() == null || goal.initial() == null ? null : goal.current().subtract(goal.initial());
        say("Состояние " + s.state() + "; незавершённая ставка=" + s.unresolved() + "; новые ставки=" + s.runAllowed()
                + "; игр " + s.completed() + "/" + s.target() + "; отправлено " + s.submitted() + "; W/L " + s.wins() + "/" + s.losses());
        say("Баланс монет=" + value(goal.current()) + "; прибыль/убыток от старта=" + value(pnl)
                + "; fixed=" + fixedStake + "; stake cap=" + maximumStake + "; причина: " + s.reason());
        say("Всего подтверждено W/L " + store.state().wins() + "/" + store.state().losses() + "; OBSERVED_CANDIDATE не является исходом.");
        if (blocked != null) say("Блокировка: " + blocked);
        if (configurationError != null) say("Ошибка конфигурации: " + configurationError);
    }
    private void notice() {
        String notice = session.snapshot().state() + ": " + session.snapshot().reason();
        if (!notice.equals(lastNotice)) { lastNotice = notice; say(notice); }
    }
    private void say(String message) {
        try { transport.feedback("[CF 0.2.2] " + message); }
        catch (Exception ex) { System.err.println("[CF] feedback unavailable: " + ex.getClass().getSimpleName()); }
    }
    private static void arity(String[] args, int count, String usage) { if (args.length != count) throw new IllegalArgumentException(usage); }
    private static int count(String value) { int count = Integer.parseInt(value); if (count < 1 || count > 500) throw new IllegalArgumentException("Число игр: 1..500"); return count; }
    private static long stake(String value) { long stake = Long.parseLong(value); if (stake < 50_000 || stake > 5_000_000) throw new IllegalArgumentException("Сумма: 50000..5000000"); return stake; }
    private static BigDecimal money(String value) {
        if (!value.matches("[0-9]{1,16}(?:\\.[0-9]{1,2})?")) throw new IllegalArgumentException("Сумма: цифры, до двух знаков после точки");
        BigDecimal amount = new BigDecimal(value);
        if (amount.signum() <= 0 || amount.compareTo(CfBalance.MAX_AMOUNT) > 0) throw new IllegalArgumentException("Недопустимая сумма");
        return amount;
    }
    private static BigDecimal reportedMoney(String amount) {
        try { return amount == null ? null : new BigDecimal(amount.replace(",", "")); }
        catch (NumberFormatException ex) { return null; }
    }
    private static String value(BigDecimal value) { return value == null ? "не задано/неизвестно" : value.toPlainString(); }
    private static String scope(Properties properties) {
        StringBuilder key = new StringBuilder(properties.getProperty("model.scope", "RaidMine-monety"));
        for (String field : new String[]{"created.regex", "insufficient.regex", "limit.regex", "win.regex", "loss.regex", "balance.regex", "balance.command", "balance.grouping", "balance.decimal"})
            key.append('\n').append(field).append('=').append(properties.getProperty(field, ""));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    public interface Transport {
        Object connection() throws Exception;
        boolean screenOpen() throws Exception;
        void sendCommand(String command) throws Exception;
        void feedback(String message) throws Exception;
    }
}
