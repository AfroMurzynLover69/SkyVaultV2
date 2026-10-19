-- V1__init_schema.sql
--
-- Schemat bazy pod architekturę Zero-Knowledge.
--
-- ZASADA NADRZĘDNA: serwer NIE przechowuje niczego, co pozwoliłoby mu odczytać
-- zawartość plików ani poznać ich nazwy. W bazie leżą wyłącznie:
--   * material kryptograficzny zamknięty kluczem, ktorego serwer nie zna,
--   * skrot weryfikatora (SHA-256), ktorego nie da sie odwrocic,
--   * zaszyfrowane sciezki oraz deterministyczne skroty HMAC do wyszukiwania.
--
-- Kolumna z JAWNA sciezka pliku celowo NIE istnieje. W starym projekcie (C#)
-- istnialo pole VirtualPath, ktore nigdy nie bylo wypelniane trescia uzytkownika,
-- ale samo jego istnienie jest zaproszeniem do wycieku danych.


-- ---------------------------------------------------------------------------
-- Uzytkownicy
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id                    BIGSERIAL    PRIMARY KEY,
    email                 VARCHAR(254) NOT NULL UNIQUE,
    -- SHA-256(authVerifier), base64. Porownywane w czasie stalym.
    auth_verifier_hash    VARCHAR(64)  NOT NULL,
    -- Sol Argon2id (16 B, base64). Jawna - jest potrzebna klientowi przed logowaniem.
    password_salt         VARCHAR(64)  NOT NULL,
    -- Klucz glowny uzytkownika zaszyfrowany KEK (>= 48 B, base64).
    -- Serwer nie ma KEK, wiec nie odszyfruje tego nigdy.
    wrapped_master_key    VARCHAR(128) NOT NULL,
    wrapped_master_key_iv VARCHAR(64)  NOT NULL,
    quota_bytes           BIGINT       NOT NULL,
    used_bytes            BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);


-- ---------------------------------------------------------------------------
-- Rejestracje oczekujace na potwierdzenie e-maila
-- Konto nie powstaje, dopoki kod nie zostanie wpisany. Dzieki temu adres e-mail
-- nie jest zajmowany przez kogos, kto go nie posiada.
-- ---------------------------------------------------------------------------
CREATE TABLE pending_registration (
    email                 VARCHAR(254) PRIMARY KEY,
    auth_verifier_hash    VARCHAR(64)  NOT NULL,
    password_salt         VARCHAR(64)  NOT NULL,
    wrapped_master_key    VARCHAR(128) NOT NULL,
    wrapped_master_key_iv VARCHAR(64)  NOT NULL,
    code                  VARCHAR(12)  NOT NULL,
    expires_at            TIMESTAMPTZ  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);


-- ---------------------------------------------------------------------------
-- Sesje
-- Identyfikator sesji jest losowym tokenem (32 B). W bazie trzymamy jego skrot,
-- zeby wyciek bazy nie pozwolil podszyc sie pod zalogowanego uzytkownika.
-- ---------------------------------------------------------------------------
CREATE TABLE user_session (
    token_hash   VARCHAR(128) PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_user_session_user ON user_session (user_id);


-- ---------------------------------------------------------------------------
-- Metadane plikow - wszystko zaszyfrowane po stronie klienta
-- ---------------------------------------------------------------------------
CREATE TABLE stored_file (
    id                     BIGSERIAL    PRIMARY KEY,
    user_id                BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- HMAC-SHA256(masterKey, "SkyVault path v1:" + sciezka), hex.
    -- Deterministyczny, wiec serwer moze szukac i wykrywac konflikty, nie znajac sciezki.
    path_hash              VARCHAR(128) NOT NULL,
    -- Sciezka zaszyfrowana AES-GCM kluczem glownym uzytkownika.
    encrypted_virtual_path VARCHAR(4096) NOT NULL,
    virtual_path_iv        VARCHAR(64)  NOT NULL,
    -- Nazwa bloba na dysku - losowa, niemowiaca nic o zawartosci.
    blob_id                VARCHAR(64),
    -- Rozmiar jawny i rozmiar po zaszyfrowaniu. Serwer zna rozmiary - to jedyny
    -- metadana, ktorej nie da sie ukryc, bo musi zaplanowac miejsce na dysku.
    size_bytes             BIGINT       NOT NULL DEFAULT 0,
    stored_size_bytes      BIGINT       NOT NULL DEFAULT 0,
    is_folder              BOOLEAN      NOT NULL DEFAULT FALSE,
    in_trash               BOOLEAN      NOT NULL DEFAULT FALSE,
    trashed_at             TIMESTAMPTZ,
    starred                BOOLEAN      NOT NULL DEFAULT FALSE,
    modified_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Unikalnosc sciezki tylko wsrod plikow aktywnych. Dzieki temu kosz moze trzymac
-- wpis o tej samej sciezce, ktora uzytkownik odtworzyl pod ta sama nazwa.
CREATE UNIQUE INDEX uq_stored_file_active_path
    ON stored_file (user_id, path_hash)
    WHERE NOT in_trash;

CREATE INDEX ix_stored_file_user ON stored_file (user_id);
