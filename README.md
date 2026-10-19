# SkyVault

Projekt na pracę inżynierską.

**Temat:** Implementacja architektury Zero-Knowledge w aplikacji umożliwiającej przechowywanie
zaszyfrowanych danych w chmurze obliczeniowej.

Autor: Patryk Rysiński · Politechnika Świętokrzyska · Teleinformatyka
Promotor: dr inż. Mariusz Ginter

## Stan projektu

Na tym etapie gotowy jest **szkielet technologiczny**: moduły, launcher, serwer z limitem
współbieżności i strona kontrolna. Logika Zero-Knowledge dojdzie w kolejnych krokach.

## Struktura

```
SkyVault\
├── SkyVault.ps1      ← LAUNCHER. Tylko uruchamianie modułów, bez logiki aplikacji.
├── SkyVault.cmd      ← to samo co wyżej, do uruchomienia dwuklikiem
├── pom.xml           ← korzeń projektu (agreguje moduły)
│
├── Web\              ← MODUŁ STRONY
│   └── src\main\resources\META-INF\resources\
│       ├── index.html
│       ├── style.css
│       └── app.js
│
└── Server\           ← MODUŁ SERWERA
    └── src\main\
        ├── java\pl\politechnika\skyvault\server\
        │   ├── ServerApplication.java
        │   ├── api\StatusController.java
        │   ├── concurrency\ConcurrencyLimiter.java
        │   ├── concurrency\ConcurrencyLimitFilter.java
        │   └── config\ServerProperties.java
        └── resources\
            ├── application.properties
            └── db\migration\        ← migracje Flyway
```

**Dlaczego moduły, a nie jeden projekt?** Web to osobny artefakt (JAR z plikami strony).
Server zależy od Web, więc strona jest serwowana automatycznie — bez kopiowania plików
i bez osobnego serwera HTTP. Przy okazji zmiana w wyglądzie strony nie wymaga
przebudowy kodu serwera.

## Stos technologiczny

| Element | Wersja |
|---|---|
| Java | 25 (LTS) |
| Spring Boot | 4.1.1 |
| Spring Web MVC, Spring Data JPA, Hibernate | — |
| PostgreSQL | 18.6 |
| Flyway | — |
| Maven (wrapper `mvnw`) | — |

## Uruchamianie

```powershell
cd C:\Users\PKR\Desktop\KOD\SkyVault
.\SkyVault.ps1
```

Launcher obsługuje tryby:

| Tryb | Działanie |
|---|---|
| `run` | zbuduj i uruchom serwer *(domyślne)* |
| `build` | zbuduj wszystkie moduły (`-SkipTests` pomija testy) |
| `test` | uruchom testy |
| `clean` | wyczyść katalogi `target` |
| `modules` | pokaż listę modułów |
| `help` | pomoc |

Gdyby PowerShell blokował skrypty:

```powershell
powershell -ExecutionPolicy Bypass -File .\SkyVault.ps1 build
```

Maven **nie jest** wymagany — projekt używa wrappera `mvnw.cmd`, który sam pobiera właściwą
wersję. Wymagane są: **JDK 25**, **PostgreSQL 18** i ustawiony `JAVA_HOME`.

## Adresy

| Adres | Opis |
|---|---|
| `http://localhost:8080/` | strona kontrolna |
| `http://localhost:8080/api/status` | stan serwera i licznik współbieżności |
| `http://localhost:8080/api/ping?delayMs=500` | żądanie z symulowanym opóźnieniem |
| `http://localhost:8080/actuator/health` | stan aplikacji i bazy |

## Limit współbieżności

Serwer obsługuje jednocześnie **maksymalnie 256 użytkowników**.

Mechanizm jest dwuwarstwowy:

1. **Pula wątków Tomcata** — `server.tomcat.threads.max=256`. Tyle żądań kontener obsłuży
   równolegle; nadwyżka czeka w kolejce (`accept-count=512`).
2. **Semafor aplikacji** — `ConcurrencyLimiter` z 256 miejscami. Żądania do `/api/**` zajmują
   miejsce na czas obsługi. Gdy wszystkich 256 jest zajętych, żądanie czeka
   `acquire-timeout-ms` (domyślnie 2000 ms), a potem dostaje **HTTP 429 Too Many Requests**.

Żądania po pliki statyczne oraz `/actuator/**` **nie** są liczone — dzięki temu strona zawsze
się wczyta, a monitoring działa nawet przy pełnym obciążeniu.

Ustawienia w `Server/src/main/resources/application.properties`:

```properties
skyvault.server.max-concurrent-users=256
skyvault.server.acquire-timeout-ms=2000
```

Można je nadpisać bez zmiany kodu:

```powershell
$env:MAX_CONCURRENT_USERS = 32
.\SkyVault.ps1
```

## Baza danych

Baza `skyvault` i rola `skyvault` muszą istnieć:

```sql
CREATE ROLE skyvault LOGIN PASSWORD 'skyvault';
CREATE DATABASE skyvault OWNER skyvault;
```

Domyślne połączenie: `127.0.0.1:5432 / skyvault`, użytkownik `skyvault`, hasło `skyvault`.
Nadpisanie: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.

> Hasło `skyvault` to wartość **wyłącznie deweloperska**. Przed wdrożeniem ustaw
> `DB_PASSWORD` spoza repozytorium.

Migracje Flyway leżą w `Server/src/main/resources/db/migration`. Katalog jest na razie pusty —
schemat bazy powstanie w kolejnym kroku. Zasada: plików `V1__...` nie edytuje się po wykonaniu
na bazie, każda zmiana to nowy plik z kolejnym numerem.

## Testy

```powershell
.\SkyVault.ps1 test
```

- `ServerApplicationTests` — czy kontekst Springa startuje (konfiguracja, baza, Flyway).
- `ConcurrencyLimiterTests` — czy limit współbieżności faktycznie nie jest przekraczany
  przy 200 równoległych żądaniach.

## Test współbieżności serwera

Osobne narzędzie, którym sprawdzisz limit 256 na działającym serwerze:

```powershell
.\SkyVault-loadtest.ps1                            # 320 zadań, limit domyślny 256
.\SkyVault-loadtest.ps1 -Requests 40 -DelayMs 3000 # szybka weryfikacja ścieżki 429
```

Skrypt wysyła prawdziwe równoległe połączenia i zlicza odpowiedzi `200` oraz `429`.
`DelayMs` musi być większy niż `acquire-timeout-ms` (domyślnie 2000 ms) — inaczej nadwyżka
doczeka się wolnego miejsca i dostanie `200` zamiast `429`.

Dlaczego nie ma tego na stronie? Przeglądarka otwiera do jednego serwera tylko kilka
połączeń naraz, więc z poziomu przeglądarki nie da się zapełnić limitu 256 miejsc.

## Dalsze kroki

1. Projekt schematu bazy pod architekturę Zero-Knowledge (użytkownicy, metadane plików, klucze).
2. Rejestracja i logowanie z weryfikacją e-mail.
3. Szyfrowanie po stronie klienta (AES-GCM, Argon2id, HKDF).
4. Testy funkcjonalne i ocena działania systemu — wymagane w planie pracy.
