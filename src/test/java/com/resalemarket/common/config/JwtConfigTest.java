package com.resalemarket.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** The access-token rules that protect every endpoint: only our key, our issuer, our algorithm, and not expired. */
class JwtConfigTest {

    private static final String SECRET = "unit-test-secret-which-is-long-enough-for-hs256";
    private static final String ISSUER = "resale-market-api";

    private final JwtProperties properties = new JwtProperties(SECRET, ISSUER, Duration.ofMinutes(15));
    private final JwtConfig config = new JwtConfig();
    private final JwtEncoder encoder = config.jwtEncoder(properties);
    private final JwtDecoder decoder = config.jwtDecoder(properties);

    private static String sign(JwtEncoder with, String issuer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("7c9e6679-7425-40de-944b-e07fc1f90ae7")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(JwtConfig.ROLES_CLAIM, List.of("ROLE_USER"))
                .build();
        return with.encode(JwtEncoderParameters.from(JwsHeader.with(JwtConfig.ALGORITHM).build(), claims)).getTokenValue();
    }

    private String validToken() {
        Instant now = Instant.now();
        return sign(encoder, ISSUER, now, now.plus(Duration.ofMinutes(15)));
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aTokenWeSignedIsAccepted() {
        Jwt jwt = decoder.decode(validToken());

        assertThat(jwt.getSubject()).isEqualTo("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        assertThat(jwt.getClaimAsStringList(JwtConfig.ROLES_CLAIM)).containsExactly("ROLE_USER");
    }

    @Test
    void anExpiredTokenIsRejected() {
        Instant now = Instant.now();
        String expired = sign(encoder, ISSUER, now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(10)));

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() {
        Instant now = Instant.now();
        String foreign = sign(encoder, "someone-else", now, now.plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        byte[] otherKey = "a-completely-different-secret-of-sufficient-length".getBytes(StandardCharsets.UTF_8);
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        Instant now = Instant.now();
        String forged = sign(attacker, ISSUER, now, now.plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void aTokenWhosePayloadWasEditedAfterSigningIsRejected() {
        String[] parts = validToken().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("ROLE_USER", "ROLE_ADMIN");
        String tampered = parts[0] + "." + base64Url(payload) + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void anUnsignedTokenIsRejected() {
        long exp = Instant.now().plus(Duration.ofMinutes(15)).getEpochSecond();
        String unsigned = base64Url("{\"alg\":\"none\"}") + "."
                + base64Url("{\"iss\":\"" + ISSUER + "\",\"sub\":\"x\",\"exp\":" + exp + ",\"roles\":[\"ROLE_ADMIN\"]}") + ".";

        assertThatThrownBy(() -> decoder.decode(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void theRolesClaimBecomesGrantedAuthoritiesWithoutAnExtraPrefix() {
        Jwt jwt = decoder.decode(validToken());

        // Spring Security 7 also adds FACTOR_BEARER, which records how the caller authenticated; it is not a role.
        assertThat(config.jwtAuthenticationConverter().convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_USER")
                .doesNotContain("ROLE_ADMIN", "ROLE_ROLE_USER");
    }
}
