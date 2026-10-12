package dev.n9ezz.cf;

import java.util.Objects;

/**
 * A bounded session with an explicitly selected stake. This class has no Minecraft or network dependencies:
 * the caller sends a returned command once, and calls {@link #failSend(String)} on failure.
 * It deliberately makes no prediction from the outcomes of independent coin flips.
 */
public final class CfSession {
    public static final long STAKE = 50_000L;
    public static final long MAX_STAKE = 5_000_000L;
    public static final int MAX_TARGET = 500;
    public static final long CREATE_INTERVAL_MILLIS = 2_000L;
    public static final long CREATE_TIMEOUT_MILLIS = 10_000L;
    public static final long RESULT_TIMEOUT_MILLIS = 180_000L;

    public enum Signal { CREATED, INSUFFICIENT, LIMIT, WIN, LOSS }
    public enum Mode { STOPPED, RUNNING }
    public enum Phase { IDLE, CREATING, WAITING_RESULT, RECOVERY_REQUIRED }

    public record Snapshot(Mode mode, Phase phase, String reason, int target,
                           int submitted, int completed, int wins, int losses,
                           boolean unresolved, long stake) { }

    private Mode mode = Mode.STOPPED;
    private Phase phase = Phase.IDLE;
    private String reason = "Сессия не запущена";
    private int target;
    private int submitted;
    private int completed;
    private int wins;
    private int losses;
    private boolean unresolved;
    private boolean hasSubmitted;
    private long lastSubmittedAt;
    private long phaseStartedAt;
    private boolean hasObservedTime;
    private long lastObservedAt;
    private long stake = STAKE;

    /**
     * Select the amount before tick() creates a pending wager and before the caller
     * records its durable marker. A pending wager keeps its amount until settled.
     * The session itself makes no model-based prediction or guarantee of winning.
     */
    public synchronized void selectStake(long amount) {
        if (amount < STAKE || amount > MAX_STAKE) {
            throw new IllegalArgumentException("Ставка должна быть от " + STAKE + " до " + MAX_STAKE);
        }
        if (unresolved || phase != Phase.IDLE) {
            throw new IllegalStateException("Нельзя менять сумму незавершённой ставки");
        }
        stake = amount;
    }

    /** An explicit start never carries over an unconfirmed stake from an earlier run. */
    public synchronized void start(int target, long nowMillis) {
        if (target < 1 || target > MAX_TARGET) {
            throw new IllegalArgumentException("Число игр должно быть от 1 до " + MAX_TARGET);
        }
        if (mode == Mode.RUNNING) {
            throw new IllegalStateException("Сессия уже запущена");
        }
        if (unresolved) {
            throw new IllegalStateException("Сначала проверьте незавершённую ставку на сервере");
        }
        this.target = target;
        submitted = completed = wins = losses = 0;
        // Preserve spacing even across explicit stop/start or one-game sessions.
        phase = Phase.IDLE;
        mode = Mode.RUNNING;
        reason = "Ожидание отправки ставки";
        hasObservedTime = true;
        lastObservedAt = nowMillis;
    }

    /**
     * Returns at most one ordinary chat command, with no leading slash.
     * Mark the durable unresolved flag before actually sending this command. A send
     * exception is ambiguous: the server may already have received the command.
     */
    public synchronized String tick(long nowMillis, boolean connected, boolean screenOpen) {
        if (mode != Mode.RUNNING) {
            return null;
        }
        if (!connected) {
            stop("Соединение потеряно; автоматическое продолжение отключено");
            return null;
        }
        if (!observeTime(nowMillis)) {
            return null;
        }
        expirePending(nowMillis);
        if (mode != Mode.RUNNING) {
            return null;
        }
        // Although the server permits two bets, unlabelled chat replies cannot
        // reliably identify them. Never create a second unresolved stake.
        if (unresolved || phase != Phase.IDLE || screenOpen) {
            return null;
        }
        if (completed >= target || submitted >= target) {
            stop("Запрошенное число игр выполнено");
            return null;
        }
        if (hasSubmitted && !elapsed(nowMillis, lastSubmittedAt, CREATE_INTERVAL_MILLIS)) {
            return null;
        }
        unresolved = true;
        phase = Phase.CREATING;
        phaseStartedAt = lastSubmittedAt = nowMillis;
        hasSubmitted = true;
        submitted++;
        reason = "Ожидание подтверждения создания ставки";
        // Only explicit selection changes the amount. Outcomes by themselves never
        // alter it, and the selected amount stays frozen while this bet is pending.
        return "cf create " + stake;
    }

    /**
     * Returns true only when a signal changed the tracked pending stake or paused it.
     * Call only for messages already matched to the server's exact response format.
     * A late result can settle a stopped session, but can never restart spending.
     */
    public synchronized boolean onSignal(Signal signal, long nowMillis) {
        Objects.requireNonNull(signal, "signal");
        if (!unresolved || phase == Phase.RECOVERY_REQUIRED) {
            return false;
        }
        observeTime(nowMillis);
        // Message delivery can occur after a long client frame. Honor the deadline
        // even if tick() has not had a chance to notice it yet; a late reply may
        // settle the stake below, but it must not resume the stopped session.
        expirePending(nowMillis);
        switch (signal) {
            case CREATED -> {
                if (phase != Phase.CREATING) {
                    // Duplicate acknowledgements must not extend the result timeout.
                    return false;
                }
                phase = Phase.WAITING_RESULT;
                phaseStartedAt = nowMillis;
                if (mode == Mode.RUNNING) {
                    reason = "Ожидание результата ставки";
                }
                return true;
            }
            case INSUFFICIENT, LIMIT -> {
                if (phase == Phase.CREATING) {
                    // A definitive negative creation acknowledgement leaves no
                    // newly created stake. Do not retry it automatically.
                    unresolved = false;
                    phase = Phase.IDLE;
                    stop(signal == Signal.INSUFFICIENT
                            ? "Недостаточно средств; сессия остановлена"
                            : "Достигнут лимит ставок; сессия остановлена");
                } else {
                    // This cannot reject an already confirmed stake. It may refer
                    // to a manual command, so retain the unresolved flag.
                    stop("Ответ сервера не соответствует текущей ставке; проверьте её вручную");
                }
                return true;
            }
            case WIN, LOSS -> {
                if (phase != Phase.WAITING_RESULT) {
                    // Without a creation ACK a result may belong to an earlier or
                    // manual game. Do not count it and do not make another bet.
                    stop("Результат получен до подтверждения ставки; требуется проверка");
                    return true;
                }
                completed++;
                if (signal == Signal.WIN) {
                    wins++;
                } else {
                    losses++;
                }
                unresolved = false;
                phase = Phase.IDLE;
                if (mode == Mode.RUNNING) {
                    if (completed >= target) {
                        stop("Запрошенное число игр выполнено");
                    } else {
                        reason = "Ожидание следующей ставки";
                    }
                }
                return true;
            }
        }
        throw new AssertionError("Unhandled signal: " + signal);
    }

    /** Pausing never cancels a bet on the server or forgets its unresolved status. */
    public synchronized void stop(String reason) {
        mode = Mode.STOPPED;
        this.reason = reason == null || reason.isBlank() ? "Сессия остановлена" : reason;
    }

    public synchronized void failSend(String reason) {
        stop(reason == null || reason.isBlank()
                ? "Ошибка отправки; проверьте ставку на сервере"
                : "Ошибка отправки: " + reason);
    }

    /**
     * An explicit user recovery action, after checking/cancelling any server bet.
     * This does not send a command and does not resume the previous session.
     */
    public synchronized void clearUnresolved() {
        if (mode == Mode.RUNNING) {
            throw new IllegalStateException("Перед сбросом остановите сессию");
        }
        unresolved = false;
        phase = Phase.IDLE;
        reason = "Проверка незавершённой ставки подтверждена; сессия остановлена";
    }

    /** Restore only a persisted safety marker, never auto-resume a previous run. */
    public synchronized void markUnresolved() {
        if (mode == Mode.RUNNING) {
            throw new IllegalStateException("Нельзя восстанавливать состояние во время сессии");
        }
        if (!unresolved) {
            unresolved = true;
            // After restart there is no reliable chat correlation with the old bet.
            phase = Phase.RECOVERY_REQUIRED;
        }
        reason = "Сохранена незавершённая ставка; проверьте её перед новым запуском";
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mode, phase, reason, target, submitted, completed,
                wins, losses, unresolved, stake);
    }

    private boolean observeTime(long nowMillis) {
        if (hasObservedTime && nowMillis < lastObservedAt) {
            stop("Время изменилось назад; сессия остановлена");
            return false;
        }
        hasObservedTime = true;
        lastObservedAt = nowMillis;
        return true;
    }

    private void expirePending(long nowMillis) {
        if (mode != Mode.RUNNING) {
            return;
        }
        if (phase == Phase.CREATING
                && elapsed(nowMillis, phaseStartedAt, CREATE_TIMEOUT_MILLIS)) {
            stop("Нет подтверждения создания ставки; проверьте её на сервере");
        } else if (phase == Phase.WAITING_RESULT
                && elapsed(nowMillis, phaseStartedAt, RESULT_TIMEOUT_MILLIS)) {
            stop("Нет результата ставки; проверьте её на сервере");
        }
    }

    /** Compare deadlines without overflowing at either end of the long range. */
    private static boolean elapsed(long now, long since, long duration) {
        return now >= since && since <= Long.MAX_VALUE - duration
                && now >= since + duration;
    }
}
