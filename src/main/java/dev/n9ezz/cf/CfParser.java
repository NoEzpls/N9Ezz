package dev.n9ezz.cf;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Parses the personal RaidMine CF replies observed in the supplied 0.2.1 log. */
public final class CfParser {
    public static final int MAX_MESSAGE_LENGTH = 2048;
    private static final Pattern COLOR = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern SPACES = Pattern.compile("[\\p{Zs}\\t]+");
    private static final Pattern CANDIDATE = Pattern.compile("(?iu)(?:coin[ -]?flip|конфлип|коинфлип|ставк|вы +выиграли|вы +проиграли|недостаточно +средств|(?:^|[^\\p{L}\\p{N}_])/?cf(?:$|[^\\p{L}\\p{N}_]))");
    private static final String MONEY = "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?";
    private static final Pattern AMOUNT = Pattern.compile(MONEY);
    private static final Pattern NICK = Pattern.compile("\\.?[A-Za-z0-9_]{1,32}");
    private static final String CANCEL_REQUIRED = "^┃ Сначала отмените текущую ставку с помощью /cf cancel[.]?$";
    private static final Pattern CANCEL_REPLY = Pattern.compile(CANCEL_REQUIRED);
    private static final Map<CfSession.Signal, String> KEYS = Map.of(
            CfSession.Signal.CREATED, "created.regex",
            CfSession.Signal.INSUFFICIENT, "insufficient.regex",
            CfSession.Signal.LIMIT, "limit.regex",
            CfSession.Signal.CANCEL_REQUIRED, "cancel_required.regex",
            CfSession.Signal.NO_ACTIVE, "no_active.regex",
            CfSession.Signal.CANCELLED, "cancelled.regex",
            CfSession.Signal.WIN, "win.regex",
            CfSession.Signal.LOSS, "loss.regex");
    private final EnumMap<CfSession.Signal, Pattern> patterns = new EnumMap<>(CfSession.Signal.class);
    private final boolean configured;

    public CfParser(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String enabled = properties.getProperty("enabled", "true").trim().toLowerCase(Locale.ROOT);
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalArgumentException("enabled must be true or false");
        }
        configured = enabled.equals("true");
        if (!configured) return;
        Properties defaults = defaultProperties();
        for (Map.Entry<CfSession.Signal, String> entry : KEYS.entrySet()) {
            String key = entry.getValue();
            String expression = properties.getProperty(key, defaults.getProperty(key)).strip();
            // A successful cancellation response was not present in the uploaded evidence.
            // Do not invent a success template: unknown replies must leave the bot paused.
            if (expression.isEmpty() && (entry.getKey() == CfSession.Signal.CANCELLED
                    || entry.getKey() == CfSession.Signal.LIMIT)) continue;
            if (expression.length() < 3 || expression.length() > MAX_MESSAGE_LENGTH
                    || !expression.startsWith("^") || !expression.endsWith("$")) {
                throw new IllegalArgumentException(key + " requires an anchored ^...$ server template");
            }
            try {
                patterns.put(entry.getKey(), Pattern.compile(expression));
            } catch (PatternSyntaxException failure) {
                throw new IllegalArgumentException(key + ": " + failure.getDescription(), failure);
            }
        }
    }

    public boolean configured() { return configured; }

    public static Properties defaultProperties() {
        Properties result = new Properties();
        result.setProperty("enabled", "true");
        result.setProperty("created.regex", "^┃ Вы успешно создали ставку[.]$");
        result.setProperty("insufficient.regex", "^┃ У [Вв]ас недостаточно средств для создания ставки[.]$");
        result.setProperty("limit.regex", "");
        result.setProperty("cancel_required.regex", CANCEL_REQUIRED);
        result.setProperty("no_active.regex", "^┃ У [Вв]ас нет активной ставки[.]$");
        result.setProperty("cancelled.regex", "");
        String currency = "(?:[\\p{Co}]{1,8}|[$])";
        result.setProperty("win.regex", "^┃ Победа! Вы выиграли (?<amount>" + MONEY
                + ") " + currency + " \\(-15%\\) у (?<opponent>.+)$");
        result.setProperty("loss.regex", "^┃ Поражение! Вы проиграли (?<amount>" + MONEY
                + ") " + currency + " игроку (?<opponent>.+)$");
        return result;
    }

    public Parsed parse(String input) {
        String normalized = normalize(input);
        boolean candidate = CANDIDATE.matcher(normalized).find();
        // The event bus also carries public chat as system text. A regex override must
        // not turn a quoted result after a player-chat separator into our own result.
        if (!configured || !isServerMessage(normalized)) {
            return new Parsed(null, normalized, null, null, candidate);
        }
        CfSession.Signal signal = null;
        String amount = null;
        String opponent = null;
        for (Map.Entry<CfSession.Signal, Pattern> entry : patterns.entrySet()) {
            Matcher matcher = entry.getValue().matcher(normalized);
            if (!matcher.matches()) continue;
            CfSession.Signal matched = entry.getKey();
            // 0.2.1 stored the cancel request under limit.regex. Preserve that setting
            // while exposing the distinct protocol event needed for reconciliation.
            if (matched == CfSession.Signal.LIMIT && CANCEL_REPLY.matcher(normalized).matches()) {
                matched = CfSession.Signal.CANCEL_REQUIRED;
            }
            String reported = group(matcher, "amount");
            String rankedOpponent = group(matcher, "opponent");
            String nick = rankedOpponent == null ? null : opponentNick(rankedOpponent);
            if (matched == CfSession.Signal.WIN || matched == CfSession.Signal.LOSS) {
                String personalPrefix = matched == CfSession.Signal.WIN
                        ? "┃ Победа! Вы выиграли " : "┃ Поражение! Вы проиграли ";
                if (!normalized.startsWith(personalPrefix) || !validAmount(reported) || nick == null) {
                    return new Parsed(null, normalized, null, null, true);
                }
            } else if (reported != null && !validAmount(reported)) {
                return new Parsed(null, normalized, null, null, true);
            }
            if (signal != null && (signal != matched || !Objects.equals(amount, reported)
                    || !Objects.equals(opponent, nick))) {
                return new Parsed(null, normalized, null, null, true);
            }
            signal = matched;
            amount = reported;
            opponent = nick;
        }
        return new Parsed(signal, normalized, amount, opponent, candidate || signal != null);
    }

    public static String normalize(String input) {
        if (input == null || input.length() > MAX_MESSAGE_LENGTH) return "";
        // Reject embedded newlines/control/bidi characters instead of joining two
        // messages into a trusted reply. Private-use currency glyphs remain intact.
        if (input.codePoints().anyMatch(c -> (Character.isISOControl(c) && c != '\t')
                || Character.getType(c) == Character.FORMAT)) return "";
        String result = SPACES.matcher(COLOR.matcher(input).replaceAll("")).replaceAll(" ").strip();
        if (result.startsWith("┃")) result = "┃ " + result.substring(1).stripLeading();
        return result;
    }

    public static boolean isPlayerChat(String input) {
        String value = normalize(input);
        return value.indexOf('»') >= 0 || value.indexOf('›') >= 0
                || value.matches("^(?:┃ )?<[^>]+>.*");
    }

    public static boolean isServerMessage(String input) {
        String value = normalize(input);
        return value.startsWith("┃ ") && !isPlayerChat(value);
    }

    private static boolean validAmount(String value) {
        if (value == null || value.length() > 64 || !AMOUNT.matcher(value).matches()) return false;
        try {
            BigDecimal amount = new BigDecimal(value.replace(",", ""));
            return amount.signum() > 0 && amount.compareTo(CfBalance.MAX_AMOUNT) <= 0;
        } catch (NumberFormatException ignored) { return false; }
    }

    private static String opponentNick(String ranked) {
        if (ranked.length() > 160 || ranked.indexOf('»') >= 0 || ranked.indexOf(':') >= 0
                || ranked.indexOf('<') >= 0 || ranked.indexOf('>') >= 0 || ranked.indexOf('┃') >= 0) return null;
        String nick = ranked.substring(ranked.lastIndexOf(' ') + 1);
        return NICK.matcher(nick).matches() ? nick : null;
    }

    private static String group(Matcher matcher, String name) {
        try {
            String value = matcher.group(name);
            return value == null || value.isBlank() ? null : value.strip();
        } catch (IllegalArgumentException ignored) { return null; }
    }

    public record Parsed(CfSession.Signal signal, String normalized, String reportedAmount,
                         String opponent, boolean candidate) {
        public boolean serverMessage() { return CfParser.isServerMessage(normalized); }
        public boolean playerChat() { return CfParser.isPlayerChat(normalized); }
    }
}
