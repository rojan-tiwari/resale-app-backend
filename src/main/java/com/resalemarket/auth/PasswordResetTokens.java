package com.resalemarket.auth;

import com.resalemarket.auth.entity.PasswordResetToken;
import com.resalemarket.common.exception.InvalidTokenException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and uses up password-reset tokens. A token is 256 random bits; the database only holds its SHA-256 hash, it works
 * once, and only until {@link AuthProperties#passwordResetTtl()} has passed. At most one link is live per person: asking
 * for a new one cancels the older ones, and using one cancels the rest.
 */
@Service
public class PasswordResetTokens {

    static final String INVALID_LINK = "This password reset link is invalid or has expired. Please request a new one.";

    private final PasswordResetTokenRepository repository;
    private final AuthProperties properties;
    private final Clock clock;

    public PasswordResetTokens(PasswordResetTokenRepository repository, AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Creates a link token for this person, cancelling their earlier unused ones, and returns it (the only time it exists in the clear). */
    @Transactional
    public String issue(UUID userId) {
        repository.deleteUnusedForUser(userId);
        String rawToken = OpaqueTokens.newToken();
        Instant now = clock.instant();
        repository.saveAndFlush(PasswordResetToken.issue(
                userId, OpaqueTokens.sha256Hex(rawToken), now, now.plus(properties.passwordResetTtl())));
        return rawToken;
    }

    /**
     * Uses the token up and returns the person it belongs to.
     *
     * @throws InvalidTokenException if it is unknown, was already used, or has expired (the three are not told apart)
     */
    @Transactional
    public UUID consume(String rawToken) {
        Instant now = clock.instant();
        PasswordResetToken token = repository.findByTokenHash(OpaqueTokens.sha256Hex(rawToken))
                .filter(found -> !found.isUsed() && !found.isExpiredAt(now))
                .orElseThrow(PasswordResetTokens::invalid);
        token.markUsed(now);
        repository.deleteUnusedForUserExcept(token.getUserId(), token.getId());
        return token.getUserId();
    }

    static InvalidTokenException invalid() {
        return new InvalidTokenException(INVALID_LINK);
    }
}
