package dev.n9ezz.cf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One sequential CoinFlip run. This class never sends a packet or a command.
 * A create command returned by {@link #tick} is already marked unresolved, so
 * the caller can persist that fact before attempting any network operation.
 */
public final class CfSession {
    public static final long STAKE = 50_000L;
    public static final long MAX_STAKE = 5_000_000L;
    public static final int MAX_TARGET = 500;
    public static final long CREATE_INTERVAL_MILLIS = 2_000L;
    public static final long CREATE_TIMEOUT_MILLIS = 10_000L;
    public static final long RESULT_TIMEOUT_MILLIS = 180_000L;
    public static final long VERIFY_TIMEOUT_MILLIS = 10_000L;

    public enum State { IDLE, CREATING, ACTIVE, SETTLING, VERIFYING, PAUSED, STOPPED, ERROR }
    /** Legacy names retained for readers of 0.2.1 snapshots. */
    public enum Mode { STOPPED, RUNNING }
    public enum Phase { IDLE, CREATING, WAITING_RESULT, RECOVERY_REQUIRED }
    /** Keep the original five values in their original order. */
    public enum Signal { CREATED, INSUFFICIENT, LIMIT, WIN, LOSS, CANCEL_REQUIRED, NO_ACTIVE, CANCELLED }

    private enum Await { NONE, CREATE, RESULT, SETTLEMENT_READY, INITIAL_BALANCE, SETTLEMENT_BALANCE, CANCEL, RECOVERY }

    private State state = State.STOPPED;
    private State haltedState = State.STOPPED;
    private Await await = Await.NONE;
    private String reason = "Сессия не запущена";
    private String haltedReason = reason;
    private boolean runAllowed;
    private boolean unresolved;
    private boolean hasSubmitted;
    private boolean hasObservedTime;
    private long lastObservedAt;
    private long lastSubmittedAt;
    private long awaitedAt;
    private long stake = STAKE;
    private int target;
    private int submitted;
    private int completed;
    private int wins;
    private int losses;
    private final ArrayList<Transition> transitions = new ArrayList<>();

    public synchronized void selectStake(long amount) {
        if (amount < STAKE || amount > MAX_STAKE) {
            throw new IllegalArgumentException("Ставка должна быть от 50000 до 5000000");
        }
        if (unresolved || await != Await.NONE) {
            throw new IllegalStateException("Нельзя менять сумму незавершённой ставки или проверки");
        }
        stake = amount;
    }

    /** Caller must obtain explicit user confirmation before invoking this. */
    public synchronized void start(int count, long now) {
        if (count < 1 || count > MAX_TARGET) {
            throw new IllegalArgumentException("Число игр должно быть от 1 до 500");
        }
        if (runAllowed) throw new IllegalStateException("Сессия уже запущена");
        if (unresolved || await != Await.NONE) {
            throw new IllegalStateException("Сначала проверьте незавершённую ставку или запрос на сервере");
        }
        if (!observeTime(now)) throw new IllegalStateException(reason);
        target = count;
        submitted = completed = wins = losses = 0;
        runAllowed = true;
        haltedState = State.STOPPED;
        haltedReason = "Сессия остановлена";
        await = Await.INITIAL_BALANCE;
        awaitedAt = now;
        transition(State.VERIFYING, "Проверка баланса перед первой ставкой", now);
    }

    public synchronized String tick(long now, boolean connected, boolean screenOpen) {
        if (!observeTime(now)) return null;
        if (!connected && (runAllowed || unresolved || await != Await.NONE)) {
            pause("Соединение потеряно; продолжение требует нового подтверждения");
            // A reply from a new connection cannot complete an old request.
            if (unresolved) await = Await.RECOVERY;
            else await = Await.NONE;
            return null;
        }
        expirePending(now);
        if (!runAllowed || state != State.IDLE || unresolved || await != Await.NONE || screenOpen) return null;
        if (completed >= target || submitted >= target) {
            stop("Запрошенное число последовательных игр выполнено");
            return null;
        }
        if (hasSubmitted && !elapsed(now, lastSubmittedAt, CREATE_INTERVAL_MILLIS)) return null;

        // Mark before sending: an exception can occur after the server received
        // the command. Such an exception must never permit an automatic retry.
        unresolved = true;
        hasSubmitted = true;
        lastSubmittedAt = awaitedAt = now;
        submitted++;
        await = Await.CREATE;
        transition(State.CREATING, "Ожидание подтверждения создания ставки", now);
        return "cf create " + stake;
    }

    /** Only pass authenticated, personal server messages to this method. */
    public synchronized boolean onSignal(Signal signal, long now) {
        Objects.requireNonNull(signal, "signal");
        if (!observeTime(now)) return false;
        expirePending(now);
        switch (signal) {
            case CANCEL_REQUIRED, LIMIT -> {
                // The server says a stake exists. Clearing the local flag here
                // was the 0.2.1 desynchronization bug; an explicit check must
                // instead obtain NO_ACTIVE or CANCELLED from this connection.
                unresolved = true;
                await = Await.RECOVERY;
                pause("Сервер просит отменить текущую ставку; выполните /cf cancel или /cfbot verify");
                return true;
            }
            case NO_ACTIVE, CANCELLED -> {
                if (await != Await.CANCEL) return false;
                unresolved = false;
                await = Await.NONE;
                runAllowed = false;
                haltedState = State.PAUSED;
                haltedReason = signal == Signal.NO_ACTIVE
                        ? "Сервер подтвердил отсутствие активной ставки; требуется новый запуск с подтверждением"
                        : "Сервер подтвердил отмену ставки; требуется новый запуск с подтверждением";
                transition(State.PAUSED, haltedReason, now);
                return true;
            }
            case CREATED -> {
                if (!unresolved || await != Await.CREATE) return false;
                await = Await.RESULT;
                awaitedAt = now;
                transition(State.ACTIVE, runAllowed ? "Ставка подтверждена; ожидание результата"
                        : "Ставка подтверждена после остановки; новые ставки запрещены", now);
                return true;
            }
            case INSUFFICIENT -> {
                if (!unresolved || await != Await.CREATE) return false;
                unresolved = false;
                await = Await.NONE;
                pause("Недостаточно средств; сервер не создал ставку");
                return true;
            }
            case WIN, LOSS -> {
                if (!unresolved || await != Await.RESULT) {
                    if (unresolved && await == Await.CREATE) {
                        await = Await.RECOVERY;
                        pause("Результат получен без подтверждения создания; требуется проверка ставки");
                    }
                    return false;
                }
                completed++;
                if (signal == Signal.WIN) wins++;
                else losses++;
                // A result is counted once, but does not open the next slot.
                // A fresh balance response completes this settlement first.
                await = Await.SETTLEMENT_READY;
                awaitedAt = now;
                transition(State.SETTLING, "Личный результат подтверждён; требуется проверка баланса", now);
                return true;
            }
        }
        throw new AssertionError("Unhandled signal: " + signal);
    }

    public synchronized void beginBalanceVerification(long now) {
        if (!observeTime(now)) throw new IllegalStateException(reason);
        expirePending(now);
        if (await == Await.INITIAL_BALANCE || await == Await.SETTLEMENT_BALANCE) return;
        if (await != Await.SETTLEMENT_READY) {
            throw new IllegalStateException("Проверка завершения допустима только после подтверждённого результата");
        }
        await = Await.SETTLEMENT_BALANCE;
        awaitedAt = now;
        transition(State.VERIFYING, "Проверка баланса после завершённой ставки", now);
    }

    /** A correlated, freshly requested personal balance, not an arbitrary /bal line. */
    public synchronized boolean balanceVerified(long now) {
        if (!observeTime(now)) return false;
        expirePending(now);
        if (await != Await.INITIAL_BALANCE && await != Await.SETTLEMENT_BALANCE) return false;
        if (await == Await.SETTLEMENT_BALANCE) unresolved = false;
        await = Await.NONE;
        if (runAllowed && (completed >= target || submitted >= target)) {
            stop("Запрошенное число последовательных игр выполнено");
        } else if (runAllowed) {
            transition(State.IDLE, "Баланс подтверждён; следующая ставка разрешена", now);
        } else {
            transition(haltedState, haltedReason, now);
        }
        return true;
    }

    /** An explicit user action; never called as an automatic retry. */
    public synchronized void beginCancelVerification(long now) {
        if (!observeTime(now)) throw new IllegalStateException(reason);
        expirePending(now);
        if (await == Await.CANCEL) {
            throw new IllegalStateException("Проверка отмены уже ожидает ответа сервера");
        }
        runAllowed = false;
        haltedState = State.PAUSED;
        haltedReason = "Проверка текущей ставки; автоматическое продолжение запрещено";
        // Even a manual check with no remembered stake is uncertain until its
        // reply arrives. A timeout must not turn that uncertainty into IDLE.
        unresolved = true;
        await = Await.CANCEL;
        awaitedAt = now;
        transition(State.VERIFYING, "Ожидание ответа сервера на однократную /cf cancel", now);
    }

    public synchronized boolean awaitingCancel() { return await == Await.CANCEL; }
    public synchronized boolean awaitingSettlement() {
        return await == Await.SETTLEMENT_READY || await == Await.SETTLEMENT_BALANCE;
    }
    public synchronized boolean awaitingInitialBalance() { return await == Await.INITIAL_BALANCE; }

    public synchronized void pause(String why) {
        halt(State.PAUSED, textOr(why, "Сессия приостановлена; требуется новый запуск"));
        if (await == Await.INITIAL_BALANCE) await = Await.NONE;
    }

    public synchronized void stop(String why) {
        // This flag is cleared synchronously, before any late response can be
        // processed. A late ACK or result may settle a stake but never restart.
        halt(State.STOPPED, textOr(why, "Сессия остановлена"));
        if (await == Await.INITIAL_BALANCE) await = Await.NONE;
    }

    public synchronized void failSend(String why) {
        halt(State.ERROR, "Ошибка отправки; автоматический повтор запрещён: " + textOr(why, "неизвестная ошибка"));
        if (!unresolved) await = Await.NONE;
    }

    /** Kept for binary/source compatibility; it can no longer erase an unknown stake. */
    public synchronized void clearUnresolved() {
        if (unresolved) throw new IllegalStateException("Нужен подтверждённый ответ сервера на /cf cancel");
        if (runAllowed || await != Await.NONE) throw new IllegalStateException("Сначала завершите текущий запрос");
        stop("Незавершённых ставок нет; сессия остановлена");
    }

    /** Restore conservative recovery state; persisted guesses never authorize play. */
    public synchronized void markUnresolved() {
        unresolved = true;
        await = Await.RECOVERY;
        pause("Сохранена незавершённая ставка; требуется подтверждение сервера через /cf cancel");
    }

    public synchronized Snapshot snapshot() {
        Phase phase = switch (await) {
            case CREATE -> Phase.CREATING;
            case RESULT -> Phase.WAITING_RESULT;
            case NONE, INITIAL_BALANCE -> unresolved ? Phase.RECOVERY_REQUIRED : Phase.IDLE;
            default -> Phase.RECOVERY_REQUIRED;
        };
        return new Snapshot(runAllowed ? Mode.RUNNING : Mode.STOPPED, phase, reason,
                target, submitted, completed, wins, losses, unresolved, stake, state, runAllowed);
    }

    public synchronized List<Transition> drainTransitions() {
        List<Transition> result = List.copyOf(transitions);
        transitions.clear();
        return result;
    }

    private void halt(State terminal, String why) {
        runAllowed = false;
        haltedState = terminal;
        haltedReason = why;
        transition(terminal, why, hasObservedTime ? lastObservedAt : 0L);
    }

    private boolean observeTime(long now) {
        if (hasObservedTime && now < lastObservedAt) {
            pause("Время изменилось назад; автоматическое продолжение запрещено");
            return false;
        }
        hasObservedTime = true;
        lastObservedAt = now;
        return true;
    }

    private void expirePending(long now) {
        long timeout = switch (await) {
            case CREATE -> CREATE_TIMEOUT_MILLIS;
            case RESULT -> RESULT_TIMEOUT_MILLIS;
            case SETTLEMENT_READY, INITIAL_BALANCE, SETTLEMENT_BALANCE, CANCEL -> VERIFY_TIMEOUT_MILLIS;
            default -> 0L;
        };
        if (timeout == 0 || !elapsed(now, awaitedAt, timeout)) return;
        String message = switch (await) {
            case CREATE -> "Тайм-аут подтверждения создания; повтор запрещён, проверьте ставку";
            case RESULT -> "Тайм-аут результата; повтор запрещён, проверьте ставку";
            case CANCEL -> "Тайм-аут проверки отмены; состояние ставки неизвестно";
            case INITIAL_BALANCE -> "Тайм-аут начального баланса; ставки не отправлялись";
            default -> "Тайм-аут проверки баланса после результата; новая ставка запрещена";
        };
        // Once a request expires its attribution window closes. An old reply
        // cannot silently clear a marker or resume a later run.
        await = unresolved ? Await.RECOVERY : Await.NONE;
        pause(message);
    }

    private void transition(State next, String why, long now) {
        if (state != next || !reason.equals(why)) transitions.add(new Transition(state, next, why, now));
        state = next;
        reason = why;
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean elapsed(long now, long start, long duration) {
        return now >= start && start <= Long.MAX_VALUE - duration && now >= start + duration;
    }

    public record Transition(State from, State to, String reason, long atMillis) { }

    public record Snapshot(Mode mode, Phase phase, String reason, int target, int submitted,
                           int completed, int wins, int losses, boolean unresolved, long stake,
                           State state, boolean runAllowed) {
        /** The 0.2.1 constructor remains available to downstream diagnostic code. */
        public Snapshot(Mode mode, Phase phase, String reason, int target, int submitted,
                        int completed, int wins, int losses, boolean unresolved, long stake) {
            this(mode, phase, reason, target, submitted, completed, wins, losses, unresolved, stake,
                    mode == Mode.RUNNING ? State.IDLE : State.STOPPED, mode == Mode.RUNNING);
        }
    }
}
