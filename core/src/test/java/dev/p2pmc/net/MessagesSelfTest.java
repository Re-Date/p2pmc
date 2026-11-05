package dev.p2pmc.net;

/** Run: java -cp out dev.p2pmc.net.MessagesSelfTest */
public final class MessagesSelfTest {
    public static void main(String[] args) {
        check(Messages.complete(), "every English text has a Russian one and vice versa");
        Messages.setLanguage("en_us");
        check(Messages.t("ui.accepted", "abc").equals("Answer accepted - connecting to friend abc..."), "English with argument");
        Messages.setLanguage("ru_ru");
        check(Messages.t("ui.accepted", "abc").equals("Ответ принят, подключаем друга abc..."), "Russian with argument");
        check(Messages.t("no.such.key").equals("no.such.key"), "unknown key falls back to the key");
        Messages.setLanguage(null);
        check(!Messages.isRussian() && Messages.t("ui.done").equals("Done"), "null language is English");
        Messages.setLanguage("ru");
        try {
            Invite.decodeOffer("");
            check(false, "short code must throw");
        } catch (InviteException e) {
            check(e.getMessage().contains("Сначала вставьте"), "engine errors follow the language: " + e.getMessage());
        }
        Messages.setLanguage("en_us");
        System.out.println("MESSAGES OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
    }
}
