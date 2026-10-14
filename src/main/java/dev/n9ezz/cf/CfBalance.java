package dev.n9ezz.cf;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Reads only the coins field of RaidMine's personal three-currency /bal reply. */
public final class CfBalance {
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000000");
    private static final Pattern COMMAND = Pattern.compile("[A-Za-z][A-Za-z0-9_:.-]*(?: [A-Za-z0-9_:.-]+)*");
    private static final String INTEGER = "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)";
    private static final String DEFAULT_TEMPLATE = "^Ваш баланс: (?<amount>" + INTEGER
            + "(?:\\.[0-9]{1,2})?)[$] [|] " + INTEGER + " рейдов [|] " + INTEGER + " коинов$";
    private static final String LEGACY_TEMPLATE = "^Ваш баланс: (?<amount>[0-9]+(?:,[0-9]{3})*)[$] [|] [0-9]+ рейдов [|] [0-9]+ коинов$";
    private final String command;
    private final Pattern template;
    private final Pattern number;
    private final String grouping;
    private final String decimal;

    public CfBalance(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String query = properties.getProperty("balance.command", "bal");
        if (query.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("balance.command must not contain control characters");
        }
        query = query.strip();
        if (!query.isEmpty()) {
            String first = query.split(" ", 2)[0].toLowerCase(Locale.ROOT);
            if (query.length() > 128 || !COMMAND.matcher(query).matches()
                    || first.equals("cfbot") || first.endsWith(":cfbot")) {
                throw new IllegalArgumentException("balance.command must be a server command without /, chat text, or cfbot");
            }
        }
        command = query.isEmpty() ? null : query;
        grouping = separator(properties.getProperty("balance.grouping", "comma"), true);
        decimal = separator(properties.getProperty("balance.decimal", "dot"), false);
        if (!grouping.isEmpty() && grouping.equals(decimal)) {
            throw new IllegalArgumentException("balance.grouping and balance.decimal must differ");
        }
        String integer = grouping.isEmpty() ? "[0-9]+"
                : "(?:[0-9]+|[0-9]{1,3}(?:" + Pattern.quote(grouping) + "[0-9]{3})+)";
        number = Pattern.compile(integer + (decimal.isEmpty() ? "" : "(?:" + Pattern.quote(decimal) + "[0-9]{1,2})?"));
        String expression = properties.getProperty("balance.regex", DEFAULT_TEMPLATE).strip();
        // Migrate only the precise uploaded 0.2.1 template: it unnecessarily rejected
        // grouped secondary currencies and fractional coin balances. Custom rules stay intact.
        if (expression.equals(LEGACY_TEMPLATE) && grouping.equals(",") && decimal.equals(".")) {
            expression = DEFAULT_TEMPLATE;
        }
        if (expression.isEmpty()) {
            template = null;
        } else {
            if (expression.length() < 3 || expression.length() > CfParser.MAX_MESSAGE_LENGTH
                    || !expression.startsWith("^") || !expression.endsWith("$")) {
                throw new IllegalArgumentException("balance.regex requires an anchored ^...$ server template");
            }
            try {
                template = Pattern.compile(expression);
            } catch (PatternSyntaxException failure) {
                throw new IllegalArgumentException("balance.regex: " + failure.getDescription(), failure);
            }
            if (!template.namedGroups().containsKey("amount")) {
                throw new IllegalArgumentException("balance.regex must name the coins amount as (?<amount>...)");
            }
        }
    }

    public static Properties defaultProperties() {
        Properties result = new Properties();
        result.setProperty("balance.command", "bal");
        result.setProperty("balance.regex", DEFAULT_TEMPLATE);
        result.setProperty("balance.grouping", "comma");
        result.setProperty("balance.decimal", "dot");
        return result;
    }

    public boolean configured() { return command != null && template != null; }
    public String queryCommand() { return command; }

    public BigDecimal parse(String input) {
        if (!configured()) return null;
        String normalized = CfParser.normalize(input);
        if (CfParser.isPlayerChat(normalized)) return null;
        String body = normalized.startsWith("┃ ") ? normalized.substring(2) : normalized;
        // This guard is independent of editable regexes: /bal <other-player> or a
        // chat quotation must never become the bot's own confirmed balance.
        if (!body.startsWith("Ваш баланс: ")) return null;
        Matcher match = template.matcher(body);
        if (!match.matches()) {
            // Preserve explicitly prefixed custom templates as well as old unprefixed ones.
            match = template.matcher(normalized);
            if (!match.matches()) return null;
        }
        String raw = match.group("amount");
        if (raw == null || raw.length() > 64 || !number.matcher(raw).matches()) return null;
        String canonical = grouping.isEmpty() ? raw : raw.replace(grouping, "");
        if (!decimal.isEmpty()) canonical = canonical.replace(decimal, ".");
        try {
            BigDecimal amount = new BigDecimal(canonical);
            return amount.signum() >= 0 && amount.compareTo(MAX_AMOUNT) <= 0 ? amount : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static String separator(String input, boolean grouping) {
        if (input.equals(" ") && grouping) return " ";
        return switch (input.strip().toLowerCase(Locale.ROOT)) {
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
