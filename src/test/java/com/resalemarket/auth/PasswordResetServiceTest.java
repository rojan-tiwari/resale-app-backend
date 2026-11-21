package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.auth.entity.RevocationReason;
import com.resalemarket.common.config.FrontendProperties;
import com.resalemarket.common.exception.InvalidTokenException;
import com.resalemarket.common.mail.EmailSender;
import com.resalemarket.common.mail.MailMessage;
import com.resalemarket.user.UserService;
import com.resalemarket.user.dto.UserResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private UserService userService;

    @Mock
    private PasswordResetTokens tokens;

    @Mock
    private RefreshTokenService refreshTokens;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailSender emailSender;

    private PasswordResetService service;

    private final UserResponse user = new UserResponse(
            USER_ID, "sita@example.com", "Sita Sharma", null, false, null, null, null, null, Instant.now(), List.of("ROLE_USER"));

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(
                Duration.ofDays(14),
                Duration.ofSeconds(10),
                new AuthProperties.RefreshCookie("refresh_token", true, "Strict", "/api/v1/auth"),
                Duration.ofHours(1),
                5,
                Duration.ofMinutes(15),
                new AuthProperties.RateLimits(null, null, null, null, null, null));
        service = new PasswordResetService(
                userService, tokens, refreshTokens, passwordEncoder, emailSender,
                new FrontendProperties("https://resalemarket.example/"), properties);
    }

    // --- asking for a link --------------------------------------------------------------------------------------

    @Test
    void anExistingAccountGetsAnEmailWithAOneTimeLinkToTheWebApp() {
        when(userService.findActiveByEmail("sita@example.com")).thenReturn(Optional.of(user));
        when(tokens.issue(USER_ID)).thenReturn("raw-token-123");

        service.requestReset("sita@example.com");

        ArgumentCaptor<MailMessage> sent = ArgumentCaptor.forClass(MailMessage.class);
        verify(emailSender).send(sent.capture());
        MailMessage mail = sent.getValue();
        assertThat(mail.to()).isEqualTo("sita@example.com");
        assertThat(mail.subject()).isEqualTo("Reset your ResaleMarket password");
        assertThat(mail.text())
                .contains("Hello Sita Sharma,")
                .contains("https://resalemarket.example/auth/reset-password?token=raw-token-123")
                .contains("expires in 1 hour")
                .contains("you can ignore this e-mail");
        assertThat(mail.toString()).doesNotContain("raw-token-123");   // the secret stays out of logs that print the object
    }

    @Test
    void anUnknownAddressGetsNothingAndNothingIsStored() {
        when(userService.findActiveByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.requestReset("nobody@example.com");

        verifyNoInteractions(tokens, emailSender);
    }

    @Test
    void aProblemInTheBackgroundIsLoggedNeverThrown() {
        when(userService.findActiveByEmail("sita@example.com")).thenReturn(Optional.of(user));
        when(tokens.issue(USER_ID)).thenReturn("raw-token-123");
        org.mockito.Mockito.doThrow(new MailSendException("Invalid Addresses: sita@example.com")).when(emailSender).send(any());

        assertThatCode(() -> service.requestReset("sita@example.com")).doesNotThrowAnyException();
    }

    @Test
    void aFailingLookupOrTokenStoreIsAlsoNeverThrown() {
        when(userService.findActiveByEmail("a@example.com")).thenThrow(new IllegalStateException("database down"));
        when(userService.findActiveByEmail("sita@example.com")).thenReturn(Optional.of(user));
        when(tokens.issue(USER_ID)).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> service.requestReset("a@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> service.requestReset("sita@example.com")).doesNotThrowAnyException();

        verifyNoInteractions(emailSender);
    }

    // --- using the link -----------------------------------------------------------------------------------------

    @Test
    void aValidLinkSetsTheNewPasswordAndEndsEverySession() {
        when(tokens.consume("the-token")).thenReturn(USER_ID);
        when(userService.findActiveById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("a brand new password")).thenReturn("{bcrypt}new-hash");

        service.reset("the-token", "a brand new password");

        InOrder order = inOrder(tokens, userService, refreshTokens);
        order.verify(tokens).consume("the-token");
        order.verify(userService).replacePassword(USER_ID, "{bcrypt}new-hash");
        order.verify(refreshTokens).revokeAll(USER_ID, RevocationReason.PASSWORD_CHANGED);
    }

    @Test
    void anInvalidLinkChangesNothing() {
        when(tokens.consume("bad")).thenThrow(PasswordResetTokens.invalid());

        assertThatThrownBy(() -> service.reset("bad", "a brand new password")).isInstanceOf(InvalidTokenException.class);

        verifyNoInteractions(passwordEncoder, refreshTokens);
        verify(userService, never()).replacePassword(any(), any());
    }

    @Test
    void aLinkOfASuspendedOrDeletedAccountIsAsGoodAsUnknown() {
        when(tokens.consume("the-token")).thenReturn(USER_ID);
        when(userService.findActiveById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reset("the-token", "a brand new password"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(PasswordResetTokens.INVALID_LINK);

        verify(userService, never()).replacePassword(any(), any());
        verifyNoInteractions(refreshTokens);
    }

    // --- wording ------------------------------------------------------------------------------------------------

    @Test
    void theLifetimeIsDescribedInWholeHoursOrMinutes() {
        assertThat(PasswordResetService.describe(Duration.ofHours(1))).isEqualTo("1 hour");
        assertThat(PasswordResetService.describe(Duration.ofHours(2))).isEqualTo("2 hours");
        assertThat(PasswordResetService.describe(Duration.ofMinutes(30))).isEqualTo("30 minutes");
        assertThat(PasswordResetService.describe(Duration.ofMinutes(90))).isEqualTo("90 minutes");
        assertThat(PasswordResetService.describe(Duration.ofSeconds(10))).isEqualTo("1 minute");
    }
}
