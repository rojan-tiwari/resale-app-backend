package com.resalemarket.user;

import com.resalemarket.common.exception.ConflictException;
import com.resalemarket.user.dto.NewUser;
import com.resalemarket.user.dto.UserResponse;
import com.resalemarket.user.entity.Role;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserProfile;
import com.resalemarket.user.entity.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accounts and profiles. Other modules (such as {@code auth}) use this service, never the repositories. */
@Service
@Transactional(readOnly = true)
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final RoleRepository roleRepository;
    private final UserMapper mapper;
    private final Clock clock;

    public UserService(
            UserRepository userRepository,
            UserProfileRepository profileRepository,
            RoleRepository roleRepository,
            UserMapper mapper,
            Clock clock) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.roleRepository = roleRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Creates an active account with the {@code ROLE_USER} role and its profile, in one transaction.
     *
     * @throws ConflictException if the e-mail address is already registered (compared case-insensitively)
     */
    @Transactional
    public UserResponse createUser(NewUser newUser) {
        String email = User.normaliseEmail(newUser.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with this email address already exists.");
        }
        Role role = roleRepository.findByName(Role.USER)
                .orElseThrow(() -> new IllegalStateException(
                        "Role " + Role.USER + " does not exist; the Flyway seed migration V8 has not been applied."));

        // Flushing makes two things happen inside this call: the database-generated timestamps are read back for the
        // response, and a concurrent registration of the same e-mail fails on uq_users_email (HTTP 409) right here.
        User user = userRepository.saveAndFlush(User.register(email, newUser.passwordHash(), role));
        UserProfile profile = profileRepository.saveAndFlush(
                UserProfile.create(user, newUser.displayName(), NepalPhone.normalise(newUser.phone())));
        return mapper.toResponse(user, profile);
    }

    /** The account with this e-mail address if it exists and may sign in (not suspended or deleted). */
    public Optional<UserResponse> findActiveByEmail(String email) {
        return userRepository.findByEmail(User.normaliseEmail(email)).filter(UserService::isActive).map(this::toResponse);
    }

    /** The account with this id if it exists and may sign in. Used to decide whether a session may continue. */
    public Optional<UserResponse> findActiveById(UUID id) {
        return userRepository.findWithRolesById(id).filter(UserService::isActive).map(this::toResponse);
    }

    /**
     * Counts a wrong password for the account with this e-mail address, and locks it when there have been too many in a row.
     * An unknown address is ignored. This runs in its own transaction so the count is kept even though the sign-in then fails.
     */
    @Transactional
    public void recordFailedLogin(String email, int maxAttempts, Duration lockout) {
        userRepository.findWithLockByEmail(User.normaliseEmail(email)).ifPresent(user -> {
            if (user.registerFailedLogin(clock.instant(), maxAttempts, lockout)) {
                log.warn("User {} locked for {} after {} wrong passwords in a row", user.getId(), lockout, maxAttempts);
            }
        });
    }

    /** Forgets earlier wrong passwords and records when the person signed in. */
    @Transactional
    public void recordSuccessfulLogin(UUID userId) {
        userRepository.findWithLockById(userId).ifPresent(user -> user.registerSuccessfulLogin(clock.instant()));
    }

    private static boolean isActive(User user) {
        return user.getStatus() == UserStatus.ACTIVE;
    }

    private static IllegalStateException missingProfile(UUID userId) {
        return new IllegalStateException("User " + userId + " has no profile.");
    }

    private UserResponse toResponse(User user) {
        UserProfile profile = profileRepository.findById(user.getId()).orElseThrow(() -> missingProfile(user.getId()));
        return mapper.toResponse(user, profile);
    }
}
