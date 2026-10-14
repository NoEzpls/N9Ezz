package dev.n9ezz.cf;

/** Offline checks. Balance commands are now handled by the controller's single queue. */
public final class CfDiagnostics {
    private CfDiagnostics() {}

    public static void selftest() {
        CfParser parser = new CfParser(CfParser.defaultProperties());
        check(parser.parse("§a┃ Вы успешно создали ставку.").signal() == CfSession.Signal.CREATED, "created");
        check(parser.parse("┃ Сначала отмените текущую ставку с помощью /cf cancel").signal() == CfSession.Signal.CANCEL_REQUIRED, "cancel required");
        check(parser.parse("┃ У вас нет активной ставки.").signal() == CfSession.Signal.NO_ACTIVE, "no active");
        check(parser.parse("ɢ ᴋɪɴɢ ExamplePlayer » all /cf").signal() == null, "public chat");
        CfBalance balance = new CfBalance(CfBalance.defaultProperties());
        check(new java.math.BigDecimal("2785000").equals(balance.parse("Ваш баланс: 2,785,000$ | 0 рейдов | 0 коинов")), "own coins");
        check(balance.parse("Баланс ExamplePlayer: 2,785,000$ | 0 рейдов | 0 коинов") == null, "foreign balance");
        CfSession session = new CfSession();
        session.start(2, 0);
        check(session.balanceVerified(1), "initial balance");
        check(session.tick(2000, true, false) != null, "create");
        session.onSignal(CfSession.Signal.CANCEL_REQUIRED, 2100);
        check(session.snapshot().unresolved() && !session.snapshot().runAllowed(), "cancel pause");
        session.beginCancelVerification(4000);
        check(session.onSignal(CfSession.Signal.NO_ACTIVE, 4100), "no active accepted");
        check(!session.snapshot().unresolved() && session.tick(6000, true, false) == null, "no automatic restart");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("selftest failed: " + label);
    }
}
