package dev.n9ezz.cf;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Parses only a configured, complete server balance reply, never an inferred game payout. */
public final class CfBalance {
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000000");
    private static final int MAX_MESSAGE_LENGTH = 2048;
    private static final Pattern COLOR = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern SPACES = Pattern.compile("[\\p{Zs}\\t]+");
    private static final Pattern EDGES = Pattern.compile("\\A +| +\\z");
    private static final Pattern COMMAND = Pattern.compile("[A-Za-z][A-Za-z0-9_:.-]*(?: [A-Za-z0-9_:.-]+)*");
    private final String command;
    private final Pattern template;
    private final Pattern number;
    private final String grouping;
    private final String decimal;

    public CfBalance(Properties properties) {
        if (properties == null) throw new IllegalArgumentException("properties must not be null");
        String providedCommand = properties.getProperty("balance.command", "bal");
        if (providedCommand.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("balance.command must not contain control characters");
        }
        providedCommand = providedCommand.strip();
        if (!providedCommand.isEmpty()) {
            String first = providedCommand.split(" ", 2)[0].toLowerCase(Locale.ROOT);
            if (providedCommand.length() > 128 || !COMMAND.matcher(providedCommand).matches()
                    || first.equals("cfbot") || first.endsWith(":cfbot")) {
                throw new IllegalArgumentException("balance.command must be a server command without /, chat text, or cfbot");
            }
        }
        command = providedCommand.isEmpty() ? null : providedCommand;

        grouping = separator(properties.getProperty("balance.grouping", "comma"), true);
        decimal = separator(properties.getProperty("balance.decimal", "dot"), false);
        if (!grouping.isEmpty() && grouping.equals(decimal)) {
            throw new IllegalArgumentException("balance.grouping and balance.decimal must differ");
        }
        // A grouped integer must have exactly three digits in every group after the first.
        // Plain digits are also valid, but abbreviated values such as 1.5m are never balances.
        String integer = grouping.isEmpty() ? "[0-9]+"
                : "(?:[0-9]+|[0-9]{1,3}(?:" + Pattern.quote(grouping) + "[0-9]{3})+)";
        String fraction = decimal.isEmpty() ? "" : "(?:" + Pattern.quote(decimal) + "[0-9]{1,2})?";
        number = Pattern.compile(integer + fraction);

        String regex = properties.getProperty("balance.regex", "").strip();
        if (regex.isEmpty()) {
            // /bal is known, but its three-currency reply is not. Collecting an example is safe;
            // enabling automation requires a template whose amount group selects coins only.
            template = null;
        } else {
            if (regex.length() < 3 || regex.length() > MAX_MESSAGE_LENGTH
                    || !regex.startsWith("^") || !regex.endsWith("$")) {
                throw new IllegalArgumentException("balance.regex requires an anchored ^...$ server template");
            }
            try {
                template = Pattern.compile(regex);
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException("balance.regex: " + ex.getDescription(), ex);
            }
            if (!template.namedGroups().containsKey("amount")) {
                throw new IllegalArgumentException("balance.regex must name the coins amount as (?<amount>...)");
            }
        }
    }

    public static Properties defaultProperties() {
        Properties properties = new Properties();
        properties.setProperty("balance.command", "bal");
        properties.setProperty("balance.regex", "");
        properties.setProperty("balance.grouping", "comma");
        properties.setProperty("balance.decimal", "dot");
        return properties;
    }

    public boolean configured() {
        return command != null && template != null;
    }

    /** Available before calibration so that the user can record an actual /bal reply. */
    public String queryCommand() {
        return command;
    }

    public BigDecimal parse(String message) {
        if (!configured() || message == null || message.length() > MAX_MESSAGE_LENGTH) return null;
        // One system packet may contain all three currencies on separate lines. Preserve those
        // lines for an explicit whole-reply template instead of searching individual fragments.
        String lineNormalized = message.replace("\r\n", "\n");
        if (lineNormalized.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\t' && cp != '\n')) return null;
        String normalized = EDGES.matcher(SPACES.matcher(COLOR.matcher(lineNormalized).replaceAll("")).replaceAll(" "))
                .replaceAll("");
        Matcher matcher = template.matcher(normalized);
        // Full matching blocks player-chat prefixes and prevents reading a different currency
        // from a loosely matched fragment of a server's multi-currency display.
        if (!matcher.matches()) return null;
        String amount = matcher.group("amount");
        if (amount == null || amount.length() > 64 || !number.matcher(amount).matches()) return null;
        String canonical = grouping.isEmpty() ? amount : amount.replace(grouping, "");
        if (!decimal.isEmpty()) canonical = canonical.replace(decimal, ".");
        try {
            BigDecimal value = new BigDecimal(canonical);
            return value.compareTo(MAX_AMOUNT) <= 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String separator(String value, boolean grouping) {
        if (value.equals(" ") && grouping) return " ";
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "comma", "," -> ",";
            case "dot", "." -> ".";
            case "none", "" -> "";
            case "space" -> {
                if (!grouping) throw new IllegalArgumentException("balance.decimal cannot be space");
                yield " ";
            }
            default -> throw new IllegalArgumentException("Unknown balance " + (grouping ? "grouping" : "decimal") + " separator");
        };
    }
}
