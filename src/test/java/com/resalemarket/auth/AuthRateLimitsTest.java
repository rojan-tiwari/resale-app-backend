package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resalemarket.common.exception.TooManyRequestsException;
import com.resalemarket.common.ratelimit.RateLimit;
import com.resalemarket.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuthRateLimitsTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final AuthRateLimits limits = new AuthRateLimits(properties(), clock);

    private static AuthProperties properties() {
        return new AuthProperties(
                Duration.ofDays(14),
                Duration.ofSeconds(10),
                new AuthProperties.RefreshCookie("refresh_token", true, "Strict", "/api/v1/auth"),
                5,
                Duration.ofMinutes(15),
                new AuthProperties.RateLimits(
                        new RateLimit(2, WINDOW),    // login
                        new RateLimit(2, WINDOW)));  // register
    }

    private static ClientInfo from(String ip) {
        return new ClientInfo(ip, "Mozilla/5.0");
    }

    @Test
    void signInIsLimitedPerIpAddress() {
        limits.login(from("203.0.113.7"));
        limits.login(from("203.0.113.7"));

        assertThatThrownBy(() -> limits.login(from("203.0.113.7"))).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limits.login(from("198.51.100.9"))).doesNotThrowAnyException();
    }

    @Test
    void eachActionHasItsOwnCountSoSigningInDoesNotUseUpRegistrations() {
        limits.login(from("203.0.113.7"));
        limits.login(from("203.0.113.7"));

        assertThatCode(() -> limits.register(from("203.0.113.7"))).doesNotThrowAnyException();
    }

    @Test
    void registrationHasItsOwnLimit() {
        limits.register(from("203.0.113.7"));
        limits.register(from("203.0.113.7"));

        assertThatThrownBy(() -> limits.register(from("203.0.113.7"))).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void aMissingAddressIsOneSharedBucketNotAFreePass() {
        limits.login(from(null));
        limits.login(from(null));

        assertThatThrownBy(() -> limits.login(from(null))).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void theLimitsEndWithTheirWindow() {
        limits.login(from("203.0.113.7"));
        limits.login(from("203.0.113.7"));
        clock.advance(WINDOW);

        assertThatCode(() -> limits.login(from("203.0.113.7"))).doesNotThrowAnyException();
    }
}
