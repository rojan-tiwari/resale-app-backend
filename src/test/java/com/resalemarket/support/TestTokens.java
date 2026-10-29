package com.resalemarket.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Builds access tokens by hand, including broken ones, to test how the API treats them. */
public final class TestTokens {

    public static final String ISSUER = "resale-market-api";

    private TestTokens() {
    }

    /** A token the API would accept, signed by the application's own encoder. */
    public static String valid(JwtEncoder applicationEncoder, String subject, String... roles) {
        Instant now = Instant.now();
        return sign(applicationEncoder, ISSUER, subject, now, now.plus(Duration.ofMinutes(15)), roles);
    }

    public static String sign(
            JwtEncoder encoder, String issuer, String subject, Instant issuedAt, Instant expiresAt, String... roles) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.of(roles))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /** An encoder with a key the application does not know: what an attacker would have. */
    public static JwtEncoder encoderWithAnotherKey() {
        byte[] key = "an-attackers-guess-of-the-signing-key-0123456789".getBytes(StandardCharsets.UTF_8);
        return new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
    }
}
