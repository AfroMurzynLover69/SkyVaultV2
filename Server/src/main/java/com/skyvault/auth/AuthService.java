package com.skyvault.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.skyvault.error.ApiException;
import com.skyvault.user.AppUser;
import com.skyvault.user.AppUserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rejestracja i logowanie.
 *
 * <p>Serwer nigdy nie widzi hasła. Widzi tylko weryfikator, z którego i tak liczy
 * SHA-256 przed zapisem. Logowanie polega na porównaniu skrótów w czasie stałym.
 *
 * <p><b>Ochrona przed ujawnieniem kont:</b> endpoint {@code /api/auth/crypto} zwraca
 * poprawny kształtem materiał kryptograficzny także dla nieistniejącego adresu
 * (sól wyliczona z sekretu serwera). Dzięki temu atakujący nie może ustalić, które
 * adresy mają konto, odpytując ten endpoint.
 */
@Service
public class AuthService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@/\\\\]+@[^\\s@/\\\\]+\\.[^\\s@/\\\\]+$");

    private final AppUserRepository userRepository;

    private final long defaultQuotaBytes;
    private final SecureRandom random = new SecureRandom();
    private final byte[] fakeSaltSecret = new byte[32];
    private final String dummyVerifierHash;

    public AuthService(AppUserRepository userRepository,
                       @Value("${skyvault.storage.default-quota-gb:5}") long defaultQuotaGb) {
        this.userRepository = userRepository;
        this.defaultQuotaBytes = defaultQuotaGb * 1024L * 1024L * 1024L;

        random.nextBytes(fakeSaltSecret);
        this.dummyVerifierHash = Base64.getEncoder()
                .encodeToString(sha256("skyvault-dummy-verifier".getBytes(StandardCharsets.UTF_8)));
    }

    // --- Rejestracja ---

    /**
     * Tworzy konto od razu. Klient przysyła gotowy pakiet kryptograficzny:
     * sól, weryfikator i klucz główny zapakowany KEK. Serwer nie potrafi
     * odtworzyć z tego ani hasła, ani klucza głównego.
     */
    @Transactional
    public AppUser register(String rawEmail,
                            String authVerifier,
                            String passwordSalt,
                            String wrappedMasterKey,
                            String wrappedMasterKeyIv) {
        String email = normalizeEmail(rawEmail);

        if (!isValidEmail(email)) {
            throw ApiException.badRequest("Nieprawidlowy adres e-mail");
        }
        if (!CryptoPackage.isValid(authVerifier, passwordSalt, wrappedMasterKey, wrappedMasterKeyIv)) {
            throw ApiException.badRequest("Nieprawidlowy pakiet kryptograficzny");
        }
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("Konto z tym adresem juz istnieje");
        }

        AppUser user = new AppUser(
                email,
                CryptoPackage.hashAuthVerifier(authVerifier),
                passwordSalt,
                wrappedMasterKey,
                wrappedMasterKeyIv,
                defaultQuotaBytes);

        return userRepository.save(user);
    }

    // --- Logowanie ---

    @Transactional(readOnly = true)
    public Optional<AppUser> authenticate(String rawEmail, String authVerifier) {
        String email = normalizeEmail(rawEmail);

        if (authVerifier == null || authVerifier.isBlank()) {
            return Optional.empty();
        }

        Optional<AppUser> user = userRepository.findByEmail(email);

        // Porownanie wykonujemy zawsze, takze gdy konto nie istnieje - inaczej czas
        // odpowiedzi zdradzalby, ktore adresy sa zarejestrowane.
        String storedHash = user.map(AppUser::getAuthVerifierHash).orElse(dummyVerifierHash);
        boolean matches = CryptoPackage.verifierMatches(authVerifier, storedHash);

        return (matches && user.isPresent()) ? user : Optional.empty();
    }

    // --- Profil ---

    @Transactional(readOnly = true)
    public Optional<AppUser> profile(Long userId) {
        return userRepository.findById(userId);
    }

    // --- Material kryptograficzny ---

    /**
     * Zwraca sól i zapakowany klucz główny.
     *
     * <p>Dla nieistniejącego adresu zwraca wartości wyliczone z sekretu serwera —
     * poprawne kształtem, ale bezużyteczne. Klient nie odróżni ich od prawdziwych,
     * dopóki nie spróbuje odszyfrować klucza głównego.
     */
    @Transactional(readOnly = true)
    public CryptoMaterial cryptoMaterial(String rawEmail) {
        String email = normalizeEmail(rawEmail);

        return userRepository.findByEmail(email)
                .map(user -> new CryptoMaterial(
                        user.getPasswordSalt(),
                        user.getWrappedMasterKey(),
                        user.getWrappedMasterKeyIv()))
                .orElseGet(() -> fakeMaterialFor(email));
    }

    private CryptoMaterial fakeMaterialFor(String email) {
        byte[] digest = hmacSha256(fakeSaltSecret, ("salt:" + email).getBytes(StandardCharsets.UTF_8));
        byte[] fakeIv = hmacSha256(fakeSaltSecret, ("iv:" + email).getBytes(StandardCharsets.UTF_8));
        byte[] fakeWrapped = hmacSha256(fakeSaltSecret, ("key:" + email).getBytes(StandardCharsets.UTF_8));

        Base64.Encoder encoder = Base64.getEncoder();
        return new CryptoMaterial(
                encoder.encodeToString(slice(digest, CryptoPackage.PASSWORD_SALT_BYTES)),
                encoder.encodeToString(concat(fakeWrapped, slice(fakeIv, 16))),
                encoder.encodeToString(slice(fakeIv, CryptoPackage.IV_BYTES)));
    }

    // --- Pomocnicze ---

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidEmail(String email) {
        return email != null
                && email.length() >= 3
                && email.length() <= 254
                && EMAIL_PATTERN.matcher(email).matches();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception exception) {
            throw new IllegalStateException("Brak algorytmu SHA-256", exception);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception exception) {
            throw new IllegalStateException("Brak algorytmu HmacSHA256", exception);
        }
    }

    private static byte[] slice(byte[] source, int length) {
        byte[] result = new byte[Math.min(length, source.length)];
        System.arraycopy(source, 0, result, 0, result.length);
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    // --- Wyniki ---

    public record CryptoMaterial(String passwordSalt, String wrappedMasterKey, String wrappedMasterKeyIv) {
    }
}
