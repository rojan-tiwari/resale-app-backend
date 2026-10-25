package com.resalemarket.user;

import com.resalemarket.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** {@code email} must already be normalised ({@link User#normaliseEmail}). */
    boolean existsByEmail(String email);

    /** Loads the roles in the same query: the authorities are read after the lookup, outside any lazy-loading session. */
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);
}
