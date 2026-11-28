package com.resalemarket.user;

import com.resalemarket.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** {@code email} must already be normalised ({@link User#normaliseEmail}). */
    boolean existsByEmail(String email);

    /** Loads the roles in the same query: the authorities are read after the lookup, outside any lazy-loading session. */
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesById(UUID id);

    /**
     * The account locked for update, so wrong-password counts and lock-outs of one account are applied one after the other
     * even when several sign-in attempts arrive at once. The roles are deliberately not fetched: PostgreSQL cannot lock
     * the nullable side of the outer join an entity graph would add.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findWithLockByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findWithLockById(UUID id);
}
