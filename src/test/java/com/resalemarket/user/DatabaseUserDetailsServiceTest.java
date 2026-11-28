package com.resalemarket.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.resalemarket.user.entity.Role;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DatabaseUserDetailsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:15:00Z");

    @Mock
    private UserRepository userRepository;

    private DatabaseUserDetailsService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseUserDetailsService(userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void mapsAnActiveAccountToUserDetails() {
        User user = UserFixtures.user("sita@example.com", UserFixtures.role(Role.USER), UserFixtures.role(Role.ADMIN));
        when(userRepository.findByEmail("sita@example.com")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("sita@example.com");

        assertThat(details.getUsername()).isEqualTo("sita@example.com");
        assertThat(details.getPassword()).isEqualTo(UserFixtures.HASH);
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
    }

    @Test
    void looksTheAddressUpInItsNormalisedForm() {
        when(userRepository.findByEmail("sita@example.com"))
                .thenReturn(Optional.of(UserFixtures.user("sita@example.com")));

        assertThat(service.loadUserByUsername("  Sita@Example.COM ").getUsername()).isEqualTo("sita@example.com");
    }

    @Test
    void reportsASuspendedAccountAsDisabled() {
        User user = UserFixtures.user("sita@example.com");
        ReflectionTestUtils.setField(user, "status", UserStatus.SUSPENDED);
        when(userRepository.findByEmail("sita@example.com")).thenReturn(Optional.of(user));

        assertThat(service.loadUserByUsername("sita@example.com").isEnabled()).isFalse();
    }

    @Test
    void reportsAnAccountInItsLockoutWindowAsLocked() {
        User user = UserFixtures.user("sita@example.com");
        ReflectionTestUtils.setField(user, "lockedUntil", NOW.plusSeconds(60));
        when(userRepository.findByEmail("sita@example.com")).thenReturn(Optional.of(user));

        assertThat(service.loadUserByUsername("sita@example.com").isAccountNonLocked()).isFalse();
    }

    @Test
    void anExpiredLockoutNoLongerLocksTheAccount() {
        User user = UserFixtures.user("sita@example.com");
        ReflectionTestUtils.setField(user, "lockedUntil", NOW.minusSeconds(1));
        when(userRepository.findByEmail("sita@example.com")).thenReturn(Optional.of(user));

        assertThat(service.loadUserByUsername("sita@example.com").isAccountNonLocked()).isTrue();
    }

    @Test
    void aDeletedAccountDoesNotExistForAuthentication() {
        User user = UserFixtures.user("sita@example.com");
        ReflectionTestUtils.setField(user, "status", UserStatus.DELETED);
        when(userRepository.findByEmail("sita@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.loadUserByUsername("sita@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void anUnknownAddressIsNotFoundWithoutRevealingAnything() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Unknown user.");
    }
}
