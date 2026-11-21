package com.resalemarket.auth;

import com.resalemarket.common.ratelimit.RateLimit;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings, bound from {@code app.auth.*}. (The access token has its own settings in
 * {@link com.resalemarket.common.config.JwtProperties}.)
 *
 * @param refreshTokenTtl     how long a refresh token stays valid; every rotation starts a new period, so a person who
 *                            keeps coming back stays signed in
 * @param refreshReuseGrace   a refresh token that was rotated less than this long ago is treated as a harmless race
 *                            (two browser tabs restoring the session at the same moment) instead of as theft; {@code 0s}
 *                            makes every reuse count as theft
 * @param refreshCookie       how the refresh token travels to the browser
 * @param passwordResetTtl    how long the link in a password-reset e-mail works
 * @param maxFailedLogins     this many wrong passwords in a row lock the account
 * @param lockoutDuration     how long a locked account refuses to sign in (a reset of the password lifts the lock at once)
 * @param rateLimits          how often the public authentication endpoints may be called
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @DefaultValue("14d") Duration refreshTokenTtl,
        @DefaultValue("10s") Duration refreshReuseGrace,
        @DefaultValue RefreshCookie refreshCookie,
        @DefaultValue("1h") Duration passwordResetTtl,
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("15m") Duration lockoutDuration,
        @DefaultValue RateLimits rateLimits) {

    public AuthProperties {
        if (refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()) {
            throw new IllegalArgumentException("app.auth.refresh-token-ttl must be positive.");
        }
        if (refreshReuseGrace.isNegative()) {
            throw new IllegalArgumentException("app.auth.refresh-reuse-grace must not be negative.");
        }
        if (passwordResetTtl.isZero() || passwordResetTtl.isNegative()) {
            throw new IllegalArgumentException("app.auth.password-reset-ttl must be positive.");
        }
        if (maxFailedLogins < 1) {
            throw new IllegalArgumentException("app.auth.max-failed-logins must be at least 1.");
        }
        if (lockoutDuration.isZero() || lockoutDuration.isNegative()) {
            throw new IllegalArgumentException("app.auth.lockout-duration must be positive.");
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

    /**
     * One rule per protected action; each is counted separately and per caller (see {@link AuthRateLimits} for what the
     * caller is). The defaults leave room for many people behind one address, which is normal for mobile and shared
     * connections in Nepal; tighten them in {@code app.auth.rate-limits.*} when abuse shows up.
     *
     * @param login                a person signing in, per IP address (the per-account guard is the lock-out)
     * @param register             creating accounts, per IP address
     * @param forgotPasswordByIp   asking for reset e-mails, per IP address
     * @param forgotPasswordByEmail reset e-mails to one address, so it cannot be mail-bombed from many IP addresses
     * @param resetPassword        using reset links, per IP address
     */
    public record RateLimits(
            RateLimit login,
            RateLimit register,
            RateLimit forgotPasswordByIp,
            RateLimit forgotPasswordByEmail,
            RateLimit resetPassword) {

        public RateLimits {
            login = login != null ? login : new RateLimit(20, Duration.ofMinutes(10));
            register = register != null ? register : new RateLimit(10, Duration.ofHours(1));
            forgotPasswordByIp = forgotPasswordByIp != null ? forgotPasswordByIp : new RateLimit(10, Duration.ofHours(1));
            forgotPasswordByEmail = forgotPasswordByEmail != null ? forgotPasswordByEmail : new RateLimit(3, Duration.ofHours(1));
            resetPassword = resetPassword != null ? resetPassword : new RateLimit(10, Duration.ofHours(1));
        }
    }
}
