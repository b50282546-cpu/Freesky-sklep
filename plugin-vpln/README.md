# FreeSky VPLN — plugin Paper 1.21.x

Plugin dodaje własne saldo VPLN, GUI sklepu `/portfel`, ogłoszenia zakupów i czasowy mnożnik nagród.

## Wymagania
- Serwer Paper 1.21.x
- Java 21
- Maven 3.9+ do zbudowania pliku JAR

## Budowanie
W katalogu `plugin-vpln` uruchom:

```bash
mvn clean package
```

Gotowy plik będzie w `target/FreeSkyVPLN-1.0.0.jar`. Skopiuj go do folderu `plugins` serwera i uruchom serwer ponownie.

## Komendy
- `/portfel` — otwiera GUI sklepu.
- `/vpln balance` — pokazuje własne saldo.
- `/vpln balance <gracz>` — sprawdza saldo gracza (admin).
- `/vpln give <gracz> <kwota>` — dodaje VPLN bez mnożnika.
- `/vpln set <gracz> <kwota>` — ustawia saldo.
- `/vpln earn <gracz> <kwota>` — dodaje nagrodę z aktywnym mnożnikiem.
- `/vpln mnoznik <wartość> <czas>` — włącza mnożnik, np. `/vpln mnoznik 2 30m`, `/vpln mnoznik 3 2h`.
- `/vpln mnoznik off` — wyłącza mnożnik.
- `/vpln reload` — przeładowuje config.
- `/vpln help` — pomoc.

Komendy administracyjne wymagają uprawnienia `freeskyvpln.admin` (domyślnie OP).

## Jak działa mnożnik
Mnożnik dotyczy nagród przyznawanych komendą `/vpln earn` i integracjom korzystającym z publicznej metody `deposit(UUID, amount, true)`. Przykład: przy mnożniku 2x nagroda 50 VPLN daje 100 VPLN. Zwykłe `give`, ustawianie salda i wydatki w sklepie nie są mnożone. Czas trwa zgodnie z zegarem rzeczywistym i jest zapisany w configu, więc restart serwera nie zeruje aktywnego eventu.

## Produkty i integracja rang/kluczy
Produkty oraz ceny edytujesz w `src/main/resources/config.yml`. Produkty mogą dawać przedmiot Minecrafta albo uruchamiać komendy konsoli. Dla nagród komendowych wpisz komendy pluginu, którego używa serwer, np. komendę nadawania rangi/klucza, i ustaw `commands:` jako listę komend. Gdy lista komend nie jest pusta, nagroda materiałowa nie jest dodawana — wykonywane są komendy z konfiguracji. Nie wpisuj komend od nieznanych pluginów; najpierw sprawdź ich dokumentację.

## Ważne ograniczenie integracji
Na start to lokalna ekonomia pluginu, zapisywana w `plugins/FreeSkyVPLN/balances.yml`. Nie jest jeszcze połączona z zakupami na stronie internetowej ani z innymi pluginami ekonomii. Żeby automatycznie dopisywać VPLN po płatności na stronie, potrzebna będzie bezpieczna integracja backendu sklepu z serwerem (nie wolno ufać samemu żądaniu z przeglądarki).

## Dane
Salda graczy są identyfikowane UUID i zapisane w `balances.yml`; nie usuwaj tego pliku, jeśli chcesz zachować salda. Rób kopie zapasowe folderu `plugins/FreeSkyVPLN`.
