package com.skyvault.auth;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoPackageTests {

    private static String bytes(int length) {
        return bytes(length, 0);
    }

    private static String bytes(int length, int value) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) value);
        return Base64.getEncoder().encodeToString(data);
    }

    @Test
    void acceptsA32ByteRecoveryVerifier() {
        assertThat(CryptoPackage.isValidRecoveryVerifier(bytes(32))).isTrue();
    }

    @Test
    void rejectsMalformedRecoveryVerifiers() {
        assertThat(CryptoPackage.isValidRecoveryVerifier(null)).isFalse();
        assertThat(CryptoPackage.isValidRecoveryVerifier("")).isFalse();
        assertThat(CryptoPackage.isValidRecoveryVerifier("not base64!")).isFalse();
        assertThat(CryptoPackage.isValidRecoveryVerifier(bytes(31))).isFalse();
        assertThat(CryptoPackage.isValidRecoveryVerifier(bytes(33))).isFalse();
    }

    @Test
    void recoveryVerifierCanBeHashedAndMatchedWithoutStoringTheSecret() {
        String verifier = bytes(32, 1);
        String hash = CryptoPackage.hashVerifier(verifier);

        assertThat(hash).isNotEqualTo(verifier);
        assertThat(CryptoPackage.verifierMatches(verifier, hash)).isTrue();
        assertThat(CryptoPackage.verifierMatches(bytes(32, 2), hash)).isFalse();
    }
}
