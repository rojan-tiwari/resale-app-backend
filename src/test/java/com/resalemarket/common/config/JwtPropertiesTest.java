package com.resalemarket.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    private static final String SECRET_OF_32_BYTES = "0123456789abcdef0123456789abcdef";

    @Test
    void aSecretOf32BytesIsAccepted() {
        JwtProperties properties = new JwtProperties(SECRET_OF_32_BYTES, "issuer", Duration.ofMinutes(15));

        assertThat(properties.secret()).isEqualTo(SECRET_OF_32_BYTES);
    }

    @Test
    void aMissingSecretStopsStartup() {
        assertThatThrownBy(() -> new JwtProperties(null, "issuer", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void aSecretShorterThan32BytesStopsStartup() {
        assertThatThrownBy(() -> new JwtProperties(SECRET_OF_32_BYTES.substring(1), "issuer", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void theLengthIsMeasuredInBytesNotCharacters() {
        // 11 Devanagari letters are 11 characters but 33 bytes: long enough. 10 are 30 bytes: too short.
        assertThat(new JwtProperties("क".repeat(11), "issuer", Duration.ofMinutes(15))).isNotNull();
        assertThatThrownBy(() -> new JwtProperties("क".repeat(10), "issuer", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAccessTokenLifetimeMustBePositive() {
        assertThatThrownBy(() -> new JwtProperties(SECRET_OF_32_BYTES, "issuer", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access-token-ttl");
    }

    @Test
    void theSecretIsNotPartOfToString() {
        JwtProperties properties = new JwtProperties(SECRET_OF_32_BYTES, "issuer", Duration.ofMinutes(15));

        assertThat(properties.toString()).doesNotContain(SECRET_OF_32_BYTES).contains("issuer");
    }
}
