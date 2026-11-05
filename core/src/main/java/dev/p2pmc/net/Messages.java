package dev.p2pmc.net;

import java.util.HashMap;
import java.util.Map;

/**
 * User-facing texts of the engine in English and Russian. The mod sets the language from the game's language setting
 * ({@code ru_ru} gives Russian, anything else English); the Paper plugin takes it from its config.
 */
public final class Messages {
    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> RU = new HashMap<>();
    private static volatile boolean russian;

    private Messages() {
    }

    /** @param code a Minecraft language code such as {@code ru_ru}, {@code en_us} or a plain {@code ru} */
    public static void setLanguage(String code) {
        russian = code != null && code.toLowerCase(java.util.Locale.ROOT).startsWith("ru");
    }

    public static boolean isRussian() {
        return russian;
    }

    /** The text for {@code key} in the current language; {@code {0}}, {@code {1}}... are replaced by the arguments. */
    public static String t(String key, Object... args) {
        String s = russian ? RU.get(key) : null;
        if (s == null) {
            s = EN.get(key);
        }
        if (s == null) {
            return key;
        }
        for (int i = 0; i < args.length; i++) {
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }

    private static void add(String key, String en, String ru) {
        EN.put(key, en);
        RU.put(key, ru);
    }

    static boolean complete() {
        return EN.keySet().equals(RU.keySet());
    }

    static {
        add("net.checking", "Checking your network...", "Проверяем вашу сеть...");
        add("net.noPublic.host", "No public address found (STUN unreachable). Only friends on your own network can connect directly.",
                "Публичный адрес не найден (STUN недоступен). Напрямую смогут подключиться только друзья из вашей сети.");
        add("net.strict.host", "Your router uses a strict NAT (symmetric / CGNAT). Direct connections will often fail - use your tunnel service (playit.gg) as a fallback.",
                "У вашего роутера строгий NAT (симметричный / CGNAT). Прямое соединение часто не получится, используйте запасной туннель (playit.gg).");
        add("net.friendly.host", "Network looks friendly: direct connections should work.",
                "Сеть подходит: прямые соединения должны работать.");
        add("net.noPublic.join", "No public address found (STUN unreachable) - only a host on your own network can be reached.",
                "Публичный адрес не найден (STUN недоступен): доступен только хост из вашей сети.");
        add("net.strict.join", "Your router uses a strict NAT (symmetric / CGNAT): connecting may fail. Use the host's tunnel (playit.gg) address instead.",
                "У вашего роутера строгий NAT (симметричный / CGNAT): подключение может не получиться. Используйте адрес туннеля хоста (playit.gg).");
        add("net.friendly.join", "Network looks friendly.", "Сеть подходит.");
        add("net.upnp.ok", "UPnP: port {0} opened on your router.", "UPnP: порт {0} открыт на роутере.");
        add("net.upnp.none", "UPnP: no router answered (UPnP may be disabled on it).", "UPnP: роутер не ответил (возможно, UPnP выключен).");

        add("host.notRunning", "Hosting is not running.", "Хостинг не запущен.");
        add("host.tooMany", "Too many connections in progress - wait a moment.", "Слишком много подключений сразу, подождите немного.");
        add("host.opening", "Opening a direct path to your friend...", "Открываем прямой путь к другу...");
        add("host.punchFailed", "Could not open a direct path (NAT too strict?). Use your tunnel service (playit.gg) for this friend.",
                "Не удалось открыть прямой путь (слишком строгий NAT?). Для этого друга используйте туннель (playit.gg).");
        add("peer.securing", "Path open, securing the connection...", "Путь открыт, защищаем соединение...");
        add("peer.connected", "Connected", "Подключён");
        add("peer.disconnected", "Disconnected", "Отключён");
        add("peer.failed", "Connection failed: {0}", "Ошибка соединения: {0}");

        add("join.reading", "Reading the invite...", "Читаем приглашение...");
        add("join.noNetwork", "No network connection found.", "Сетевое подключение не найдено.");
        add("join.timeout", "The host did not accept the answer in time, or the direct path is blocked. Ask the host to paste your answer code, or use the host's tunnel address.",
                "Хост не принял вас вовремя, либо прямой путь заблокирован. Попросите хоста вставить ваш ответный код или используйте адрес туннеля хоста.");
        add("join.waiting", "Connecting to the host... if nothing happens in a few seconds, send your answer code to the host.",
                "Подключаемся к хосту... если через несколько секунд ничего не произошло, отправьте хосту ваш ответный код.");
        add("join.startFailed", "Could not start: {0}", "Не удалось запустить: {0}");
        add("join.ready", "Connected to the host - joining the game...", "Соединение с хостом есть, заходим в игру...");
        add("join.gameTimeout", "The game did not connect to the tunnel in time.", "Игра не подключилась к туннелю вовремя.");
        add("join.closed", "Closed", "Закрыто");

        add("inv.tooShort", "Invite code is too short - copy the whole code.", "Код приглашения слишком короткий: скопируйте его целиком.");
        add("inv.checksum", "Invite code is damaged (checksum mismatch) - copy it again.", "Код приглашения повреждён (не сошлась контрольная сумма): скопируйте его заново.");
        add("ans.tooShort", "Answer code is too short - copy the whole code.", "Ответный код слишком короткий: скопируйте его целиком.");
        add("ans.mismatch", "This answer does not match your current invite (or it is damaged).", "Этот ответ не подходит к вашему приглашению (или повреждён).");
        add("code.pasteInvite", "Paste the invite code first.", "Сначала вставьте код-приглашение.");
        add("code.pasteAnswer", "Paste the answer code first.", "Сначала вставьте ответный код.");
        add("code.notP2P", "This is not a P2P code.", "Это не P2P-код.");
        add("code.isAnswer", "That is an ANSWER code. Paste the host's INVITE code here instead.", "Это ОТВЕТНЫЙ код. Вставьте сюда код-ПРИГЛАШЕНИЕ хоста.");
        add("code.isInvite", "That is an INVITE code. Paste your friend's ANSWER code here instead.", "Это код-ПРИГЛАШЕНИЕ. Вставьте сюда ОТВЕТНЫЙ код друга.");
        add("code.incompatible", "Code was made by an incompatible mod version.", "Код создан несовместимой версией мода.");
        add("code.damaged", "Code is damaged - copy it again.", "Код повреждён: скопируйте его заново.");
        add("code.badChar", "The code contains an invalid character '{0}' - copy it again.", "В коде недопустимый символ '{0}': скопируйте его заново.");

        add("ui.joinButton", "P2P: Join", "P2P: Войти");
        add("ui.inviteButton", "P2P: Invite friends", "P2P: Пригласить друзей");
        add("ui.hostTitle", "P2P host", "P2P-хост");
        add("ui.joinTitle", "P2P join", "P2P-вход");
        add("ui.hostHeader", "Invite friends (direct P2P - no VPN, no public IP)", "Приглашение друзей (прямой P2P: без VPN, белого IP и VPS)");
        add("ui.step1", "1) Send this invite code to your friend:", "1) Отправьте этот код-приглашение другу:");
        add("ui.step2", "2) Only if auto-connect fails: friend's answer code:", "2) Только если автоподключение не сработало: ответный код друга:");
        add("ui.inviteCode", "Invite code", "Код-приглашение");
        add("ui.answerCode", "Answer code", "Ответный код");
        add("ui.copy", "Copy", "Копировать");
        add("ui.paste", "Paste", "Вставить");
        add("ui.accept", "Accept", "Принять");
        add("ui.connect", "Connect", "Подключиться");
        add("ui.cancel", "Cancel", "Отмена");
        add("ui.done", "Done", "Готово");
        add("ui.stop", "Stop hosting", "Остановить");
        add("ui.friends", "Friends:", "Друзья:");
        add("ui.copied", "Invite code copied.", "Код-приглашение скопирован.");
        add("ui.licenceOn", "Licence: ON", "Лицензия: ВКЛ");
        add("ui.licenceOff", "Licence: OFF", "Лицензия: ВЫКЛ");
        add("ui.licenceTip", "ON: only players with a licensed account can join. OFF: offline/cracked launchers can join too (names are not verified, keep the invite code private).",
                "ВКЛ: зайти могут только игроки с лицензией. ВЫКЛ: можно и с пиратских лаунчеров (ники не проверяются, держите код в секрете).");
        add("ui.licenceNowOn", "Licence check ON: only licensed accounts can join (applies to new connections).",
                "Проверка лицензии ВКЛ: зайти могут только лицензионные аккаунты (для новых подключений).");
        add("ui.licenceNowOff", "Licence check OFF: offline launchers can join too. Keep your invite code private.",
                "Проверка лицензии ВЫКЛ: можно и с пиратских лаунчеров. Держите код приглашения в секрете.");
        add("ui.notReady", "Hosting is not ready yet.", "Хостинг ещё не готов.");
        add("ui.accepted", "Answer accepted - connecting to friend {0}...", "Ответ принят, подключаем друга {0}...");
        add("ui.starting", "Starting...", "Запуск...");
        add("ui.worldPort", "World port on this PC: {0} (also usable as the target of a tunnel service).",
                "Порт мира на этом ПК: {0} (его же можно указать в туннельном сервисе).");
        add("ui.joinHeader", "Join a friend over P2P (no VPN, no public IP)", "Вход к другу по P2P (без VPN, белого IP и VPS)");
        add("ui.pasteFirst", "Paste the invite code first.", "Сначала вставьте код-приглашение.");
        add("ui.openWorldFirst", "Open a singleplayer world first, then use this button from the pause menu.",
                "Сначала откройте одиночный мир, затем нажмите эту кнопку в меню паузы.");
        add("ui.publishFailed", "Minecraft could not open the world to the network.", "Minecraft не смог открыть мир для сети.");
        add("ui.publishError", "Could not open the world: {0}", "Не удалось открыть мир: {0}");
        add("ui.startFailed", "Could not start: {0}", "Не удалось запустить: {0}");
        add("ui.joinStep1", "1) Paste the invite code from the host:", "1) Вставьте код-приглашение от хоста:");
        add("ui.joinStep2", "2) Only if it does not connect: send this to the host:", "2) Только если не подключается: отправьте это хосту:");
        add("ui.ping", " (ping {0} ms)", " (пинг {0} мс)");
        add("ui.showQr", "QR code", "QR-код");
        add("ui.qrTitle", "Scan with your phone to copy the invite code", "Отсканируйте телефоном, чтобы получить код-приглашение");
        add("ui.qrBack", "Back", "Назад");
    }
}
