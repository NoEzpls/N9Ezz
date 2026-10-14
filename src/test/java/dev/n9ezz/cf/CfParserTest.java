package dev.n9ezz.cf;

import java.util.Properties;

/** Sanitized fixtures preserve protocol formatting, not the uploaded player names. */
public final class CfParserTest {
    private static int checks;
    private static final String GLYPH = "\uE23A\uF801";

    public static void main(String[] args) {
        CfParser parser = new CfParser(CfParser.defaultProperties());
        check(parser.configured(), "verified default templates enabled");
        signal(parser, "┃ Вы успешно создали ставку.", CfSession.Signal.CREATED);
        signal(parser, "┃ У Вас недостаточно средств для создания ставки.", CfSession.Signal.INSUFFICIENT);
        signal(parser, "┃ Сначала отмените текущую ставку с помощью /cf cancel", CfSession.Signal.CANCEL_REQUIRED);
        signal(parser, "┃ У вас нет активной ставки.", CfSession.Signal.NO_ACTIVE);
        signal(parser, "§7┃§r\u00a0§aВы успешно создали ставку.§r", CfSession.Signal.CREATED);
        signal(parser, "┃Вы успешно создали ставку.", CfSession.Signal.CREATED);
        signal(parser, "  ┃\tВы успешно создали ставку.  ", CfSession.Signal.CREATED);

        CfParser.Parsed win = parser.parse("┃ Победа! Вы выиграли 42,500 " + GLYPH + " (-15%) у ɢᴏʙʟɪɴ FixturePlayer");
        equal(CfSession.Signal.WIN, win.signal(), "personal win");
        equal("42,500", win.reportedAmount(), "reported payout retained, not assumed stake");
        equal("FixturePlayer", win.opponent(), "rank removed");
        check(win.serverMessage() && !win.playerChat() && win.candidate(), "server result classification");
        CfParser.Parsed loss = parser.parse("┃ Поражение! Вы проиграли 50,000 " + GLYPH + " игроку sᴇɴɪᴏʀ .FixtureBedrock");
        equal(CfSession.Signal.LOSS, loss.signal(), "personal loss");
        equal(".FixtureBedrock", loss.opponent(), "bedrock leading dot retained");
        signal(parser, "┃ Победа! Вы выиграли 42,500.50 " + GLYPH + " (-15%) у FixturePlayer", CfSession.Signal.WIN);

        for (String amount : new String[]{"50,00", "5000,000", "0", "-50000", "+50000", "1e6", "NaN", "1,000,000,000,000,001", "50,000.123"}) {
            check(parser.parse("┃ Поражение! Вы проиграли " + amount + " " + GLYPH + " игроку FixturePlayer").signal() == null,
                    "invalid result amount rejected: " + amount);
        }
        for (String nick : new String[]{"player » quoted", "player: quote", "<FixturePlayer>", "FixturePlayer!", ".", "псевдоним"}) {
            check(parser.parse("┃ Поражение! Вы проиграли 50,000 " + GLYPH + " игроку " + nick).signal() == null,
                    "invalid opponent rejected: " + nick);
        }
        CfParser.Parsed publicChat = parser.parse("ɢ ᴊᴜɴɪᴏʀ FixturePlayer » all cf 50k");
        check(publicChat.candidate() && publicChat.playerChat() && !publicChat.serverMessage(), "public CF chatter stays observational");
        check(publicChat.signal() == null, "public CF chatter never confirmed");
        for (String forged : new String[]{
                "ɢ FixturePlayer » ┃ Вы успешно создали ставку.",
                "┃ FixturePlayer » Победа! Вы выиграли 42,500 " + GLYPH + " (-15%) у OtherPlayer",
                "┃ Победа! Вы выиграли 42,500 " + GLYPH + " (-15%) у OtherPlayer » quoted",
                "Победа! Вы выиграли 42,500 " + GLYPH + " (-15%) у OtherPlayer",
                "┃ Победа! Игрок FixturePlayer выиграл 42,500 " + GLYPH + " (-15%) у OtherPlayer",
                "<FixturePlayer> ┃ Вы успешно создали ставку.",
                "┃ Вы успешно создали ставку.\n┃ Вы успешно создали ставку.",
                "┃ Вы успешно создали ставку.\r",
                "┃ Вы успешно создали ставку.\u202e"}) {
            check(parser.parse(forged).signal() == null, "forgery or nonpersonal message rejected");
        }

        Properties legacy = legacyProperties();
        CfParser migrated = new CfParser(legacy);
        signal(migrated, "┃ Сначала отмените текущую ставку с помощью /cf cancel", CfSession.Signal.CANCEL_REQUIRED);
        signal(migrated, "┃ У вас нет активной ставки.", CfSession.Signal.NO_ACTIVE);
        signal(migrated, "┃ Победа! Вы выиграли 42,500 " + GLYPH + " (-15%) у ᴍɪᴅᴅʟᴇ FixturePlayer", CfSession.Signal.WIN);
        check(migrated.parse("┃ Поражение! Вы проиграли 5000,000 " + GLYPH + " игроку FixturePlayer").signal() == null,
                "legacy permissive grouping cannot bypass amount validation");
        check(migrated.parse("┃ Ставка успешно отменена.").signal() == null,
                "unobserved successful cancellation not invented");
        Properties overridden = legacyProperties();
        overridden.setProperty("win.regex", "^┃ .* (?<amount>[0-9,]+) .* у (?<opponent>.+)$");
        check(new CfParser(overridden).parse("┃ Игрок FixturePlayer выиграл 42,500 " + GLYPH + " у OtherPlayer").signal() == null,
                "editable regex cannot admit third-person win");
        Properties ambiguous = CfParser.defaultProperties();
        ambiguous.setProperty("insufficient.regex", ambiguous.getProperty("created.regex"));
        check(new CfParser(ambiguous).parse("┃ Вы успешно создали ставку.").signal() == null, "conflicting event templates fail closed");
        Properties disabled = CfParser.defaultProperties();
        disabled.setProperty("enabled", "false");
        CfParser.Parsed observation = new CfParser(disabled).parse("┃ Вы успешно создали ставку.");
        check(observation.signal() == null && observation.candidate(), "disabled parser observes only");
        check(parser.parse(null).signal() == null && parser.parse("x".repeat(2049)).normalized().isEmpty(), "input bounded");
        Properties bad = CfParser.defaultProperties();
        bad.setProperty("created.regex", ".*");
        rejects(() -> new CfParser(bad), "unanchored rule rejected");
        Properties badEnabled = CfParser.defaultProperties();
        badEnabled.setProperty("enabled", "yes");
        rejects(() -> new CfParser(badEnabled), "invalid enabled setting rejected");
        System.out.println("CfParserTest: " + checks + " checks passed");
    }

    static Properties legacyProperties() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "true");
        properties.setProperty("created.regex", "^┃ Вы успешно создали ставку[.]$");
        properties.setProperty("insufficient.regex", "^┃ У Вас недостаточно средств для создания ставки[.]$");
        properties.setProperty("limit.regex", "^┃ Сначала отмените текущую ставку с помощью /cf cancel$");
        properties.setProperty("win.regex", "^┃ Победа! Вы выиграли (?<amount>[0-9]+(?:,[0-9]{3})*) .+ у (?<opponent>.+)$");
        properties.setProperty("loss.regex", "^┃ Поражение! Вы проиграли (?<amount>[0-9]+(?:,[0-9]{3})*) .+ игроку (?<opponent>.+)$");
        return properties;
    }

    private static void signal(CfParser parser, String message, CfSession.Signal expected) {
        equal(expected, parser.parse(message).signal(), "signal " + expected);
    }
    private static void check(boolean passed, String reason) {
        checks++;
        if (!passed) throw new AssertionError(reason);
    }
    private static void equal(Object expected, Object actual, String reason) {
        check(java.util.Objects.equals(expected, actual), reason + ": expected " + expected + ", actual " + actual);
    }
    private static void rejects(Runnable action, String reason) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(reason);
    }
}
