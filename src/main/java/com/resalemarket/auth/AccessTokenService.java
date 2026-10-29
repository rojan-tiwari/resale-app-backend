package com.resalemarket.auth;

import com.resalemarket.common.config.JwtConfig;
import com.resalemarket.common.config.JwtProperties;
import com.resalemarket.user.dto.UserResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Creates the short-lived, signed access token that the browser sends as {@code Authorization: Bearer ...}.
 *
 * <p>The token carries only what authorisation needs: who ({@code sub}, the user id) and which roles. Anything else is
 * read from the database, so a profile change never leaves stale data inside a token.
 */
@Service
public class AccessTokenService {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public AccessTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param value            the compact JWT
     * @param expiresInSeconds how long it is valid, counted from now
     */
    public record AccessToken(String value, long expiresInSeconds) {

        /** Keeps the token out of logs. */
        @Override
        public String toString() {
            return "AccessToken[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }

    public AccessToken issue(UserResponse user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim(JwtConfig.ROLES_CLAIM, user.roles())
                .build();
        String token = encoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(JwtConfig.ALGORITHM).build(), claims))
                .getTokenValue();
        return new AccessToken(token, properties.accessTokenTtl().toSeconds());
    }
}
