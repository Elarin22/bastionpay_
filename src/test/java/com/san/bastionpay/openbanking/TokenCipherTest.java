package com.san.bastionpay.openbanking;

import com.san.bastionpay.core.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCipherTest {

    private static TokenCipher cipher(String secret) {
        return new TokenCipher(new OpenBankingProperties("b", "c", "s", "r", "", secret, Duration.ofMinutes(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), null));
    }

    @Test
    void roundTripsAndUsesRandomIv() {
        TokenCipher c = cipher("secret-one");
        String a = c.encrypt("access-token");
        String b = c.encrypt("access-token");

        assertThat(a).isNotEqualTo(b).doesNotContain("access-token");
        assertThat(c.decrypt(a)).isEqualTo("access-token");
    }

    @Test
    void wrongKeyOrTamperedValueIsRejected() {
        String encrypted = cipher("secret-one").encrypt("access-token");

        assertThatThrownBy(() -> cipher("secret-two").decrypt(encrypted)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cipher("secret-one").decrypt("AAAA")).isInstanceOf(BusinessException.class);
    }
}
