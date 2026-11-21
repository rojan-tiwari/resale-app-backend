package com.resalemarket.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.user.entity.Role;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The lock-out rules, which live in the {@link User} entity. */
class UserLockoutTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final User user = UserFixtures.withIdAndStatus(
            UserFixtures.user("sita@example.com", UserFixtures.role(Role.USER)), UUID.randomUUID(), UserStatus.ACTIVE);

    @Test
    void wrongPasswordsBelowTheLimitOnlyCount() {
        assertThat(user.registerFailedLogin(NOW, 3, LOCKOUT)).isFalse();
        assertThat(user.registerFailedLogin(NOW, 3, LOCKOUT)).isFalse();

        assertThat(user.getFailedLoginAttempts()).isEqualTo(2);
        assertThat(user.isLockedAt(NOW)).isFalse();
    }

    @Test
    void theAttemptThatReachesTheLimitLocksTheAccountAndStartsTheCountAgain() {
        user.registerFailedLogin(NOW, 3, LOCKOUT);
        user.registerFailedLogin(NOW, 3, LOCKOUT);

        assertThat(user.registerFailedLogin(NOW, 3, LOCKOUT)).isTrue();

        assertThat(user.isLockedAt(NOW)).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(LOCKOUT));
        assertThat(user.getFailedLoginAttempts()).isZero();
    }

    @Test
    void attemptsWhileLockedAreNotCountedAndDoNotExtendTheLock() {
        user.registerFailedLogin(NOW, 1, LOCKOUT);
        Instant lockedUntil = user.getLockedUntil();

        assertThat(user.registerFailedLogin(NOW.plusSeconds(60), 1, LOCKOUT)).isFalse();
        assertThat(user.registerFailedLogin(NOW.plusSeconds(120), 1, LOCKOUT)).isFalse();

        assertThat(user.getLockedUntil()).isEqualTo(lockedUntil);
        assertThat(user.getFailedLoginAttempts()).isZero();
    }

    @Test
    void theLockEndsOnItsOwnAndTheNextWrongPasswordIsCountedAgain() {
        user.registerFailedLogin(NOW, 2, LOCKOUT);
        user.registerFailedLogin(NOW, 2, LOCKOUT);
        Instant after = NOW.plus(LOCKOUT);

        assertThat(user.isLockedAt(after.minusSeconds(1))).isTrue();
        assertThat(user.isLockedAt(after)).isFalse();

        user.registerFailedLogin(after, 2, LOCKOUT);
        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void aSuspendedAccountIsNotCounted() {
        User suspended = UserFixtures.withIdAndStatus(
                UserFixtures.user("sita@example.com", UserFixtures.role(Role.USER)), UUID.randomUUID(), UserStatus.SUSPENDED);

        assertThat(suspended.registerFailedLogin(NOW, 1, LOCKOUT)).isFalse();

        assertThat(suspended.getFailedLoginAttempts()).isZero();
        assertThat(suspended.getLockedUntil()).isNull();
    }

    @Test
    void aCorrectPasswordForgetsWrongOnesAndAnExpiredLockAndRemembersTheTime() {
        user.registerFailedLogin(NOW, 5, LOCKOUT);
        user.registerFailedLogin(NOW, 5, LOCKOUT);

        user.registerSuccessfulLogin(NOW.plusSeconds(30));

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void replacingThePasswordLiftsALockAndClearsTheCount() {
        user.registerFailedLogin(NOW, 1, LOCKOUT);
        assertThat(user.isLockedAt(NOW)).isTrue();

        user.replacePasswordHash("{bcrypt}new");

        assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}new");
        assertThat(user.isLockedAt(NOW)).isFalse();
        assertThat(user.getFailedLoginAttempts()).isZero();
    }
}
