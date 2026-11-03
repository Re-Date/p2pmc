# P2P Minecraft (аналог Essential: хостинг мира без белого IP и VPS)

## Как это работает
1. Хост открывает свой одиночный мир и в меню паузы жмёт **P2P: Invite friends**. Мод публикует мир на локальный порт,
   узнаёт внешний адрес через публичный STUN и показывает **код-приглашение**.
2. Друг в главном меню жмёт **P2P: Join**, вставляет код и нажимает **Connect**. Мод показывает **ответный код**.
3. Друг отправляет ответный код хосту, хост вставляет его в свой экран. Оба NAT «пробиваются» одновременно (UDP hole punching).
   Если NAT хоста мягкий, ответный код не нужен.
4. Дальше игра идёт как по LAN, но через шифрованный прямой канал (X25519 + ChaCha20-Poly1305, ключ привязан к токену из кода).

Ни VPN, ни VPS, ни сторонних серверов, кроме публичных STUN (они только сообщают ваш внешний адрес, трафик игры через них не идёт).

## Если прямое соединение невозможно (CGNAT, симметричный NAT)
Нужен релей, и серверы тут не обойти (ZeroTier и Radmin делают так же). Запасной вариант: хост запускает playit.gg
на порт своего мира, друзья заходят на адрес playit как на обычный сервер. Мод показывает порт мира на экране хоста.

## Paper-плагин («P2P: Invite friends» для сервера на домашнем ПК)
Положите `dist/P2PMinecraft-1.0.0.jar` в `plugins/`. Команды (право `p2p.admin`, по умолчанию операторы):
- `/p2p invite` показывает код-приглашение (запускает хостинг при первом вызове)
- `/p2p accept <ответный код>` принимает ответный код друга
- `/p2p status` показывает сеть и друзей с пингом, `/p2p stop` останавливает
- `auto-start: true` в `plugins/P2PMinecraft/config.yml` печатает код в консоль при старте сервера

Важно: друзья заходят через 127.0.0.1, поэтому у всех один IP. В `bukkit.yml` поставьте `connection-throttle: 0`
(иначе двое друзей, зашедших в пределах 4 секунд, получат отказ) и оставьте `server-ip` пустым в `server.properties`.
Друзья подключаются модом (P2P: Join), как и к миру хоста.

## Структура
- `core/` чистая JDK (Java 21+): криптография, STUN, надёжный UDP (ARQ), коды-приглашения, мост TCP<->P2P. Без Minecraft и без зависимостей.
- `mod/` Fabric-мод (Minecraft 26.x, Java 25): экраны, кнопки (2 миксина), конфиг `config/p2pmc.json`.
- `plugin/` Paper-плагин (тонкая обёртка над ядром).
- `dist/` готовый jar плагина. `.github/workflows/build.yml` собирает оба jar и гоняет тесты (GitHub Actions).

## Проверка ядра (без Minecraft)
```
javac --release 21 -d out $(find core/src -name '*.java')
java -cp out dev.p2pmc.crypto.CryptoSelfTest
java -cp out dev.p2pmc.net.TransportSelfTest
java -cp out dev.p2pmc.net.EndToEndSelfTest
```

## Сборка мода и плагина
```
cd mod    && gradle build     # jar в mod/build/libs
cd plugin && gradle build     # jar в plugin/build/libs
```
Или без установки Java: загрузите папку в репозиторий GitHub, и workflow соберёт оба jar (артефакт `p2pmc-jars`).
Версии (`minecraft_version`, `loom_version`, `loader_version`) сверьте на https://fabricmc.net/develop.

## Что НЕ проверено
Код мода не компилировался против Minecraft 26.3 (нет доступа к его исходникам). Использованы имена Mojang;
если сборка ругнётся, это почти наверняка одно из: `ConnectScreen.startConnecting` (JoinScreen.java),
`HttpUtil.getAvailablePort`, `IntegratedServer.publishServer`, `keyboardHandler.getClipboard/setClipboard`, id методов в миксинах.
