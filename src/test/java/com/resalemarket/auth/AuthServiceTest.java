package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resalemarket.auth.dto.RegisterRequest;
import com.resalemarket.user.UserService;
import com.resalemarket.user.dto.NewUser;
import com.resalemarket.user.dto.UserResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void hashesThePasswordAndHandsOnlyTheHashToTheUserModule() {
        UserResponse created = new UserResponse(UUID.randomUUID(), "sita@example.com", "Sita", null, Instant.now());
        when(passwordEncoder.encode("correct horse")).thenReturn("{bcrypt}hashed");
        when(userService.createUser(org.mockito.ArgumentMatchers.any(NewUser.class))).thenReturn(created);

        UserResponse result = new AuthService(userService, passwordEncoder)
                .register(new RegisterRequest("sita@example.com", "correct horse", "Sita", "9812345678"));

        ArgumentCaptor<NewUser> handedOn = ArgumentCaptor.forClass(NewUser.class);
        verify(userService).createUser(handedOn.capture());
        assertThat(handedOn.getValue().passwordHash()).isEqualTo("{bcrypt}hashed");
        assertThat(handedOn.getValue().email()).isEqualTo("sita@example.com");
        assertThat(handedOn.getValue().displayName()).isEqualTo("Sita");
        assertThat(handedOn.getValue().phone()).isEqualTo("9812345678");
        assertThat(handedOn.getValue().toString()).doesNotContain("hashed", "correct horse");
        assertThat(result).isSameAs(created);
    }
}
