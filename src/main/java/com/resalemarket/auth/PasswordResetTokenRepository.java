package com.resalemarket.auth;

import com.resalemarket.auth.entity.PasswordResetToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Locks the row, so two requests that use the same link at the same moment are handled one after the other (the second
     * then sees that the first used it) instead of both succeeding.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Removes every link of one person that has not been used yet; returns how many there were. */
    @Modifying
    @Query("delete from PasswordResetToken t where t.userId = :userId and t.usedAt is null")
    int deleteUnusedForUser(@Param("userId") UUID userId);

    /**
     * Like {@link #deleteUnusedForUser} but leaves one link alone: the one being used right now, whose "used" mark is still
     * waiting to be written. Excluding it by id means the outcome does not depend on the order Hibernate flushes in.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.userId = :userId and t.usedAt is null and t.id <> :keepId")
    int deleteUnusedForUserExcept(@Param("userId") UUID userId, @Param("keepId") UUID keepId);
}
