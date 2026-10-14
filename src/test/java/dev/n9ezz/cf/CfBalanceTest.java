package dev.n9ezz.cf;

import java.math.BigDecimal;
import java.util.Properties;

public final class CfBalanceTest {
    private static int checks;

    public static void main(String[] args) {
        CfBalance balance = new CfBalance(CfBalance.defaultProperties());
        check(balance.configured() && balance.queryCommand().equals("bal"), "default personal balance enabled");
        amount(balance, "Ваш баланс: 2,785,000$ | 0 рейдов | 0 коинов", "2785000");
        amount(balance, "┃ Ваш баланс: 2,785,000$ | 99 рейдов | 18 коинов", "2785000");
        amount(balance, "§7┃§r\u00a0Ваш баланс: §e2,785,000.50§r$ | 1,200 рейдов | 99,000 коинов", "2785000.50");
        amount(balance, "Ваш баланс: 0$ | 500 рейдов | 200 коинов", "0");
        amount(balance, "Ваш баланс: 1000000$ | 0 рейдов | 0 коинов", "1000000");
        amount(balance, "Ваш баланс: 1,000,000,000,000,000$ | 0 рейдов | 0 коинов", "1000000000000000");
        for (String message : new String[]{
                "Баланс игрока FixturePlayer: 2,785,000$ | 0 рейдов | 0 коинов",
                "┃ Баланс FixturePlayer: 2,785,000$ | 0 рейдов | 0 коинов",
                "ɢ FixturePlayer » Ваш баланс: 2,785,000$ | 0 рейдов | 0 коинов",
                "┃ FixturePlayer » Ваш баланс: 2,785,000$ | 0 рейдов | 0 коинов",
                "Ваш баланс: 2,785,000$ | 0 рейдов | 0 коинов » сообщение",
                "Ваш баланс: 2,785,000$ | 0 рейдов | 0 коинов\nВаш баланс: 0$ | 0 рейдов | 0 коинов",
                "Ваш баланс: 2,785,000$ | 0 рейдов | 0 коинов\u202e",
                "Ваш баланс: 2,785,000$ | 9,99 рейдов | 0 коинов",
                "Ваш баланс: 2,785,000$ | 0 рейдов | 9,99 коинов"}) {
            check(balance.parse(message) == null, "foreign, malformed, or forged balance rejected");
        }
        for (String money : new String[]{"27,85,000", "2785,000", "-1", "+1", "1e6", "50.123", "NaN", "1,000,000,000,000,001"}) {
            check(balance.parse("Ваш баланс: " + money + "$ | 0 рейдов | 0 коинов") == null, "invalid money rejected: " + money);
        }
        Properties legacy = new Properties();
        legacy.setProperty("balance.regex", "^Ваш баланс: (?<amount>[0-9]+(?:,[0-9]{3})*)[$] [|] [0-9]+ рейдов [|] [0-9]+ коинов$");
        CfBalance migrated = new CfBalance(legacy);
        amount(migrated, "┃ Ваш баланс: 2,785,000.50$ | 1,200 рейдов | 9,000 коинов", "2785000.50");
        Properties prefixed = CfBalance.defaultProperties();
        prefixed.setProperty("balance.regex", "^┃ Ваш баланс: (?<amount>[0-9,]+)[$] [|] [0-9]+ рейдов [|] [0-9]+ коинов$");
        amount(new CfBalance(prefixed), "┃ Ваш баланс: 2,785,000$ | 0 рейдов | 0 коинов", "2785000");
        Properties lax = CfBalance.defaultProperties();
        lax.setProperty("balance.regex", "^.*: (?<amount>[0-9,]+)[$] [|] [0-9]+ рейдов [|] [0-9]+ коинов$");
        check(new CfBalance(lax).parse("Баланс игрока FixturePlayer: 2,785,000$ | 0 рейдов | 0 коинов") == null,
                "custom expression cannot treat others' balance as ours");
        Properties spaced = CfBalance.defaultProperties();
        spaced.setProperty("balance.grouping", "space");
        spaced.setProperty("balance.decimal", "comma");
        spaced.setProperty("balance.regex", "^Ваш баланс: (?<amount>[0-9 ,]+)[$]$");
        amount(new CfBalance(spaced), "Ваш баланс: 2\u00a0785\u00a0000,50$", "2785000.50");
        Properties disabled = CfBalance.defaultProperties();
        disabled.setProperty("balance.regex", "");
        check(!new CfBalance(disabled).configured(), "explicit empty template disables balance query recognition");
        Properties commandDisabled = CfBalance.defaultProperties();
        commandDisabled.setProperty("balance.command", "");
        check(!new CfBalance(commandDisabled).configured(), "explicit empty command disables balance checks");
        check(balance.parse(null) == null && balance.parse("x".repeat(2049)) == null, "input bounded");
        for (String command : new String[]{"/bal", "cfbot", "other:cfbot", "bal\ncf create 50000", "bal; cf create 50000"}) {
            Properties properties = CfBalance.defaultProperties();
            properties.setProperty("balance.command", command);
            rejects(() -> new CfBalance(properties), "invalid query command rejected");
        }
        Properties noGroup = CfBalance.defaultProperties();
        noGroup.setProperty("balance.regex", "^Ваш баланс: [0-9]+$");
        rejects(() -> new CfBalance(noGroup), "named amount required");
        Properties same = CfBalance.defaultProperties();
        same.setProperty("balance.decimal", "comma");
        rejects(() -> new CfBalance(same), "ambiguous numeric separators rejected");
        System.out.println("CfBalanceTest: " + checks + " checks passed");
    }

    private static void amount(CfBalance parser, String input, String expected) {
        BigDecimal actual = parser.parse(input);
        check(actual != null && actual.compareTo(new BigDecimal(expected)) == 0,
                "balance amount expected " + expected + ", actual " + actual);
    }
    private static void check(boolean passed, String reason) {
        checks++;
        if (!passed) throw new AssertionError(reason);
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(reason);
    }
}
