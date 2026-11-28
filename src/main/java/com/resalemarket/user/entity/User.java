package com.resalemarket.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * The login identity: e-mail, password hash, status and roles. Personal details live in {@link UserProfile}.
 * This entity never leaves the {@code user} module; controllers return DTOs, so the hash cannot leak.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Always stored lower-case (see {@link #normaliseEmail}); the database enforces this with a CHECK constraint. */
    @Column(nullable = false, length = 254)
    private String email;

    /** Delegating-encoder format, e.g. {@code {bcrypt}$2a$10$...}. Never returned by the API. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Set by the database ({@code DEFAULT now()}) and read back after the insert. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by the {@code trg_users_updated_at} trigger, never by application code. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Optimistic locking; {@code null} until the first save so Spring Data treats the entity as new. */
    @Version
    private Long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    /** A new, active account holding the given role. */
    public static User register(String email, String passwordHash, Role role) {
        User user = new User();
        user.email = normaliseEmail(email);
        user.passwordHash = passwordHash;
        user.roles.add(role);
        return user;
    }

    /** The one canonical spelling of an e-mail address: trimmed and lower-case, so lookups are case-insensitive. */
    public static String normaliseEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
