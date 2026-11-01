package com.resalemarket.auth;

import com.resalemarket.auth.entity.RefreshToken;
import com.resalemarket.auth.entity.RevocationReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens (see ARCHITECTURE.md, section 6).
 *
 * <ul>
 *   <li>A token is 256 random bits; the database only ever holds its SHA-256 hash.</li>
 *   <li>Using a token <b>rotates</b> it: the old one is revoked and a new one with the same family takes its place.</li>
 *   <li>Presenting a token that was already rotated means two parties hold the same session, so the whole family is
 *       revoked and both are signed out ({@link RevocationReason#REUSE_DETECTED}).</li>
 * </ul>
 *
 * <p>One exception keeps ordinary use working: a token rotated only moments ago (see
 * {@link AuthProperties#refreshReuseGrace()}) is a race between two tabs of the same browser, which share one cookie
 * jar. The tab that lost the race gets a fresh access token but no new refresh token; the winning response has already
 * stored the new cookie.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The result of a successful rotation.
     *
     * @param userId the account the session belongs to
     * @param token  the replacement refresh token, or {@code null} when the request lost a race with another tab (the
     *               cookie must then be left alone)
     */
    public record Rotation(UUID userId, String token) {

        /** Keeps the token out of logs. */
        @Override
        public String toString() {
            return "Rotation[userId=" + userId + "]";
        }
    }

    /** Starts a new session (a new token family) for a login and returns the token to hand to the browser. */
    @Transactional
    public String start(UUID userId, ClientInfo client) {
        return save(userId, UUID.randomUUID(), client, clock.instant()).rawToken();
    }

    /**
     * Exchanges a refresh token for a new one.
     *
     * @return the rotation, or empty when the token is unknown, expired, revoked or was reused; the caller answers 401
     *     in every one of those cases and does not say which it was
     */
    @Transactional
    public Optional<Rotation> rotate(String rawToken, ClientInfo client) {
        Instant now = clock.instant();
        Optional<RefreshToken> found = repository.findByTokenHash(hash(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken current = found.get();

        if (current.isRevoked()) {
            return current.getRevokedReason() == RevocationReason.ROTATED ? reused(current, now) : Optional.empty();
        }
        if (current.isExpiredAt(now)) {
            return Optional.empty();
        }

        Saved successor = save(current.getUserId(), current.getFamilyId(), client, now);
        current.rotate(now, successor.token().getId());
        return Optional.of(new Rotation(current.getUserId(), successor.rawToken()));
    }

    /** Ends the session this token belongs to (sign out). Unknown or already revoked tokens are ignored. */
    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(token ->
                repository.revokeActiveInFamily(token.getFamilyId(), clock.instant(), RevocationReason.LOGOUT));
    }

    /**
     * Ends every session of a person (sign out of all devices). Tokens that were already revoked keep their original
     * reason, so the history of how each session ended is not rewritten.
     *
     * @return how many sessions were still open
     */
    @Transactional
    public int revokeAll(UUID userId) {
        return repository.revokeActiveForUser(userId, clock.instant(), RevocationReason.LOGOUT);
    }

    /** A token that was already rotated came back: a harmless race if it is fresh, theft if it is not. */
    private Optional<Rotation> reused(RefreshToken rotated, Instant now) {
        boolean sessionStillOpen = repository.existsByFamilyIdAndRevokedAtIsNull(rotated.getFamilyId());
        boolean withinGrace = now.isBefore(rotated.getRevokedAt().plus(properties.refreshReuseGrace()));
        if (sessionStillOpen && withinGrace) {
            return Optional.of(new Rotation(rotated.getUserId(), null));
        }
        if (sessionStillOpen) {
            int revoked = repository.revokeActiveInFamily(rotated.getFamilyId(), now, RevocationReason.REUSE_DETECTED);
            log.warn("Refresh token reuse detected for user {}: revoked {} active token(s) of session {}",
                    rotated.getUserId(), revoked, rotated.getFamilyId());
        }
        return Optional.empty();
    }

    private Saved save(UUID userId, UUID familyId, ClientInfo client, Instant now) {
        String rawToken = newRawToken();
        RefreshToken token = repository.saveAndFlush(RefreshToken.issue(
                userId,
                familyId,
                hash(rawToken),
                now,
                now.plus(properties.refreshTokenTtl()),
                client.ipAddress(),
                client.userAgent()));
        return new Saved(token, rawToken);
    }

    private String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 as lower-case hex: 64 characters, the width of {@code refresh_tokens.token_hash}. */
    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime.", ex);
        }
    }

    private record Saved(RefreshToken token, String rawToken) {
    }
}
