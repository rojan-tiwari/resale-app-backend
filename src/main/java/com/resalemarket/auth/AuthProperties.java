package com.resalemarket.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Refresh-token settings, bound from {@code app.auth.*}. (The access token has its own settings in
 * {@link com.resalemarket.common.config.JwtProperties}.)
 *
 * @param refreshTokenTtl     how long a refresh token stays valid; every rotation starts a new period, so a person who
 *                            keeps coming back stays signed in
 * @param refreshReuseGrace   a refresh token that was rotated less than this long ago is treated as a harmless race
 *                            (two browser tabs restoring the session at the same moment) instead of as theft; {@code 0s}
 *                            makes every reuse count as theft
 * @param refreshCookie       how the refresh token travels to the browser
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @DefaultValue("14d") Duration refreshTokenTtl,
        @DefaultValue("10s") Duration refreshReuseGrace,
        @DefaultValue RefreshCookie refreshCookie) {

    public AuthProperties {
        if (refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()) {
            throw new IllegalArgumentException("app.auth.refresh-token-ttl must be positive.");
        }
        if (refreshReuseGrace.isNegative()) {
            throw new IllegalArgumentException("app.auth.refresh-reuse-grace must not be negative.");
        }
    }

    /**
     * @param name     cookie name
     * @param secure   send the cookie over HTTPS only; switched off in the dev profile because the dev server is plain HTTP
     * @param sameSite {@code Strict}, {@code Lax} or {@code None}; Strict is right while the SPA and the API share a site
     * @param path     the cookie is only sent to this path, i.e. to the authentication endpoints and nowhere else
     */
    public record RefreshCookie(
            @DefaultValue("refresh_token") String name,
            @DefaultValue("true") boolean secure,
            @DefaultValue("Strict") String sameSite,
            @DefaultValue("/api/v1/auth") String path) {
    }
}
