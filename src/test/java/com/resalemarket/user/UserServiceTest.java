package com.resalemarket.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.common.exception.ConflictException;
import com.resalemarket.user.dto.NewUser;
import com.resalemarket.user.dto.UserResponse;
import com.resalemarket.user.entity.Role;
import com.resalemarket.user.entity.User;
import com.resalemarket.user.entity.UserProfile;
import com.resalemarket.user.entity.UserStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository profileRepository;

    @Mock
    private RoleRepository roleRepository;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, profileRepository, roleRepository, new UserMapperImpl());
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
}
