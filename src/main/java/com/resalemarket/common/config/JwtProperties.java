package com.resalemarket.common.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token (JWT) settings, bound from {@code app.jwt.*}.
 *
 * <p>Tokens are signed with HMAC-SHA256. HS256 needs a key of at least 256 bits, so a missing or shorter {@code secret}
 * stops the application at startup instead of silently weakening every token. The secret comes from the
 * {@code JWT_SECRET} environment variable and is never committed.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("resale-market-api") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl) {

    /** 256 bits: the minimum key length for HS256 (RFC 7518, section 3.2). */
    static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "app.jwt.secret (JWT_SECRET) must be set and at least %d bytes long. Generate one with: openssl rand -hex 48"
                            .formatted(MIN_SECRET_BYTES));
        }
        if (accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("app.jwt.access-token-ttl must be positive.");
        }
    }

    /** Keeps the signing secret out of logs and actuator output. */
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
