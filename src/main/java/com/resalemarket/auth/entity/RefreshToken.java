package com.resalemarket.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Server-side record of one issued refresh token. Only the SHA-256 hash of the token is stored, never the token, so a
 * leaked table cannot be used to sign in. All tokens that descend from one login share a {@code familyId}.
 *
 * <p>The user is referenced by id only: the {@code user} module owns the user entity and this module must not reach into it.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    /** Lower-case hex SHA-256 of the token: a fixed-width CHAR(64) column. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 30)
    private RevocationReason revokedReason;

    /** The token that took this one's place when it was rotated. */
    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    public static RefreshToken issue(
            UUID userId, UUID familyId, String tokenHash, Instant now, Instant expiresAt, String ipAddress, String userAgent) {
        RefreshToken token = new RefreshToken();
        token.userId = userId;
        token.familyId = familyId;
        token.tokenHash = tokenHash;
        token.issuedAt = now;
        token.expiresAt = expiresAt;
        token.ipAddress = ipAddress;
        token.userAgent = userAgent;
        return token;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /** Ends this token because {@code successor} replaces it. */
    public void rotate(Instant now, UUID successorId) {
        revoke(RevocationReason.ROTATED, now);
        replacedById = successorId;
    }

    public void revoke(RevocationReason reason, Instant now) {
        revokedAt = now;
        revokedReason = reason;
    }
}
