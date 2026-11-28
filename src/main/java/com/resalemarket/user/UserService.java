package com.resalemarket.user;

import com.resalemarket.common.exception.ConflictException;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.exception.UnauthorizedException;
import com.resalemarket.location.LocationService;
import com.resalemarket.location.dto.LocationNames;
import com.resalemarket.user.dto.NewUser;
import com.resalemarket.user.dto.PublicUserResponse;
import com.resalemarket.user.dto.UpdateProfileRequest;
import com.resalemarket.user.dto.UserResponse;
import com.resalemarket.user.entity.Role;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserProfile;
import com.resalemarket.user.entity.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
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
    private final LocationService locationService;
    private final Clock clock;

    public UserService(
            UserRepository userRepository,
            UserProfileRepository profileRepository,
            RoleRepository roleRepository,
            UserMapper mapper,
            LocationService locationService,
            Clock clock) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.roleRepository = roleRepository;
        this.mapper = mapper;
        this.locationService = locationService;
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
     * The signed-in person's own account, with the private fields.
     *
     * @throws UnauthorizedException if the account was suspended or deleted after the access token was issued: the token is
     *     still valid by signature, but the session must end (the client renews the token, is refused, and signs out)
     */
    public UserResponse getActiveAccount(UUID id) {
        return findActiveById(id).orElseThrow(UserService::sessionEnded);
    }

    /**
     * Saves the profile form. The whole editable profile is replaced, so an optional field left empty is cleared.
     *
     * @throws FieldValidationException if the location ids do not belong together, or the phone number is to be shown
     *     but there is none
     * @throws UnauthorizedException    if the account may no longer be used
     */
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findWithRolesById(userId).filter(UserService::isActive).orElseThrow(UserService::sessionEnded);
        UserProfile profile = profileRepository.findById(userId).orElseThrow(() -> missingProfile(userId));

        String phone = NepalPhone.normalise(request.phone());
        if (request.showPhone() && phone == null) {
            throw new FieldValidationException("showPhone", "Add a phone number to show it on your profile.");
        }
        locationService.resolve(request.provinceId(), request.districtId(), request.municipalityId());

        profile.update(
                request.displayName(),
                phone,
                request.showPhone(),
                request.bio(),
                request.provinceId(),
                request.districtId(),
                request.municipalityId());
        // Flushed here so a concurrent edit (optimistic lock) fails inside this call and the database timestamps are read back.
        profileRepository.saveAndFlush(profile);
        return mapper.toResponse(user, profile);
    }

    /**
     * What anyone may see about a person. Only accounts in good standing have a public profile: a suspended or deleted one is
     * answered like an unknown id, so the response does not reveal that the account exists.
     *
     * @throws ResourceNotFoundException if there is no such active account
     */
    public PublicUserResponse getPublicProfile(UUID id) {
        return findPublicProfile(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    /** Like {@link #getPublicProfile}, but an unknown, suspended or deleted account is an empty result instead of a 404. */
    public Optional<PublicUserResponse> findPublicProfile(UUID id) {
        return userRepository.findById(id).filter(UserService::isActive).map(user -> {
            UserProfile profile = profileRepository.findById(id).orElseThrow(() -> missingProfile(id));
            LocationNames location = locationService.resolve(
                    profile.getProvinceId(), profile.getDistrictId(), profile.getMunicipalityId());
            // The number is public only with its owner's explicit consent.
            String phone = profile.isShowPhone() ? profile.getPhone() : null;
            return new PublicUserResponse(user.getId(), profile.getDisplayName(), profile.getBio(), user.getCreatedAt(), location, phone);
        });
    }

    /** Whether the account exists and may sign in (not suspended or deleted): one row, no profile, for callers that only need to know. */
    public boolean isActive(UUID userId) {
        return userRepository.findById(userId).filter(UserService::isActive).isPresent();
    }

    /**
     * Makes sure the account may still act. An access token stays valid for up to 15 minutes after an account is suspended, so
     * actions that publish something call this instead of trusting the token alone.
     *
     * @throws UnauthorizedException if the account was suspended or deleted (the web app then signs the person out)
     */
    public void requireActive(UUID userId) {
        userRepository.findById(userId).filter(UserService::isActive).orElseThrow(UserService::sessionEnded);
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

    /**
     * Stores a new password hash (the caller hashed it; the plain password never reaches this module). Also lifts a lock-out,
     * because whoever got here proved they own the account. Ending the sessions that used the old password is the caller's job.
     *
     * @throws UnauthorizedException if the account may no longer be used
     */
    @Transactional
    public void replacePassword(UUID userId, String newPasswordHash) {
        User user = userRepository.findWithLockById(userId).filter(UserService::isActive).orElseThrow(UserService::sessionEnded);
        user.replacePasswordHash(newPasswordHash);
    }

    /**
     * Ids of the members whose ads must not be shown to the public: suspended and deleted accounts. Search and the home page
     * leave their listings out. The list is empty almost always and short otherwise, so it is passed to the search as an
     * exclusion; if suspensions ever become numerous, a visibility flag on the listing replaces it.
     */
    public List<UUID> inactiveUserIds() {
        return userRepository.findIdsByStatusIn(List.of(UserStatus.SUSPENDED, UserStatus.DELETED));
    }

    private static boolean isActive(User user) {
        return user.getStatus() == UserStatus.ACTIVE;
    }

    private static UnauthorizedException sessionEnded() {
        return new UnauthorizedException("Your session has expired. Please sign in again.");
    }

    private static IllegalStateException missingProfile(UUID userId) {
        return new IllegalStateException("User " + userId + " has no profile.");
    }

    private UserResponse toResponse(User user) {
        UserProfile profile = profileRepository.findById(user.getId()).orElseThrow(() -> missingProfile(user.getId()));
        return mapper.toResponse(user, profile);
    }
}
