package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.auth.dto.LoginRequest;
import com.resalemarket.auth.dto.RegisterRequest;
import com.resalemarket.common.exception.ForbiddenException;
import com.resalemarket.common.exception.UnauthorizedException;
import com.resalemarket.user.UserService;
import com.resalemarket.user.dto.NewUser;
import com.resalemarket.user.dto.UserResponse;
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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "Mozilla/5.0");
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private UserService userService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private AccessTokenService accessTokens;

    @Mock
    private RefreshTokenService refreshTokens;

    private AuthService service;

    private final UserResponse user = new UserResponse(
            USER_ID, "sita@example.com", "Sita", null, Instant.now(), List.of("ROLE_USER"));

    @BeforeEach
    void setUp() {
        service = new AuthService(userService, passwordEncoder, authenticationManager, accessTokens, refreshTokens);
    }

    private Authentication authenticated() {
        return UsernamePasswordAuthenticationToken.authenticated("sita@example.com", null, List.of());
    }

    // --- register ---------------------------------------------------------------------------------------------------

    @Test
    void hashesThePasswordAndHandsOnlyTheHashToTheUserModule() {
        when(passwordEncoder.encode("correct horse")).thenReturn("{bcrypt}hashed");
        when(userService.createUser(any(NewUser.class))).thenReturn(user);

        UserResponse result =
                service.register(new RegisterRequest("sita@example.com", "correct horse", "Sita", "9812345678"));

        ArgumentCaptor<NewUser> handedOn = ArgumentCaptor.forClass(NewUser.class);
        verify(userService).createUser(handedOn.capture());
        assertThat(handedOn.getValue().passwordHash()).isEqualTo("{bcrypt}hashed");
        assertThat(handedOn.getValue().email()).isEqualTo("sita@example.com");
        assertThat(handedOn.getValue().displayName()).isEqualTo("Sita");
        assertThat(handedOn.getValue().phone()).isEqualTo("9812345678");
        assertThat(handedOn.getValue().toString()).doesNotContain("hashed", "correct horse");
        assertThat(result).isSameAs(user);
    }

    // --- login ------------------------------------------------------------------------------------------------------

    @Test
    void loginChecksTheCredentialsThenIssuesBothTokens() {
        when(authenticationManager.authenticate(any())).thenReturn(authenticated());
        when(userService.findActiveByEmail("sita@example.com")).thenReturn(Optional.of(user));
        when(refreshTokens.start(USER_ID, CLIENT)).thenReturn("refresh-token");
        when(accessTokens.issue(user)).thenReturn(new AccessTokenService.AccessToken("access-token", 900));

        AuthSession session = service.login(new LoginRequest("sita@example.com", "correct horse"), CLIENT);

        ArgumentCaptor<Authentication> attempt = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(attempt.capture());
        assertThat(attempt.getValue().isAuthenticated()).isFalse();
        assertThat(attempt.getValue().getPrincipal()).isEqualTo("sita@example.com");
        assertThat(attempt.getValue().getCredentials()).isEqualTo("correct horse");

        assertThat(session.refreshToken()).isEqualTo("refresh-token");
        assertThat(session.response().accessToken()).isEqualTo("access-token");
        assertThat(session.response().tokenType()).isEqualTo("Bearer");
        assertThat(session.response().expiresIn()).isEqualTo(900);
        assertThat(session.response().user()).isSameAs(user);
        assertThat(session.toString()).doesNotContain("refresh-token", "access-token");
    }

    @Test
    void aWrongPasswordIsRefusedAndNoTokenIsIssued() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> service.login(new LoginRequest("sita@example.com", "wrong"), CLIENT))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password.");

        verifyNoInteractions(accessTokens, refreshTokens, userService);
    }

    @Test
    void aSuspendedAccountIsToldSoOnlyAfterTheCorrectPassword() {
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> service.login(new LoginRequest("sita@example.com", "correct horse"), CLIENT))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This account has been suspended.");

        verifyNoInteractions(accessTokens, refreshTokens);
    }

    @Test
    void aLockedAccountIsRefusedWithoutTokens() {
        when(authenticationManager.authenticate(any())).thenThrow(new LockedException("locked"));

        assertThatThrownBy(() -> service.login(new LoginRequest("sita@example.com", "correct horse"), CLIENT))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("temporarily locked");

        verifyNoInteractions(accessTokens, refreshTokens);
    }

    @Test
    void anInfrastructureFailureIsNotDisguisedAsWrongCredentials() {
        InternalAuthenticationServiceException outage = new InternalAuthenticationServiceException("database down");
        when(authenticationManager.authenticate(any())).thenThrow(outage);

        assertThatThrownBy(() -> service.login(new LoginRequest("sita@example.com", "correct horse"), CLIENT))
                .isSameAs(outage);
    }

    @Test
    void anAccountThatDisappearsDuringLoginIsRefused() {
        when(authenticationManager.authenticate(any())).thenReturn(authenticated());
        when(userService.findActiveByEmail("sita@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("sita@example.com", "correct horse"), CLIENT))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password.");

        verifyNoInteractions(accessTokens, refreshTokens);
    }

    // --- refresh ----------------------------------------------------------------------------------------------------

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() {
        when(refreshTokens.rotate("old-refresh", CLIENT))
                .thenReturn(Optional.of(new RefreshTokenService.Rotation(USER_ID, "new-refresh")));
        when(userService.findActiveById(USER_ID)).thenReturn(Optional.of(user));
        when(accessTokens.issue(user)).thenReturn(new AccessTokenService.AccessToken("access-token", 900));

        AuthSession session = service.refresh("old-refresh", CLIENT);

        assertThat(session.refreshToken()).isEqualTo("new-refresh");
        assertThat(session.response().accessToken()).isEqualTo("access-token");
        assertThat(session.response().user()).isSameAs(user);
    }

    @Test
    void refreshAfterATabRaceKeepsTheCookieAsItIs() {
        when(refreshTokens.rotate("raced", CLIENT)).thenReturn(Optional.of(new RefreshTokenService.Rotation(USER_ID, null)));
        when(userService.findActiveById(USER_ID)).thenReturn(Optional.of(user));
        when(accessTokens.issue(user)).thenReturn(new AccessTokenService.AccessToken("access-token", 900));

        AuthSession session = service.refresh("raced", CLIENT);

        assertThat(session.refreshToken()).isNull();
        assertThat(session.response().accessToken()).isEqualTo("access-token");
    }

    @Test
    void refreshWithoutACookieIsRefusedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.refresh(null, CLIENT))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Your session has expired. Please sign in again.");
        assertThatThrownBy(() -> service.refresh("  ", CLIENT)).isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(refreshTokens, userService, accessTokens);
    }

    @Test
    void refreshWithARefusedTokenIsRefused() {
        when(refreshTokens.rotate("stale", CLIENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("stale", CLIENT)).isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(userService, accessTokens);
    }

    @Test
    void refreshIsRefusedWhenTheAccountMayNoLongerSignIn() {
        when(refreshTokens.rotate("valid", CLIENT)).thenReturn(Optional.of(new RefreshTokenService.Rotation(USER_ID, "new")));
        when(userService.findActiveById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("valid", CLIENT)).isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(accessTokens);
    }

    // --- logout -----------------------------------------------------------------------------------------------------

    @Test
    void logoutRevokesTheSession() {
        service.logout("current");

        verify(refreshTokens).revoke("current");
    }

    @Test
    void logoutAllRevokesEverySessionOfTheUser() {
        service.logoutAll(USER_ID);

        verify(refreshTokens).revokeAll(USER_ID);
    }

    @Test
    void logoutWithoutATokenIsANoOp() {
        service.logout(null);
        service.logout(" ");

        verifyNoInteractions(refreshTokens);
    }
}
