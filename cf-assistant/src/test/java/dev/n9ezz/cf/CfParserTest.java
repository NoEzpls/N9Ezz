package dev.n9ezz.cf;

import java.util.Properties;

public final class CfParserTest {
    private static int checks;

    public static void main(String[] args) {
        CfParser disabled = new CfParser(CfParser.defaultProperties());
        check(!disabled.configured(), "default parser is unconfigured");
        check(disabled.parse("Вы успешно создали ставку").signal() == null, "approximate example cannot acknowledge");
        check(disabled.parse("Вы успешно создали ставку").candidate(), "candidate retained for calibration");
        check(disabled.parse("Недостаточно средств").candidate(), "negative acknowledgement retained for calibration");
        check(!disabled.parse("[CHAT] обычный текст").candidate(), "ordinary chat ignored");
        check(disabled.parse("Открыть /cf").candidate(), "command token recognized");
        check(!disabled.parse("abcfoo").candidate(), "cf inside a word is not a candidate");
        check(disabled.parse("COINFLIP").candidate(), "English candidate case insensitive");
        check(disabled.parse("ВЫ ВЫИГРАЛИ").candidate(), "Russian candidate case insensitive");

        CfParser parser = new CfParser(config());
        check(parser.configured(), "complete explicit configuration enabled");
        CfParser.Parsed win = parser.parse("§a[CF]§r Победа: 1 500 000 у Alice");
        check(win.signal() == CfSession.Signal.WIN, "colored exact result accepted");
        check(win.normalized().equals("[CF] Победа: 1 500 000 у Alice"), "colors and narrow spaces normalized");
        check(win.reportedAmount().equals("1 500 000"), "reported amount preserved, no arithmetic");
        check(win.opponent().equals("Alice"), "optional opponent captured");
        check(win.candidate(), "exact matches retained");
        check(parser.parse("  [CF]\tПобеда: 50 000 у Bob  ").signal() == CfSession.Signal.WIN, "NBSP and repeated whitespace normalized");
        check(parser.parse("[CF] Поражение: 50000 у Carol").signal() == CfSession.Signal.LOSS, "loss recognized");
        check(parser.parse("[CF] Создана ставка").signal() == CfSession.Signal.CREATED, "creation acknowledged");
        check(parser.parse("[CF] Недостаточно средств").signal() == CfSession.Signal.INSUFFICIENT, "insufficient recognized");
        check(parser.parse("[CF] Уже две ставки").signal() == CfSession.Signal.LIMIT, "limit recognized");
        check(parser.parse("[CF] Создана ставка").opponent() == null, "missing named group is null");
        check(parser.parse("[CF] Создана ставка").reportedAmount() == null, "missing amount is null");
        check(parser.parse("<Alice> [CF] Победа: 50000 у Bob").signal() == null, "player-chat prefix rejected");
        check(parser.parse("[CF] Победа: 50000 у Bob!").signal() == null, "unexpected suffix rejected");
        check(parser.parse("[CF] Победа: 50000 у Bob\nextra").signal() == null, "multiline addition rejected");
        check(parser.parse("[CF] Победа: 9" + "9".repeat(3000) + " у Bob").signal() == null, "oversize message rejected before regex");
        check(!parser.parse("/cf " + "a".repeat(2048)).candidate(), "oversize candidate excluded");
        check(parser.parse(null).signal() == null, "null safely ignored");

        Properties overlap = config();
        overlap.setProperty("loss.regex", overlap.getProperty("win.regex"));
        CfParser.Parsed ambiguous = new CfParser(overlap).parse("[CF] Победа: 50000 у Bob");
        check(ambiguous.signal() == null && ambiguous.candidate(), "overlap records but does not confirm result");
        check(ambiguous.reportedAmount() == null && ambiguous.opponent() == null, "ambiguous details not attributed");

        Properties noGroups = config();
        noGroups.setProperty("win.regex", "^WIN$");
        CfParser.Parsed plain = new CfParser(noGroups).parse("WIN");
        check(plain.signal() == CfSession.Signal.WIN && plain.reportedAmount() == null && plain.opponent() == null,
                "exact templates may omit named fields");

        Properties hugeAmount = config();
        String literal = "9".repeat(200);
        check(new CfParser(hugeAmount).parse("[CF] Победа: " + literal + " у Bob").reportedAmount().equals(literal),
                "large literal amount never overflows because it is not parsed numerically");

        invalid("created.regex", "", "empty template");
        invalid("created.regex", ".*", "unanchored template");
        invalid("created.regex", "^[$", "invalid regex");
        invalid("enabled", "tru", "ambiguous enabled value");
        Properties partial = config();
        partial.remove("limit.regex");
        rejects(() -> new CfParser(partial), "all five templates required");
        Properties disabledBroken = config();
        disabledBroken.setProperty("enabled", "false");
        disabledBroken.setProperty("win.regex", "[");
        check(!new CfParser(disabledBroken).configured(), "disabled configuration does not use incomplete templates");
        Properties fresh = CfParser.defaultProperties();
        fresh.setProperty("enabled", "true");
        check(CfParser.defaultProperties().getProperty("enabled").equals("false"), "default properties are independent copies");
        System.out.println("CfParserTest: " + checks + " checks passed");
    }

    private static Properties config() {
        Properties p = CfParser.defaultProperties();
        p.setProperty("enabled", "true");
        p.setProperty("created.regex", "^\\[CF\\] Создана ставка$");
        p.setProperty("insufficient.regex", "^\\[CF\\] Недостаточно средств$");
        p.setProperty("limit.regex", "^\\[CF\\] Уже две ставки$");
        p.setProperty("win.regex", "^\\[CF\\] Победа: (?<amount>[0-9 ]+) у (?<opponent>[A-Za-z0-9_]+)$");
        p.setProperty("loss.regex", "^\\[CF\\] Поражение: (?<amount>[0-9 ]+) у (?<opponent>[A-Za-z0-9_]+)$");
        return p;
    }

    private static void invalid(String key, String value, String description) {
        Properties p = config();
        p.setProperty(key, value);
        rejects(() -> new CfParser(p), description);
    }

    private static void rejects(Runnable operation, String description) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError(description);
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
    }
}
