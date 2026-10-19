/*
 * SkyVault — kryptografia po stronie klienta.
 *
 * Cały ten plik wykonuje się w przeglądarce. Serwer nigdy nie widzi hasła,
 * klucza głównego ani jawnych nazw plików.
 *
 * Schemat (przeniesiony ze starego projektu SkyVault):
 *
 *   hasło
 *     → Argon2id(sól 16 B, 256 MB)        = seed 32 B
 *     → HKDF-SHA256("AuthVerifier")       = authVerifier 32 B  → serwer dostaje SHA-256
 *     → HKDF-SHA256("MasterKey KEK")      = KEK 32 B
 *   masterKey 32 B (losowy)
 *     → AES-GCM(KEK, iv 12 B)             = wrappedMasterKey 48 B
 *
 * Plik:  "SKYVAULT-AESGCM-v1\n" || IV (12 B) || AES-GCM(masterKey, treść)
 * Ścieżka: AES-GCM(masterKey, ścieżka), IV osobno w metadanych
 * Skrót ścieżki: HMAC-SHA256(masterKey, "SkyVault path v1:" + ścieżka), hex
 */
(function () {
  'use strict';

  const encoder = new TextEncoder();
  const decoder = new TextDecoder();

  const BLOB_MAGIC = 'SKYVAULT-AESGCM-v1\n';
  const HKDF_SALT = 'SkyVault HKDF v1';
  const ARGON2_OUT_BYTES = 32;
  const ARGON2_MEM_BYTES = 256 * 1024 * 1024;

  const MASTER_KEY_STORAGE = 'skyvaultMasterKey';
  const EMAIL_STORAGE = 'skyvaultEmail';

  // --- Kodowanie ---

  function toBase64(bytes) {
    let binary = '';
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(binary);
  }

  function fromBase64(value) {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }

  function toHex(bytes) {
    return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  }

  function concat(parts) {
    let total = 0;
    for (const part of parts) {
      total += part.length;
    }
    const result = new Uint8Array(total);
    let offset = 0;
    for (const part of parts) {
      result.set(part, offset);
      offset += part.length;
    }
    return result;
  }

  function startsWith(bytes, prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (let i = 0; i < prefix.length; i += 1) {
      if (bytes[i] !== prefix[i]) {
        return false;
      }
    }
    return true;
  }

  // --- Klucze z hasła ---

  async function ensureSodium() {
    if (!window.sodium) {
      throw new Error('Biblioteka kryptograficzna nie została wczytana.');
    }
    await window.sodium.ready;
    return window.sodium;
  }

  async function hkdf(seed, info) {
    const key = await crypto.subtle.importKey('raw', seed, 'HKDF', false, ['deriveBits']);
    const bits = await crypto.subtle.deriveBits({
      name: 'HKDF',
      hash: 'SHA-256',
      salt: encoder.encode(HKDF_SALT),
      info: encoder.encode(info)
    }, key, 256);
    return new Uint8Array(bits);
  }

  async function deriveKeys(password, salt) {
    const sodium = await ensureSodium();
    const seed = sodium.crypto_pwhash(
      ARGON2_OUT_BYTES,
      password,
      salt,
      sodium.crypto_pwhash_OPSLIMIT_INTERACTIVE,
      ARGON2_MEM_BYTES,
      sodium.crypto_pwhash_ALG_ARGON2ID13
    );

    return {
      authVerifier: await hkdf(seed, 'SkyVault AuthVerifier v1'),
      kek: await hkdf(seed, 'SkyVault MasterKey KEK v1')
    };
  }

  async function wrapMasterKey(kek, masterKey) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const key = await crypto.subtle.importKey('raw', kek, 'AES-GCM', false, ['encrypt']);
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: iv }, key, masterKey));
    return { ciphertext: ciphertext, iv: iv };
  }

  async function unwrapMasterKey(kek, ciphertext, iv) {
    const key = await crypto.subtle.importKey('raw', kek, 'AES-GCM', false, ['decrypt']);
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: iv }, key, ciphertext);
    return new Uint8Array(plaintext);
  }

  // --- Klucz główny w tej sesji ---

  function storeMasterKey(email, masterKey) {
    sessionStorage.setItem(MASTER_KEY_STORAGE, toBase64(masterKey));
    sessionStorage.setItem(EMAIL_STORAGE, email);
  }

  function loadMasterKey() {
    const value = sessionStorage.getItem(MASTER_KEY_STORAGE);
    return value ? fromBase64(value) : null;
  }

  function storedEmail() {
    return sessionStorage.getItem(EMAIL_STORAGE) || '';
  }

  function clearMasterKey() {
    sessionStorage.removeItem(MASTER_KEY_STORAGE);
    sessionStorage.removeItem(EMAIL_STORAGE);
  }

  async function vaultKey(usages) {
    const masterKey = loadMasterKey();
    if (!masterKey) {
      throw new Error('Brak klucza w tej sesji. Zaloguj się ponownie.');
    }
    return crypto.subtle.importKey('raw', masterKey, 'AES-GCM', false, usages);
  }

  // --- Pliki ---

  async function encryptBlob(arrayBuffer) {
    const key = await vaultKey(['encrypt']);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: iv }, key, arrayBuffer));
    const header = encoder.encode(BLOB_MAGIC);
    return new Blob([concat([header, iv, ciphertext])], { type: 'application/octet-stream' });
  }

  async function decryptBlob(arrayBuffer) {
    const bytes = new Uint8Array(arrayBuffer);
    const header = encoder.encode(BLOB_MAGIC);
    const key = await vaultKey(['decrypt']);

    if (startsWith(bytes, header)) {
      const iv = bytes.slice(header.length, header.length + 12);
      const ciphertext = bytes.slice(header.length + 12);
      return new Uint8Array(await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: iv }, key, ciphertext));
    }

    // Zgodność z plikami zapisanymi w formacie "IV || szyfrogram".
    const iv = bytes.slice(0, 12);
    const ciphertext = bytes.slice(12);
    return new Uint8Array(await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: iv }, key, ciphertext));
  }

  // --- Ścieżki ---

  async function encryptPath(path) {
    const key = await vaultKey(['encrypt']);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: iv }, key, encoder.encode(path)));

    return {
      encryptedVirtualPath: toBase64(ciphertext),
      virtualPathIv: toBase64(iv),
      pathHash: await pathHash(path)
    };
  }

  async function decryptPath(encryptedVirtualPath, iv) {
    const key = await vaultKey(['decrypt']);
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: fromBase64(iv) },
      key,
      fromBase64(encryptedVirtualPath));
    return decoder.decode(plaintext);
  }

  async function pathHash(path) {
    const masterKey = loadMasterKey();
    if (!masterKey) {
      throw new Error('Brak klucza w tej sesji. Zaloguj się ponownie.');
    }
    const key = await crypto.subtle.importKey(
      'raw', masterKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    const signature = new Uint8Array(await crypto.subtle.sign(
      'HMAC', key, encoder.encode('SkyVault path v1:' + path)));
    return toHex(signature);
  }

  // --- Karta odzyskiwania ---

  function recoverySheet(email, masterKey) {
    const text = [
      'SkyVault — karta odzyskiwania',
      'E-mail: ' + email,
      'Klucz główny (base64): ' + toBase64(masterKey),
      'Utworzono: ' + new Date().toISOString(),
      '',
      'Zachowaj ten plik offline. Każdy, kto ma ten klucz, może odszyfrować Twój dysk.',
      'SkyVault nie potrafi go odzyskać, jeśli zostanie utracony.'
    ].join('\n');
    return new Blob([text], { type: 'text/plain;charset=utf-8' });
  }

  window.SkyVaultCrypto = {
    ensureSodium: ensureSodium,
    deriveKeys: deriveKeys,
    wrapMasterKey: wrapMasterKey,
    unwrapMasterKey: unwrapMasterKey,
    storeMasterKey: storeMasterKey,
    loadMasterKey: loadMasterKey,
    storedEmail: storedEmail,
    clearMasterKey: clearMasterKey,
    encryptBlob: encryptBlob,
    decryptBlob: decryptBlob,
    encryptPath: encryptPath,
    decryptPath: decryptPath,
    pathHash: pathHash,
    recoverySheet: recoverySheet,
    toBase64: toBase64,
    fromBase64: fromBase64
  };
})();
