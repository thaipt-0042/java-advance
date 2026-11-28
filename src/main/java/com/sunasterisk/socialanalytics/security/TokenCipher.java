package com.sunasterisk.socialanalytics.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for the stored OAuth2 tokens ({@code docs/cau-hoi-lam-ro.md} Q8). Each value gets a fresh random
 * 12-byte IV, stored in front of the ciphertext; GCM's tag makes a wrong key or an altered value fail instead of
 * decrypting to garbage.
 */
public final class TokenCipher {

    static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private TokenCipher(SecretKey key) {
        this.key = key;
    }

    /**
     * @param base64Key 32 bytes in Base64 ({@code openssl rand -base64 32})
     * @throws IllegalArgumentException not Base64, or not 32 bytes
     */
    public static TokenCipher fromBase64Key(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Token encryption key is not valid Base64");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Token encryption key must be %d bytes (got %d)".formatted(KEY_BYTES, bytes.length));
        }
        return new TokenCipher(new SecretKeySpec(bytes, "AES"));
    }

    public byte[] encrypt(byte[] plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_BYTES + encrypted.length).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot encrypt a token", ex);
        }
    }

    /** @throws TokenDecryptionException another key, or an altered or truncated value */
    public byte[] decrypt(byte[] stored) {
        if (stored.length <= IV_BYTES) {
            throw new TokenDecryptionException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(stored, 0, IV_BYTES)));
            return cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
        } catch (GeneralSecurityException ex) {
            throw new TokenDecryptionException();
        }
    }

    /** The stored token can't be read with this key: the user just has to log in again. */
    public static final class TokenDecryptionException extends RuntimeException {

        TokenDecryptionException() {
            super("Stored token cannot be decrypted with the current key", null, false, false);
        }
    }
}
