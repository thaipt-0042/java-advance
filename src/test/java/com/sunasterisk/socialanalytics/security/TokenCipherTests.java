package com.sunasterisk.socialanalytics.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenCipherTests {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String OTHER_KEY = Base64.getEncoder().encodeToString(filled(32, (byte) 7));

    private final TokenCipher cipher = TokenCipher.fromBase64Key(KEY);

    @Test
    void decryptsWhatItEncryptedWithAFreshIvEachTime() {
        byte[] token = "EAAB-secret-token".getBytes(StandardCharsets.UTF_8);

        byte[] first = cipher.encrypt(token);
        byte[] second = cipher.encrypt(token);

        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain("secret");
        assertThat(cipher.decrypt(first)).isEqualTo(token);
        assertThat(cipher.decrypt(second)).isEqualTo(token);
        // IV (12) + ciphertext (same length as the token) + tag (16)
        assertThat(first).hasSize(12 + token.length + 16);
    }

    @Test
    void anotherKeyOrAnAlteredValueFailsInsteadOfGivingGarbage() {
        byte[] stored = cipher.encrypt("token".getBytes(StandardCharsets.UTF_8));
        byte[] altered = Arrays.copyOf(stored, stored.length);
        altered[stored.length - 1] ^= 1;

        assertThatThrownBy(() -> TokenCipher.fromBase64Key(OTHER_KEY).decrypt(stored))
                .isInstanceOf(TokenCipher.TokenDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(altered)).isInstanceOf(TokenCipher.TokenDecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[5])).isInstanceOf(TokenCipher.TokenDecryptionException.class);
    }

    @Test
    void theKeyMustBe32BytesOfBase64() {
        assertThatThrownBy(() -> TokenCipher.fromBase64Key("not base64!"))
                .hasMessage("Token encryption key is not valid Base64");
        assertThatThrownBy(() -> TokenCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessage("Token encryption key must be 32 bytes (got 16)");
    }

    private static byte[] filled(int size, byte value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
