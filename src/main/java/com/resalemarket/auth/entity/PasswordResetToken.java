package com.resalemarket.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Server-side record of one password-reset link. Only the SHA-256 hash of the token is stored, so a leaked table cannot be
 * used to take over accounts. A token works once and only until {@code expiresAt}.
 *
 * <p>The user is referenced by id only: the {@code user} module owns the user entity and this module must not reach into it.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Lower-case hex SHA-256 of the token: a fixed-width CHAR(64) column. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    public static PasswordResetToken issue(UUID userId, String tokenHash, Instant now, Instant expiresAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.userId = userId;
        token.tokenHash = tokenHash;
        token.createdAt = now;
        token.expiresAt = expiresAt;
        return token;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        usedAt = now;
    }
}
