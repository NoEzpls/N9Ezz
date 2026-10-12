package dev.n9ezz.cf;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Exact, user-configured server templates; candidate text is never a confirmed result. */
public final class CfParser {
    public static final int MAX_MESSAGE_LENGTH = 2048;
    private static final Pattern COLOR = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern SPACES = Pattern.compile("[\\p{Zs}\\t]+");
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?iu)(?:coin[ -]?flip|конфлип|коинфлип|ставк|вы +выиграли|вы +проиграли|недостаточно +средств|(?:^|[^\\p{L}\\p{N}_])/?cf(?:$|[^\\p{L}\\p{N}_]))");
    private static final Map<CfSession.Signal, String> KEYS = Map.of(
            CfSession.Signal.CREATED, "created.regex",
            CfSession.Signal.INSUFFICIENT, "insufficient.regex",
            CfSession.Signal.LIMIT, "limit.regex",
            CfSession.Signal.WIN, "win.regex",
            CfSession.Signal.LOSS, "loss.regex");

    private final EnumMap<CfSession.Signal, Pattern> patterns = new EnumMap<>(CfSession.Signal.class);
    private final boolean configured;

    public record Parsed(CfSession.Signal signal, String normalized, String reportedAmount,
                         String opponent, boolean candidate) {}

    public CfParser(Properties properties) {
        if (properties == null) throw new IllegalArgumentException("properties must not be null");
        String enabled = properties.getProperty("enabled", "false").trim().toLowerCase(Locale.ROOT);
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalArgumentException("enabled must be true or false");
        }
        configured = enabled.equals("true");
        if (!configured) return;
        for (CfSession.Signal signal : CfSession.Signal.values()) {
            String key = KEYS.get(signal);
            String regex = properties.getProperty(key, "").trim();
            if (regex.length() < 3 || !regex.startsWith("^") || !regex.endsWith("$")) {
                throw new IllegalArgumentException(key + " requires a nonempty anchored ^...$ server template");
            }
            try {
                patterns.put(signal, Pattern.compile(regex));
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException(key + ": " + ex.getDescription(), ex);
            }
        }
    }

    public boolean configured() {
        return configured;
    }

    /** Blank templates deliberately cannot enable automation from approximate example messages. */
    public static Properties defaultProperties() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "false");
        for (String key : KEYS.values()) properties.setProperty(key, "");
        return properties;
    }

    public Parsed parse(String message) {
        // Limit work before running user-supplied regular expressions on remote text.
        if (message == null || message.length() > MAX_MESSAGE_LENGTH) {
            return new Parsed(null, "", null, null, false);
        }
        String normalized = SPACES.matcher(COLOR.matcher(message).replaceAll("")).replaceAll(" ").strip();
        boolean candidate = CANDIDATE.matcher(normalized).find();
        if (!configured) return new Parsed(null, normalized, null, null, candidate);

        CfSession.Signal result = null;
        String amount = null;
        String opponent = null;
        for (Map.Entry<CfSession.Signal, Pattern> entry : patterns.entrySet()) {
            Matcher matcher = entry.getValue().matcher(normalized);
            // matches() is essential: prefixes from player chat cannot pass an otherwise exact template.
            if (!matcher.matches()) continue;
            if (result != null) {
                // Overlapping templates make attribution ambiguous, so never advance the session.
                return new Parsed(null, normalized, null, null, true);
            }
            result = entry.getKey();
            amount = group(matcher, "amount");
            opponent = group(matcher, "opponent");
        }
        // An amount is the server's literal report, not inferred profit, payout, or fee.
        return new Parsed(result, normalized, amount, opponent, candidate || result != null);
    }

    private static String group(Matcher matcher, String name) {
        try {
            String value = matcher.group(name);
            return value == null || value.isBlank() ? null : value.strip();
        } catch (IllegalArgumentException ex) {
            return null; // Named fields are optional; many server acknowledgements contain neither.
        }
    }
}
