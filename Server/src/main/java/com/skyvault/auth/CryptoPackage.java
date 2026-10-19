package com.skyvault.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Walidacja i obsługa pakietu kryptograficznego przysyłanego przez klienta.
 *
 * <p>To jest <b>kontrakt Zero-Knowledge</b> między przeglądarką a serwerem. Serwer nie
 * ufa klientowi, więc sprawdza, czy każdy element ma dokładnie taki rozmiar, jaki wynika
 * z przyjętego schematu:
 *
 * <pre>
 *   hasło
 *     → Argon2id(sól 16 B, 256 MB)            = seed 32 B
 *     → HKDF-SHA256(info="AuthVerifier")      = authVerifier 32 B   → serwer dostaje SHA-256
 *     → HKDF-SHA256(info="MasterKey KEK")     = KEK 32 B
 *   masterKey 32 B losowy
 *     → AES-GCM(KEK, iv 12 B)                 = wrappedMasterKey 48 B
 * </pre>
 *
 * <p>Serwer przyjmuje wyłącznie poprawnie zbudowany pakiet. Dzięki temu błędna
 * implementacja po stronie klienta zostaje wykryta przy rejestracji, a nie dopiero
 * przy próbie odszyfrowania plików.
 */
public final class CryptoPackage {

    public static final int AUTH_VERIFIER_BYTES = 32;
    public static final int PASSWORD_SALT_BYTES = 16;
    public static final int MIN_WRAPPED_KEY_BYTES = 48;
    public static final int IV_BYTES = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoPackage() {
    }

    /**
     * Sprawdza, czy pakiet ma poprawne rozmiary i jest poprawnym base64.
     * Nie sprawdza poprawności hasła — tego serwer zrobić nie może i nie powinien.
     */
    public static boolean isValid(String authVerifier,
                                  String passwordSalt,
                                  String wrappedMasterKey,
                                  String wrappedMasterKeyIv) {
        return hasBytes(authVerifier, AUTH_VERIFIER_BYTES)
                && hasBytes(passwordSalt, PASSWORD_SALT_BYTES)
                && hasAtLeastBytes(wrappedMasterKey, MIN_WRAPPED_KEY_BYTES)
                && hasBytes(wrappedMasterKeyIv, IV_BYTES);
    }

    /** SHA-256(weryfikator) w base64 — to trafia do bazy. */
    public static String hashAuthVerifier(String authVerifier) {
        return Base64.getEncoder().encodeToString(sha256(decodeBase64(authVerifier)));
    }

    /** Porównanie w czasie stałym — nie zdradza, ile początkowych bajtów się zgadza. */
    public static boolean verifierMatches(String authVerifier, String storedHash) {
        try {
            byte[] expected = Base64.getDecoder().decode(storedHash);
            byte[] actual = sha256(decodeBase64(authVerifier));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Sól z 16 losowych bajtów, base64. */
    public static String newSaltBase64() {
        byte[] salt = new byte[PASSWORD_SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** SHA-256 w postaci hex — używane do skrótu tokenu sesji. */
    public static String sha256Hex(String value) {
        return toHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean hasBytes(String base64Value, int expected) {
        try {
            return decodeBase64(base64Value).length == expected;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean hasAtLeastBytes(String base64Value, int minimum) {
        try {
            return decodeBase64(base64Value).length >= minimum;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] decodeBase64(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Pusta wartosc base64");
        }
        return Base64.getDecoder().decode(value.trim());
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Brak algorytmu SHA-256", exception);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
