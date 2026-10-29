package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.common.config.JwtConfig;
import com.resalemarket.common.config.JwtProperties;
import com.resalemarket.user.dto.UserResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AccessTokenServiceTest {

    private final JwtProperties properties = new JwtProperties(
            "unit-test-secret-which-is-long-enough-for-hs256", "resale-market-api", Duration.ofMinutes(15));
    private final JwtConfig config = new JwtConfig();
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final AccessTokenService service = new AccessTokenService(
            config.jwtEncoder(properties), properties, Clock.fixed(now, ZoneOffset.UTC));

    private final UserResponse user = new UserResponse(
            UUID.randomUUID(), "sita@example.com", "Sita", null, now, List.of("ROLE_ADMIN", "ROLE_USER"));

    @Test
    void theTokenIdentifiesTheUserAndCarriesTheirRoles() {
        AccessTokenService.AccessToken token = service.issue(user);

        Jwt jwt = config.jwtDecoder(properties).decode(token.value());
        assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("resale-market-api");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void theTokenExpiresAfterTheConfiguredLifetime() {
        AccessTokenService.AccessToken token = service.issue(user);

        Jwt jwt = config.jwtDecoder(properties).decode(token.value());
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(token.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void theTokenHoldsNoPersonalDataBeyondTheUserId() {
        Jwt jwt = config.jwtDecoder(properties).decode(service.issue(user).value());

        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "jti", "roles");
        assertThat(jwt.getTokenValue()).doesNotContain("sita");
    }

    @Test
    void everyTokenHasItsOwnId() {
        String first = config.jwtDecoder(properties).decode(service.issue(user).value()).getId();
        String second = config.jwtDecoder(properties).decode(service.issue(user).value()).getId();

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    @Test
    void theTokenValueIsNotPartOfToString() {
        AccessTokenService.AccessToken token = service.issue(user);

        assertThat(token.toString()).doesNotContain(token.value());
    }
}
