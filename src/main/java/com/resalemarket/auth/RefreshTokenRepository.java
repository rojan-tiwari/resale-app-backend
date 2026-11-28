package com.resalemarket.auth;

import com.resalemarket.auth.entity.RefreshToken;
import com.resalemarket.auth.entity.RevocationReason;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Locks the row, so two requests that present the same token at the same moment are handled one after the other
     * (the second then sees that the first already rotated it) instead of both succeeding.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    boolean existsByFamilyIdAndRevokedAtIsNull(UUID familyId);

    /** Revokes every token of the family that is still valid; returns how many there were. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeActiveInFamily(
            @Param("familyId") UUID familyId, @Param("now") Instant now, @Param("reason") RevocationReason reason);
}
