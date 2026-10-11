# Konfiguracja automatycznych VPLN i panelu sklepu

Kod integracji jest przygotowany, ale automatyczne wydawanie nie ruszy, dopóki nie skonfigurujesz sekretów Cloudflare i serwera Minecraft.

## Co zostało dodane
- Po kliknięciu **Potwierdź płatność** w Discordzie zamówienie trafia do kolejki dostaw dla serwera.
- Plugin Paper sprawdza kolejkę co 5 sekund, dopisuje VPLN do salda gracza (także offline), pokazuje tytuł i ogłoszenie oraz zapisuje ID dostawy, żeby nie naliczyć tego samego zamówienia drugi raz.
- Przeliczenie: **1 PLN = 1 VPLN**. Ponieważ saldo pluginu przechowuje całkowite VPLN, kwota z groszami jest zaokrąglana do najbliższej liczby całkowitej.
- `/portfel` ma zakładki kategorii.
- `/vpln gui` otwiera panel administracyjny. Weź przedmiot do ręki i kliknij „Dodaj produkt z ręki”; kliknięcie produktu zmienia cenę o 10 VPLN, Shift+klik włącza/wyłącza, a Shift+prawy klik usuwa produkt. Dotychczasowe komendy zostają.

## 1. Cloudflare Worker

Plik `wrangler.toml` wskazuje teraz `worker-v2.js`.

1. W Cloudflare utwórz KV namespace i dodaj binding o nazwie `ORDERS_KV` do Workera.
2. Ustaw zmienne/sekrety Workera:
   - `DISCORD_BOT_TOKEN` — token bota (secret)
   - `DISCORD_CHANNEL_ID` — ID kanału zamówień
   - `DISCORD_PUBLIC_KEY` — public key aplikacji Discord
   - `ADMIN_ROLE_ID` — ID roli uprawnionej do potwierdzania płatności
   - `DISCORD_GUILD_ID` — ID serwera Discord (zalecane)
   - `GAME_BRIDGE_SECRET` — długi, losowy sekret współdzielony z pluginem
3. W Discord Developer Portal ustaw **Interactions Endpoint URL** na `https://ADRES-WORKERA/interactions`.
4. W katalogu projektu uruchom `npx wrangler deploy`. Przed wdrożeniem sprawdź, że binding `ORDERS_KV` rzeczywiście jest przypięty.

Nie umieszczaj tokenów ani sekretów w `index.html`, publicznym repozytorium ani wiadomościach Discorda.

## 2. Plugin Paper 1.21.x

1. Zbuduj plugin w katalogu `plugin-vpln` poleceniem `mvn package` (wymaga JDK 21 i Maven).
2. Skopiuj `plugin-vpln/target/FreeSkyVPLN-1.0.0.jar` do katalogu `plugins` serwera Paper i uruchom serwer raz, żeby wygenerował konfigurację.
3. W `plugins/FreeSkyVPLN/config.yml` ustaw:
   - `web-bridge.enabled: true`
   - `web-bridge.url` na bazowy adres wdrożonego Workera, np. `https://nazwa.workers.dev`
   - `web-bridge.secret` dokładnie na tę samą wartość co sekret Cloudflare `GAME_BRIDGE_SECRET`
4. Zrestartuj serwer. Nie publikuj pliku config.yml, bo zawiera sekret.

## 3. Test bez ryzyka

Najpierw wykonaj testowe zamówienie o małej wartości. Dopiero po potwierdzeniu płatności kliknij przycisk na Discordzie. Sprawdź saldo w grze, tytuł, broadcast oraz status dostawy w KV. Nie klikaj „Potwierdź płatność” dla nieopłaconych zamówień.

**Uwaga:** samo zapisanie zmian w repozytorium nie konfiguruje Twojego konta Cloudflare, aplikacji Discord ani serwera Minecraft. Te kroki wymagają dostępu do tych usług.
