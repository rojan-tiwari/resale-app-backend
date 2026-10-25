package com.resalemarket.user;

import static org.springframework.security.core.userdetails.User.withUsername;

import com.resalemarket.user.entity.Role;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserStatus;
import java.time.Clock;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Looks accounts up in the {@code users} table for Spring Security. The "username" is the e-mail address.
 *
 * <p>Suspended accounts are reported as disabled and accounts in a lock-out window as locked, so the authentication
 * provider refuses them. Deleted accounts do not exist as far as authentication is concerned.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final Clock clock;

    public DatabaseUserDetailsService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        User user = userRepository.findByEmail(User.normaliseEmail(username))
                .filter(found -> found.getStatus() != UserStatus.DELETED)
                // Deliberately generic: the message must not reveal whether the address is registered.
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user."));

        return withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(user.getRoles().stream().map(Role::getName).toArray(String[]::new))
                .disabled(user.getStatus() == UserStatus.SUSPENDED)
                .accountLocked(user.isLockedAt(clock.instant()))
                .build();
    }
}
