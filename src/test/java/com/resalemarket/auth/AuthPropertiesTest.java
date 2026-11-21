package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resalemarket.common.ratelimit.RateLimit;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AuthPropertiesTest {

    private static AuthProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("app.auth", AuthProperties.class);
    }

    @Test
    void everySettingHasASafeDefault() {
        AuthProperties properties = bind(Map.of());

        assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
        assertThat(properties.maxFailedLogins()).isEqualTo(5);
        assertThat(properties.lockoutDuration()).isEqualTo(Duration.ofMinutes(15));
        AuthProperties.RateLimits limits = properties.rateLimits();
        assertThat(limits.login()).isEqualTo(new RateLimit(20, Duration.ofMinutes(10)));
        assertThat(limits.register()).isEqualTo(new RateLimit(10, Duration.ofHours(1)));
    }

    @Test
    void theYamlNamesBindAndOnlyTheRulesThatAreSetChange() {
        AuthProperties properties = bind(Map.of(
                "app.auth.max-failed-logins", "7",
                "app.auth.lockout-duration", "1h",
                "app.auth.rate-limits.register.attempts", "2",
                "app.auth.rate-limits.register.window", "30m",
                "app.auth.rate-limits.login.attempts", "50",
                "app.auth.rate-limits.login.window", "1m"));

        assertThat(properties.maxFailedLogins()).isEqualTo(7);
        assertThat(properties.lockoutDuration()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.rateLimits().register()).isEqualTo(new RateLimit(2, Duration.ofMinutes(30)));
        assertThat(properties.rateLimits().login()).isEqualTo(new RateLimit(50, Duration.ofMinutes(1)));
        assertThat(properties.rateLimits().login().attempts()).isEqualTo(50);   // the other rule is untouched
    }

    @Test
    void nonsenseIsRejectedAtStartup() {
        assertThatThrownBy(() -> bind(Map.of("app.auth.max-failed-logins", "0"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("app.auth.lockout-duration", "0s"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        // A rule with a count but no window is incomplete, not "unlimited".
        assertThatThrownBy(() -> bind(Map.of("app.auth.rate-limits.login.attempts", "5")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
