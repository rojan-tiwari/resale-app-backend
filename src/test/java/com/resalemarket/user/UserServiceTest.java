package com.resalemarket.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.common.exception.ConflictException;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.exception.UnauthorizedException;
import com.resalemarket.location.LocationService;
import com.resalemarket.location.dto.LocationNames;
import com.resalemarket.support.MutableClock;
import com.resalemarket.user.dto.NewUser;
import com.resalemarket.user.dto.PublicUserResponse;
import com.resalemarket.user.dto.UpdateProfileRequest;
import com.resalemarket.user.dto.UserResponse;
import com.resalemarket.user.entity.Role;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserProfile;
import com.resalemarket.user.entity.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository profileRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private LocationService locationService;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(
                userRepository, profileRepository, roleRepository, new UserMapperImpl(), locationService, clock);
    }

    @Test
    void createsAnActiveUserWithTheUserRoleAndAProfile() {
        Role userRole = UserFixtures.role(Role.USER);
        when(userRepository.existsByEmail("sita@example.com")).thenReturn(false);
        when(roleRepository.findByName(Role.USER)).thenReturn(Optional.of(userRole));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(profileRepository.saveAndFlush(any(UserProfile.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse response = service.createUser(
                new NewUser("  Sita@Example.COM ", UserFixtures.HASH, "  Sita Sharma ", "9812345678"));

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(savedUser.capture());
        assertThat(savedUser.getValue().getEmail()).isEqualTo("sita@example.com");
        assertThat(savedUser.getValue().getPasswordHash()).isEqualTo(UserFixtures.HASH);
        assertThat(savedUser.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(savedUser.getValue().getRoles()).containsExactly(userRole);

        ArgumentCaptor<UserProfile> savedProfile = ArgumentCaptor.forClass(UserProfile.class);
        verify(profileRepository).saveAndFlush(savedProfile.capture());
        assertThat(savedProfile.getValue().getUser()).isSameAs(savedUser.getValue());
        assertThat(savedProfile.getValue().getDisplayName()).isEqualTo("Sita Sharma");
        assertThat(savedProfile.getValue().getPhone()).isEqualTo("+9779812345678");

        assertThat(response.email()).isEqualTo("sita@example.com");
        assertThat(response.displayName()).isEqualTo("Sita Sharma");
        assertThat(response.phone()).isEqualTo("+9779812345678");
    }

    @Test
    void phoneIsOptional() {
        when(roleRepository.findByName(Role.USER)).thenReturn(Optional.of(UserFixtures.role(Role.USER)));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(profileRepository.saveAndFlush(any(UserProfile.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse response = service.createUser(new NewUser("ram@example.com", UserFixtures.HASH, "Ram", null));

        assertThat(response.phone()).isNull();
    }

    @Test
    void rejectsAnEmailThatIsAlreadyRegisteredRegardlessOfCase() {
        when(userRepository.existsByEmail("sita@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(
                        new NewUser("SITA@example.com", UserFixtures.HASH, "Sita Sharma", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("An account with this email address already exists.");

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(profileRepository);
    }

    @Test
    void failsLoudlyWhenTheSeedRoleIsMissing() {
        when(roleRepository.findByName(Role.USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createUser(new NewUser("ram@example.com", UserFixtures.HASH, "Ram", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V8");
    }

    // --- lookups for sign-in ------------------------------------------------------------------------------------

    private User account(UUID id, UserStatus status, Role... roles) {
        return UserFixtures.withIdAndStatus(UserFixtures.user("sita@example.com", roles), id, status);
    }

    @Test
    void findsAnActiveAccountByEmailWithItsProfileAndSortedRoles() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE, UserFixtures.role(Role.USER), UserFixtures.role(Role.ADMIN));
        when(userRepository.findByEmail("sita@example.com")).thenReturn(Optional.of(user));
        when(profileRepository.findById(id)).thenReturn(Optional.of(UserProfile.create(user, "Sita Sharma", "+9779812345678")));

        Optional<UserResponse> found = service.findActiveByEmail("  SITA@Example.com ");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().displayName()).isEqualTo("Sita Sharma");
        assertThat(found.get().phone()).isEqualTo("+9779812345678");
        assertThat(found.get().roles()).containsExactly("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void doesNotFindAnUnknownAccountByEmail() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(service.findActiveByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void doesNotFindASuspendedOrDeletedAccountByEmail() {
        when(userRepository.findByEmail("sita@example.com"))
                .thenReturn(Optional.of(account(UUID.randomUUID(), UserStatus.SUSPENDED)))
                .thenReturn(Optional.of(account(UUID.randomUUID(), UserStatus.DELETED)));

        assertThat(service.findActiveByEmail("sita@example.com")).isEmpty();
        assertThat(service.findActiveByEmail("sita@example.com")).isEmpty();
        verifyNoInteractions(profileRepository);
    }

    @Test
    void findsAnActiveAccountById() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE);
        when(userRepository.findWithRolesById(id)).thenReturn(Optional.of(user));
        when(profileRepository.findById(id)).thenReturn(Optional.of(UserProfile.create(user, "Sita", null)));

        assertThat(service.findActiveById(id)).get().satisfies(response -> {
            assertThat(response.email()).isEqualTo("sita@example.com");
            assertThat(response.roles()).containsExactly("ROLE_USER");
        });
    }

    @Test
    void doesNotFindAnUnknownSuspendedOrDeletedAccountById() {
        UUID unknown = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        when(userRepository.findWithRolesById(unknown)).thenReturn(Optional.empty());
        when(userRepository.findWithRolesById(suspended)).thenReturn(Optional.of(account(suspended, UserStatus.SUSPENDED)));
        when(userRepository.findWithRolesById(deleted)).thenReturn(Optional.of(account(deleted, UserStatus.DELETED)));

        assertThat(service.findActiveById(unknown)).isEmpty();
        assertThat(service.findActiveById(suspended)).isEmpty();
        assertThat(service.findActiveById(deleted)).isEmpty();
    }

    @Test
    void anAccountWithoutAProfileIsADataErrorNotASilentGap() {
        UUID id = UUID.randomUUID();
        when(userRepository.findWithRolesById(id)).thenReturn(Optional.of(account(id, UserStatus.ACTIVE)));
        when(profileRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findActiveById(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no profile");
    }

    // --- own account ----------------------------------------------------------------------------------------------

    private static UpdateProfileRequest form(String name, String phone, boolean showPhone, String bio, Integer province,
            Integer district, Integer municipality) {
        return new UpdateProfileRequest(name, phone, showPhone, bio, province, district, municipality);
    }

    @Test
    void anActiveAccountIsReturnedWithItsPrivateFields() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE);
        UserProfile profile = UserProfile.create(user, "Sita", "+9779812345678");
        profile.update("Sita", "+9779812345678", true, "Selling things I love", 3, 25, 1);
        when(userRepository.findWithRolesById(id)).thenReturn(Optional.of(user));
        when(profileRepository.findById(id)).thenReturn(Optional.of(profile));

        UserResponse response = service.getActiveAccount(id);

        assertThat(response.email()).isEqualTo("sita@example.com");
        assertThat(response.showPhone()).isTrue();
        assertThat(response.bio()).isEqualTo("Selling things I love");
        assertThat(response.provinceId()).isEqualTo(3);
        assertThat(response.districtId()).isEqualTo(25);
        assertThat(response.municipalityId()).isEqualTo(1);
    }

    @Test
    void anAccountThatIsNoLongerActiveEndsTheSessionInsteadOfBeingReturned() {
        UUID id = UUID.randomUUID();
        when(userRepository.findWithRolesById(id)).thenReturn(Optional.of(account(id, UserStatus.SUSPENDED)));

        assertThatThrownBy(() -> service.getActiveAccount(id))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Your session has expired. Please sign in again.");
    }

    @Test
    void savingTheProfileReplacesEverythingTheOwnerCanEdit() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE);
        UserProfile profile = UserProfile.create(user, "Old Name", null);
        when(userRepository.findWithRolesById(id)).thenReturn(Optional.of(user));
        when(profileRepository.findById(id)).thenReturn(Optional.of(profile));
        when(locationService.resolve(3, 25, 1)).thenReturn(new LocationNames("Bagmati", "Kathmandu", "Kathmandu"));

        UserResponse response = service.updateProfile(
                id, form("Sita Sharma", "9812345678", true, "Hello there", 3, 25, 1));

        assertThat(profile.getDisplayName()).isEqualTo("Sita Sharma");
        assertThat(profile.getPhone()).isEqualTo("+9779812345678");   // stored in E.164 form
        assertThat(profile.isShowPhone()).isTrue();
        assertThat(profile.getBio()).isEqualTo("Hello there");
        assertThat(profile.getProvinceId()).isEqualTo(3);
        assertThat(profile.getDistrictId()).isEqualTo(25);
        assertThat(profile.getMunicipalityId()).isEqualTo(1);
        verify(profileRepository).saveAndFlush(profile);
        assertThat(response.displayName()).isEqualTo("Sita Sharma");
        assertThat(response.phone()).isEqualTo("+9779812345678");
    }

    @Test
    void anOptionalFieldThatIsLeftEmptyIsCleared() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE);
        UserProfile profile = UserProfile.create(user, "Sita", "+9779812345678");
        profile.update("Sita", "+9779812345678", true, "old bio", 3, 25, 1);
        when(userRepository.findWithRolesById(id)).thenReturn(Optional.of(user));
        when(profileRepository.findById(id)).thenReturn(Optional.of(profile));
        when(locationService.resolve(null, null, null)).thenReturn(LocationNames.NONE);

        service.updateProfile(id, form("Sita", null, false, null, null, null, null));

        assertThat(profile.getPhone()).isNull();
        assertThat(profile.isShowPhone()).isFalse();
        assertThat(profile.getBio()).isNull();
        assertThat(profile.getProvinceId()).isNull();
        assertThat(profile.getDistrictId()).isNull();
        assertThat(profile.getMunicipalityId()).isNull();
    }

    @Test
    void thePhoneNumberCannotBeShownWhenThereIsNone() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE);
        UserProfile profile = UserProfile.create(user, "Sita", null);
        when(userRepository.findWithRolesById(id)).thenReturn(Optional.of(user));
        when(profileRepository.findById(id)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.updateProfile(id, form("Sita", null, true, null, null, null, null)))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.getViolations().get(0).field()).isEqualTo("showPhone"));

        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    void aLocationThatDoesNotFitTogetherIsRejectedAndNothingIsSaved() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE);
        UserProfile profile = UserProfile.create(user, "Sita", null);
        when(userRepository.findWithRolesById(id)).thenReturn(Optional.of(user));
        when(profileRepository.findById(id)).thenReturn(Optional.of(profile));
        when(locationService.resolve(1, 25, null))
                .thenThrow(new FieldValidationException("districtId", "This district is not in the chosen province."));

        assertThatThrownBy(() -> service.updateProfile(id, form("Sita", null, false, null, 1, 25, null)))
                .isInstanceOf(FieldValidationException.class);

        assertThat(profile.getDistrictId()).isNull();
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    void anInactiveAccountCannotEditItsProfile() {
        UUID id = UUID.randomUUID();
        when(userRepository.findWithRolesById(id)).thenReturn(Optional.of(account(id, UserStatus.DELETED)));

        assertThatThrownBy(() -> service.updateProfile(id, form("Sita", null, false, null, null, null, null)))
                .isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(profileRepository);
    }

    // --- public profile -------------------------------------------------------------------------------------------

    private PublicUserResponse publicProfileOf(boolean showPhone) {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "createdAt", Instant.parse("2026-10-20T08:15:00Z"));
        UserProfile profile = UserProfile.create(user, "Sita Sharma", "+9779812345678");
        profile.update("Sita Sharma", "+9779812345678", showPhone, "I sell books", 3, 25, 1);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(profileRepository.findById(id)).thenReturn(Optional.of(profile));
        when(locationService.resolve(3, 25, 1)).thenReturn(new LocationNames("Bagmati", "Kathmandu", "Kathmandu"));
        return service.getPublicProfile(id);
    }

    @Test
    void thePublicProfileShowsThePhoneOnlyWithConsent() {
        assertThat(publicProfileOf(true).phone()).isEqualTo("+9779812345678");
    }

    @Test
    void thePublicProfileHidesThePhoneWithoutConsent() {
        PublicUserResponse response = publicProfileOf(false);

        assertThat(response.phone()).isNull();
        assertThat(response.toString()).doesNotContain("9779812345678");
    }

    @Test
    void thePublicProfileHasNoEmailAndCarriesTheNamesOfThePlace() {
        PublicUserResponse response = publicProfileOf(false);

        assertThat(response.displayName()).isEqualTo("Sita Sharma");
        assertThat(response.bio()).isEqualTo("I sell books");
        assertThat(response.memberSince()).isEqualTo(Instant.parse("2026-10-20T08:15:00Z"));
        assertThat(response.location()).isEqualTo(new LocationNames("Bagmati", "Kathmandu", "Kathmandu"));
        assertThat(response.toString()).doesNotContain("sita@example.com");
        assertThat(PublicUserResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("email", "roles", "showPhone");
    }

    @Test
    void findingAPublicProfileGivesAnEmptyResultInsteadOfA404ForAnInactiveMember() {
        UUID suspended = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(suspended)).thenReturn(Optional.of(account(suspended, UserStatus.SUSPENDED)));
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.findPublicProfile(suspended)).isEmpty();
        assertThat(service.findPublicProfile(unknown)).isEmpty();
        verifyNoInteractions(profileRepository);
    }

    @Test
    void anActiveAccountMayActAndASuspendedOrDeletedOneMayNot() {
        UUID active = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(active)).thenReturn(Optional.of(account(active, UserStatus.ACTIVE)));
        when(userRepository.findById(suspended)).thenReturn(Optional.of(account(suspended, UserStatus.SUSPENDED)));
        when(userRepository.findById(deleted)).thenReturn(Optional.of(account(deleted, UserStatus.DELETED)));
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        service.requireActive(active);

        for (UUID id : new UUID[] {suspended, deleted, unknown}) {
            assertThatThrownBy(() -> service.requireActive(id))
                    .isInstanceOf(UnauthorizedException.class)
                    .hasMessage("Your session has expired. Please sign in again.");
        }
    }

    @Test
    void isActiveIsTrueOnlyForAnExistingAccountInGoodStanding() {
        UUID active = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(active)).thenReturn(Optional.of(account(active, UserStatus.ACTIVE)));
        when(userRepository.findById(suspended)).thenReturn(Optional.of(account(suspended, UserStatus.SUSPENDED)));
        when(userRepository.findById(deleted)).thenReturn(Optional.of(account(deleted, UserStatus.DELETED)));
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.isActive(active)).isTrue();
        assertThat(service.isActive(suspended)).isFalse();
        assertThat(service.isActive(deleted)).isFalse();
        assertThat(service.isActive(unknown)).isFalse();
        verifyNoInteractions(profileRepository);          // one row: no profile, no place names
    }

    @Test
    void theIdsOfMembersWhoMustNotBeShownAreTheSuspendedAndDeletedOnes() {
        UUID a = UUID.randomUUID();
        when(userRepository.findIdsByStatusIn(List.of(UserStatus.SUSPENDED, UserStatus.DELETED))).thenReturn(List.of(a));

        assertThat(service.inactiveUserIds()).containsExactly(a);
    }

    @Test
    void anUnknownSuspendedOrDeletedMemberHasNoPublicProfile() {
        UUID unknown = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());
        when(userRepository.findById(suspended)).thenReturn(Optional.of(account(suspended, UserStatus.SUSPENDED)));
        when(userRepository.findById(deleted)).thenReturn(Optional.of(account(deleted, UserStatus.DELETED)));

        for (UUID id : new UUID[] {unknown, suspended, deleted}) {
            assertThatThrownBy(() -> service.getPublicProfile(id)).isInstanceOf(ResourceNotFoundException.class);
        }
        verifyNoInteractions(profileRepository);
    }

    // --- wrong passwords and lock-out -----------------------------------------------------------------------------

    @Test
    void wrongPasswordsAreCountedAndTheAccountIsLockedAtTheLimit() {
        User user = account(UUID.randomUUID(), UserStatus.ACTIVE);
        when(userRepository.findWithLockByEmail("sita@example.com")).thenReturn(Optional.of(user));

        service.recordFailedLogin("Sita@Example.com ", 3, Duration.ofMinutes(15));
        service.recordFailedLogin("sita@example.com", 3, Duration.ofMinutes(15));
        assertThat(user.getFailedLoginAttempts()).isEqualTo(2);
        assertThat(user.isLockedAt(clock.instant())).isFalse();

        service.recordFailedLogin("sita@example.com", 3, Duration.ofMinutes(15));
        assertThat(user.isLockedAt(clock.instant())).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
    }

    @Test
    void aWrongPasswordForAnUnknownAddressIsIgnored() {
        when(userRepository.findWithLockByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.recordFailedLogin("nobody@example.com", 3, Duration.ofMinutes(15));

        verify(userRepository).findWithLockByEmail("nobody@example.com");
        verifyNoInteractions(profileRepository);
    }

    @Test
    void aSuccessfulSignInForgetsWrongPasswordsAndRecordsTheTime() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE);
        user.registerFailedLogin(clock.instant(), 5, Duration.ofMinutes(15));
        when(userRepository.findWithLockById(id)).thenReturn(Optional.of(user));

        service.recordSuccessfulLogin(id);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(clock.instant());
    }

    // --- replacing the password -----------------------------------------------------------------------------------

    @Test
    void replacingThePasswordStoresTheNewHashAndLiftsALock() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.ACTIVE);
        user.registerFailedLogin(clock.instant(), 1, Duration.ofMinutes(15));   // locked
        when(userRepository.findWithLockById(id)).thenReturn(Optional.of(user));

        service.replacePassword(id, "{bcrypt}new-hash");

        assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}new-hash");
        assertThat(user.isLockedAt(clock.instant())).isFalse();
        assertThat(user.getFailedLoginAttempts()).isZero();
    }

    @Test
    void thePasswordOfAnInactiveAccountIsNotReplaced() {
        UUID id = UUID.randomUUID();
        User user = account(id, UserStatus.SUSPENDED);
        when(userRepository.findWithLockById(id)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.replacePassword(id, "{bcrypt}new-hash"))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(user.getPasswordHash()).isEqualTo(UserFixtures.HASH);
    }
}
