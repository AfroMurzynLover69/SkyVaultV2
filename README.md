# SkyVault

Projekt na pracę inżynierską.

**Temat:** Implementacja architektury Zero-Knowledge w aplikacji umożliwiającej przechowywanie
zaszyfrowanych danych w chmurze obliczeniowej.

Autor: Patryk Rysiński · Politechnika Świętokrzyska · Teleinformatyka
Promotor: dr inż. Mariusz Ginter

## Stan projektu

Gotowe są: **szkielet technologiczny**, **API Zero-Knowledge** (rejestracja, logowanie,
sesje, operacje na zaszyfrowanych plikach) oraz **interfejs przeglądania dysku** —
w całości w czerni i bieli, w całości szyfrujący dane w przeglądarce.

## Struktura

```
SkyVault\
├── SkyVault.ps1      ← LAUNCHER. Tylko uruchamianie modułów, bez logiki aplikacji.
├── SkyVault.cmd      ← to samo co wyżej, do uruchomienia dwuklikiem
├── pom.xml           ← korzeń projektu (agreguje moduły)
│
├── Web\              ← MODUŁ STRONY
│   └── src\main\resources\META-INF\resources\
│       ├── index.html          ← szkielet GUI (własny sprite ikon SVG)
│       ├── style.css           ← motyw czarno-biały, tylko skala szarości
│       ├── crypto.js           ← Argon2id, HKDF, AES-GCM (WebCrypto + libsodium)
│       ├── app.js              ← logika SPA: dysk, biblioteki, kosz, upload
│       └── vendor\             ← libsodium (wersja lokalna, bez CDN)
│
└── Server\           ← MODUŁ SERWERA
    └── src\main\
        ├── java\com\skyvault\
        │   ├── ServerApplication.java
        │   ├── api\          ← /api/status, /api/ping
        │   ├── auth\         ← rejestracja, logowanie, pakiet kryptograficzny
        │   ├── file\         ← metadane plików, bloby, kosz
        │   ├── session\      ← sesje i ochrona endpointów
        │   ├── user\         ← konto użytkownika
        │   ├── concurrency\  ← limit 256 współbieżnych żądań
        │   ├── config\       ← konfiguracja Springa
        │   └── error\        ← obsługa błędów API
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
| `http://localhost:8080/` | interfejs SkyVault |
| `http://localhost:8080/api/status` | stan serwera i licznik współbieżności |
| `http://localhost:8080/api/ping?delayMs=500` | żądanie z symulowanym opóźnieniem |
| `http://localhost:8080/actuator/health` | stan aplikacji i bazy |

## API Zero-Knowledge

Serwer **nie zna hasła, klucza głównego ani nazw plików**. Cała kryptografia odbywa się
w przeglądarce:

```
hasło
  → Argon2id(sól 16 B, 256 MB)          = seed 32 B
  → HKDF-SHA256("AuthVerifier")         = authVerifier 32 B   → serwer dostaje SHA-256
  → HKDF-SHA256("MasterKey KEK")        = KEK 32 B
masterKey 32 B (losowy)
  → AES-GCM(KEK, iv 12 B)               = wrappedMasterKey 48 B → serwer zapisuje bez zmian
```

Serwer przechowuje wyłącznie: skrót weryfikatora, sól, zapakowany klucz główny oraz
zaszyfrowane ścieżki. Kolumny z jawną nazwą pliku **nie ma**.

### Format bloba

Klient wysyła zaszyfrowaną zawartość jako jeden kawałek:

```
blob = "SKYVAULT-AESGCM-v1\n" (19 B) || IV (12 B) || AES-GCM(klucz główny, zawartość)
```

Nagłówek jednoznacznie identyfikuje format i wersję, więc IV jest częścią bloba
i serwer nie musi go nigdzie zapisywać. Rozpoznanie wersji nie opiera się na długości
pliku ani na heurystyce: odszyfrowywanie czyta magiczny nagłówek, a gdy go nie ma,
wraca do starego układu `IV || szyfrogram` (zgodność wstecz).

`sizeBytes` to rozmiar jawny (przed szyfrowaniem), a `storedSizeBytes` — rozmiar
tego, co faktycznie dotarło na serwer (przy tej konwencji: `sizeBytes + 47`:
19 B nagłówka + 12 B IV + 16 B tagu AES-GCM).

Ścieżka ma osobne pole `virtualPathIv`, bo jej szyfrogram i IV są zwracane
klientowi razem z metadanymi.

### Uwierzytelnianie

| Metoda | Endpoint | Opis |
|---|---|---|
| `POST` | `/api/auth/register` | pakiet kryptograficzny → utworzenie konta i sesji |
| `POST` | `/api/auth/login` | weryfikator → sesja |
| `POST` | `/api/auth/logout` | koniec sesji |
| `GET` | `/api/auth/crypto?email=` | sól i zapakowany klucz główny |
| `GET` | `/api/auth/me` | profil zalogowanego użytkownika |

`/api/auth/crypto` zwraca poprawny kształtem materiał także dla nieistniejącego adresu,
więc nie da się nim sprawdzić, które konta istnieją.

### Pliki (wymagają sesji)

| Metoda | Endpoint | Opis |
|---|---|---|
| `GET` | `/api/files` | lista aktywnych plików i folderów |
| `GET` | `/api/files/trash` | zawartość kosza |
| `POST` | `/api/files` | rejestracja pliku (multipart: metadane + zaszyfrowany blob) |
| `POST` | `/api/folders` | utworzenie folderu |
| `GET` | `/api/files/{pathHash}/content` | pobranie zaszyfrowanego bloba |
| `POST` | `/api/files/{pathHash}/move` | zmiana nazwy lub przeniesienie |
| `POST` | `/api/files/{pathHash}/trash` | przeniesienie do kosza |
| `POST` | `/api/files/{pathHash}/restore` | przywrócenie z kosza |
| `POST` | `/api/files/{pathHash}/star` | oznaczenie gwiazdką |
| `DELETE` | `/api/files/{pathHash}` | trwałe usunięcie (tylko z kosza) |

`pathHash` to `HMAC-SHA256(masterKey, "SkyVault path v1:" + ścieżka)` w postaci hex.
Jest deterministyczny, więc serwer może wyszukiwać i wykrywać konflikty, nie znając ścieżki.
Klucz HMAC jest inny dla każdego użytkownika, więc tych samych nazw nie da się skorelować
między kontami.

Pełny test przepływu: `.\SkyVault-api-test.ps1` (serwer musi działać).

## Interfejs (GUI)

Strona jest zwykłym HTML/CSS/JS serwowanym z modułu `Web` — bez frameworka i bez
procesu budowania frontendu. Układ i mechanika zostały przeniesione ze starego projektu
(C#/Angular), ale kod i wygląd są nowe: **cała kolorystyka to skala szarości od `#0a0a0a`
do `#ffffff`**, a ikony to własny sprite SVG (`currentColor`), bez zewnętrznych zestawów.

Widoki:

| Widok | Zawartość |
|---|---|
| Strona główna / Mój dysk | foldery i pliki wybranego poziomu, breadcrumbs |
| Komputery | informacje o bieżącej sesji i zasadzie odblokowywania na urządzeniu |
| Dokumenty, Obrazy, Filmy, Muzyka | biblioteki filtrowane po rozszerzeniu |
| Ostatnie | 60 najnowszych plików z całego dysku |
| Oznaczone gwiazdką | pliki i foldery z gwiazdką |
| Kosz | przywracanie i trwałe usuwanie |
| Skydysk | wykorzystanie limitu, największe pliki, narzut szyfrowania |

Mechanika:

- **Przesyłanie** — przyciskiem, przeciągnięciem plików na obszar roboczy albo całym
  katalogiem (`Prześlij folder` w menu pod prawym przyciskiem). Plik jest szyfrowany
  w karcie i dopiero potem wysyłany; konflikt nazw rozwiązuje się automatycznie
  (`notatka (1).txt`).
- **Pobieranie i podgląd** — pobranie odszyfrowuje blob w przeglądarce. Podgląd obsługuje
  obrazy, filmy, dźwięk, PDF oraz tekst (`text/plain` i typowe rozszerzenia kodu).
- **Nazwy i foldery** — zmiana nazwy, przenoszenie (także przeciągnięciem na folder),
  tworzenie folderów. Folder przenoszony razem z całą zawartością.
- **Kosz** — przenoszenie, przywracanie i trwałe usuwanie; opróżnianie całości.
- **Menu kontekstowe** (prawy przycisk myszy) — otwórz/podgląd, pobierz, gwiazdka,
  zmiana nazwy, przenoszenie, informacje, kosz; na pustym obszarze — nowy folder,
  przesyłanie plików i katalogu, odświeżenie.
- **Skróty** — `Ctrl+F` szukaj, `F2` zmiana nazwy, `Delete` do kosza, `Enter` otwórz.

Klucz główny żyje wyłącznie w `sessionStorage` karty: zamknięcie karty zapomina klucz,
a otwarcie SkyVault w nowej karcie prosi o hasło („odblokuj dysk”) — sesja serwerowa
nadal obowiązuje, ale klucza nikt nie odtworzy bez hasła. Po rejestracji klient pobiera
**kartę odzyskiwania** z kluczem głównym; bez niej utrata hasła oznacza utratę danych.

Web Crypto wymaga bezpiecznego kontekstu: `http://localhost` i `http://127.0.0.1`
działają, ale dostęp przez LAN po zwykłym HTTP — już nie (potrzebny HTTPS).

Zasoby statyczne są serwowane z `Cache-Control: no-store`. Powód: Spring Boot pakuje
wpisy JAR z datą 1980-02-01 (powtarzalne buildy), więc przeglądarka dostawałaby `304`
i trzymała stary interfejs po aktualizacji.

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

Migracje Flyway leżą w `Server/src/main/resources/db/migration`. Schemat opisuje
`V1__init_schema.sql` (tabele `app_user`, `user_session`, `stored_file`).

Flyway **jest potrzebny**: `spring.jpa.hibernate.ddl-auto=validate` sprawdza przy starcie,
czy encje zgadzają się ze schematem. Bez migracji dodanie nowej encji uniemożliwi start aplikacji.

Zasada: plików `V1__...` nie edytuje się po wykonaniu na bazie — każda zmiana to nowy plik
z kolejnym numerem.

### Przechowywanie plików

Zaszyfrowane bloby trafiają do `data/files/u{id}/`. Serwer zapisuje bajty dokładnie w takiej
postaci, w jakiej dostał je od klienta — nie ma klucza, więc nie potrafi ich odczytać.
Katalog `data/` jest w `.gitignore`.

| Zmienna | Domyślnie | Znaczenie |
|---|---|---|
| `SKYVAULT_DATA_DIR` | `data/files` | katalog na bloby |
| `DEFAULT_QUOTA_GB` | `5` | limit miejsca na konto |

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

1. ~~Projekt schematu bazy pod architekturę Zero-Knowledge~~ — gotowy (`V1__init_schema.sql`).
2. ~~Rejestracja i logowanie~~ — gotowe.
3. ~~Szyfrowanie po stronie klienta w przeglądarce (AES-GCM, Argon2id, HKDF) i GUI przeglądania~~ — gotowe (moduł `Web`).
4. Testy funkcjonalne i ocena działania systemu — wymagane w planie pracy.
