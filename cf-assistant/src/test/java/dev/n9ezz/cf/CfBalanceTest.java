package dev.n9ezz.cf;

import java.math.BigDecimal;
import java.util.Properties;

public final class CfBalanceTest {
    private static int checks;

    public static void main(String[] args) {
        CfBalance defaults = new CfBalance(CfBalance.defaultProperties());
        check(!defaults.configured(), "known /bal command cannot enable uncalibrated parsing");
        check(defaults.queryCommand().equals("bal"), "known balance command can collect an exact reply");
        check(defaults.parse("Монеты: 1,234.56") == null, "no guessed template");
        Properties empty = configuration();
        empty.setProperty("balance.command", "");
        CfBalance noCommand = new CfBalance(empty);
        check(!noCommand.configured() && noCommand.queryCommand() == null, "missing command disables parsing");
        check(noCommand.parse("[Balance] Coins: 100; Raids: 200; Koins: 300") == null, "partial configuration never parses");

        CfBalance parser = new CfBalance(configuration());
        check(parser.configured(), "explicit full template configured");
        eq(parser.parse(reply("1,234,567.89")), "1234567.89", "grouped balance is exact");
        eq(parser.parse(reply("1234567")), "1234567", "ungrouped whole balance");
        eq(parser.parse(reply("123.4")), "123.4", "one fractional digit");
        eq(parser.parse(reply("0")), "0", "zero balance");
        eq(parser.parse(reply("1000000000000000")), "1000000000000000", "maximum exact balance");
        eq(parser.parse(reply("999,999,999,999,999.99")), "999999999999999.99", "maximum fractional balance");
        eq(parser.parse("§a[Balance]§r Coins: 25.50; Raids: 999,999; Koins: 1,000,000"), "25.50", "only named coin group selected among three currencies");
        check(parser.parse("<Player> " + reply("25")) == null, "player-chat prefix rejected");
        check(parser.parse(reply("25") + " extra") == null, "unexpected suffix rejected");
        check(parser.parse(reply("25") + "\n") == null, "unexpected newline is not stripped from exact template");
        check(parser.parse(reply("25") + "\r") == null, "carriage return rejected");
        check(parser.parse(reply("25") + "\u0000") == null, "NUL rejected");
        check(parser.parse(null) == null, "null ignored");
        check(parser.parse("x".repeat(2049)) == null, "oversize ignored before regex");

        Properties multiline = configuration();
        multiline.setProperty("balance.regex", "^Монеты: (?<amount>[0-9,.]+)\\nРейды: [0-9,.]+\\nКоины: [0-9,.]+$");
        CfBalance threeLines = new CfBalance(multiline);
        eq(threeLines.parse("§aМонеты: 12,345.67\nРейды: 999,999\nКоины: 1,000,000"), "12345.67", "exact multiline template selects coins only");
        eq(threeLines.parse("Монеты: 12,345.67\r\nРейды: 999,999\r\nКоины: 1,000,000"), "12345.67", "CRLF normalized to LF");
        check(threeLines.parse("Монеты: 12,345.67\rРейды: 999,999\nКоины: 1,000,000") == null, "unpaired CR rejected");
        check(threeLines.parse("header\nМонеты: 12,345.67\nРейды: 999,999\nКоины: 1,000,000") == null, "multiline full-match rejects prefix");
        check(threeLines.parse("Рейды: 999,999\nМонеты: 12,345.67\nКоины: 1,000,000") == null, "multiline order must match template");
        check(threeLines.parse("Монеты: 12,345.67\nРейды: 999,999\nКоины: 1,000,000\u001b") == null, "other control characters rejected");

        for (String bad : new String[]{"1,23", "1,234,56", "1234,567", "1,,234", "1.234,56", "1,234.567",
                ".50", "1.", "-50", "+50", "1e6", "1.5m", "50k", "NaN", "Infinity", "1 234", "1_234",
                "1000000000000000.01", "1000000000000001", "99999999999999999999999999999999999999999999999999999999999999999"}) {
            check(parser.parse(reply(bad)) == null, "invalid amount rejected: " + bad);
        }

        Properties european = configuration();
        european.setProperty("balance.grouping", "dot");
        european.setProperty("balance.decimal", "comma");
        CfBalance euro = new CfBalance(european);
        eq(euro.parse(reply("1.234.567,89")), "1234567.89", "explicit European separators");
        check(euro.parse(reply("1,234,567.89")) == null, "separators never auto-detected");
        Properties space = configuration();
        space.setProperty("balance.grouping", "space");
        CfBalance spaces = new CfBalance(space);
        eq(spaces.parse(reply("1 234 567.89")), "1234567.89", "NBSP and narrow NBSP grouped amounts");
        eq(spaces.parse(reply("1\t234.89")), "1234.89", "tab normalization matches parser normalization");
        eq(spaces.parse("  " + reply("1 234") + "  "), "1234", "outer whitespace normalization");
        check(spaces.parse(reply("12 34")) == null, "normalized groups still require three digits");

        Properties integerOnly = configuration();
        integerOnly.setProperty("balance.grouping", "none");
        integerOnly.setProperty("balance.decimal", "none");
        CfBalance whole = new CfBalance(integerOnly);
        eq(whole.parse(reply("1234")), "1234", "both separators disabled");
        check(whole.parse(reply("1234.00")) == null, "fraction disabled explicitly");
        check(whole.parse(reply("1,234")) == null, "grouping disabled explicitly");

        Properties optional = configuration();
        optional.setProperty("balance.regex", "^(?<amount>[0-9]+)?$");
        check(new CfBalance(optional).parse("") == null, "missing optional amount is not zero");
        invalid("balance.regex", ".*", "unanchored template");
        invalid("balance.regex", "^[0-9]+$", "missing named amount group");
        invalid("balance.regex", "^\\Q(?<amount>\\E$", "quoted fake named group");
        invalid("balance.regex", "^(?<amount>[$", "invalid regex");
        invalid("balance.grouping", "comma", "equal separators", "balance.decimal", "comma");
        invalid("balance.grouping", "apostrophe", "unknown grouping");
        invalid("balance.decimal", "space", "space decimal forbidden");
        for (String command : new String[]{"/bal", "cfbot", "CfBot start", "n9ezz:cfbot start", "bal\ncf create 50000",
                "bal\r", "bal\t", "bal;cf create 50000", "Hello, world!", "bal " + "a".repeat(128)}) {
            invalid("balance.command", command, "invalid command rejected");
        }
        Properties command = configuration();
        command.setProperty("balance.command", "essentials:balance Player_1");
        check(new CfBalance(command).queryCommand().equals("essentials:balance Player_1"), "explicit command arguments preserved");
        Properties fresh = CfBalance.defaultProperties();
        fresh.setProperty("balance.command", "changed");
        check(CfBalance.defaultProperties().getProperty("balance.command").equals("bal"), "fresh defaults independent");
        rejects(() -> new CfBalance(null), "null config");
        System.out.println("CfBalanceTest: " + checks + " checks passed");
    }

    // Synthetic fixtures exercise the parser; they do not assert any RaidMine reply format.
    private static Properties configuration() {
        Properties p = CfBalance.defaultProperties();
        p.setProperty("balance.regex", "^\\[Balance\\] Coins: (?<amount>.+); Raids: [0-9,]+; Koins: [0-9,]+$");
        return p;
    }

    private static String reply(String amount) {
        return "[Balance] Coins: " + amount + "; Raids: 200; Koins: 300";
    }

    private static void invalid(String key, String value, String description, String... extra) {
        Properties p = configuration();
        p.setProperty(key, value);
        for (int i = 0; i < extra.length; i += 2) p.setProperty(extra[i], extra[i + 1]);
        rejects(() -> new CfBalance(p), description);
    }

    private static void eq(BigDecimal actual, String expected, String description) {
        check(actual != null && actual.compareTo(new BigDecimal(expected)) == 0, description);
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
