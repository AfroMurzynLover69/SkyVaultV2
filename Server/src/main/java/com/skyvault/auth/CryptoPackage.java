package com.skyvault.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class CryptoPackage {

    public static final int AUTH_VERIFIER_BYTES = 32;
    public static final int PASSWORD_SALT_BYTES = 16;
    public static final int MIN_WRAPPED_KEY_BYTES = 48;
    public static final int IV_BYTES = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoPackage() {
    }

    public static boolean isValid(String authVerifier,
                                  String passwordSalt,
                                  String wrappedMasterKey,
                                  String wrappedMasterKeyIv) {
        return hasBytes(authVerifier, AUTH_VERIFIER_BYTES)
                && hasBytes(passwordSalt, PASSWORD_SALT_BYTES)
                && hasAtLeastBytes(wrappedMasterKey, MIN_WRAPPED_KEY_BYTES)
                && hasBytes(wrappedMasterKeyIv, IV_BYTES);
    }

    public static String hashAuthVerifier(String authVerifier) {
        return Base64.getEncoder().encodeToString(sha256(decodeBase64(authVerifier)));
    }

    public static boolean verifierMatches(String authVerifier, String storedHash) {
        try {
            byte[] expected = Base64.getDecoder().decode(storedHash);
            byte[] actual = sha256(decodeBase64(authVerifier));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static String newSaltBase64() {
        byte[] salt = new byte[PASSWORD_SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

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
            throw new IllegalArgumentException("Empty base64 value");
        }
        return Base64.getDecoder().decode(value.trim());
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm not available", exception);
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
