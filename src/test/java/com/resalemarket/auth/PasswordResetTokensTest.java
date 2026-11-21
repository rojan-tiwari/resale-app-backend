package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resalemarket.auth.entity.PasswordResetToken;
import com.resalemarket.common.exception.InvalidTokenException;
import com.resalemarket.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PasswordResetTokensTest {

    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    @Mock
    private PasswordResetTokenRepository repository;

    private final MutableClock clock = new MutableClock(START);
    private PasswordResetTokens tokens;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(
                Duration.ofDays(14),
                Duration.ofSeconds(10),
                new AuthProperties.RefreshCookie("refresh_token", true, "Strict", "/api/v1/auth"),
                Duration.ofMinutes(45),
                5,
                Duration.ofMinutes(15),
                new AuthProperties.RateLimits(null, null, null, null, null));
        tokens = new PasswordResetTokens(repository, properties, clock);
    }

    private PasswordResetToken storedToken(String raw, Instant expiresAt) {
        PasswordResetToken token = PasswordResetToken.issue(USER, OpaqueTokens.sha256Hex(raw), START, expiresAt);
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        return token;
    }

    @Test
    void issuingCancelsEarlierLinksThenStoresOnlyTheHashWithTheConfiguredLifetime() {
        String raw = tokens.issue(USER);

        InOrder order = inOrder(repository);
        order.verify(repository).deleteUnusedForUser(USER);
        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        order.verify(repository).saveAndFlush(saved.capture());

        assertThat(raw).hasSize(43).matches("[A-Za-z0-9_-]+");   // 256 bits, URL-safe: fine inside a link
        assertThat(saved.getValue().getTokenHash()).hasSize(64).isEqualTo(OpaqueTokens.sha256Hex(raw)).isNotEqualTo(raw);
        assertThat(saved.getValue().getUserId()).isEqualTo(USER);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(START);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(START.plus(Duration.ofMinutes(45)));
        assertThat(saved.getValue().isUsed()).isFalse();
    }

    @Test
    void everyIssuedTokenIsDifferent() {
        assertThat(tokens.issue(USER)).isNotEqualTo(tokens.issue(USER));
    }

    @Test
    void usingAValidTokenMarksItUsedCancelsTheOthersAndSaysWhoItBelongsTo() {
        PasswordResetToken token = storedToken("the-token", START.plus(Duration.ofMinutes(45)));
        when(repository.findByTokenHash(OpaqueTokens.sha256Hex("the-token"))).thenReturn(Optional.of(token));
        clock.advance(Duration.ofMinutes(10));

        UUID owner = tokens.consume("the-token");

        assertThat(owner).isEqualTo(USER);
        assertThat(token.isUsed()).isTrue();
        assertThat(token.getUsedAt()).isEqualTo(clock.instant());
        verify(repository).deleteUnusedForUserExcept(USER, token.getId());
    }

    @Test
    void anUnknownTokenIsInvalid() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokens.consume("never-issued"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(PasswordResetTokens.INVALID_LINK);

        verify(repository, never()).deleteUnusedForUserExcept(any(), any());
    }

    @Test
    void aTokenThatWasAlreadyUsedIsInvalidSoALinkWorksOnlyOnce() {
        PasswordResetToken token = storedToken("the-token", START.plus(Duration.ofMinutes(45)));
        token.markUsed(START.plusSeconds(60));
        when(repository.findByTokenHash(OpaqueTokens.sha256Hex("the-token"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> tokens.consume("the-token"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(PasswordResetTokens.INVALID_LINK);

        assertThat(token.getUsedAt()).isEqualTo(START.plusSeconds(60));   // untouched
    }

    @Test
    void anExpiredTokenIsInvalidFromTheExactMomentOfExpiry() {
        PasswordResetToken token = storedToken("the-token", START.plus(Duration.ofMinutes(45)));
        when(repository.findByTokenHash(OpaqueTokens.sha256Hex("the-token"))).thenReturn(Optional.of(token));

        clock.advance(Duration.ofMinutes(45).minusSeconds(1));
        assertThat(tokens.consume("the-token")).isEqualTo(USER);   // still fine one second before

        PasswordResetToken another = storedToken("another", START.plus(Duration.ofMinutes(45)));
        when(repository.findByTokenHash(OpaqueTokens.sha256Hex("another"))).thenReturn(Optional.of(another));
        clock.advance(Duration.ofSeconds(1));
        assertThatThrownBy(() -> tokens.consume("another")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void unknownUsedAndExpiredTokensAreAnsweredWithTheSameWords() {
        PasswordResetToken used = storedToken("used", START.plus(Duration.ofMinutes(45)));
        used.markUsed(START);
        PasswordResetToken expired = storedToken("expired", START.minusSeconds(1));
        when(repository.findByTokenHash(OpaqueTokens.sha256Hex("used"))).thenReturn(Optional.of(used));
        when(repository.findByTokenHash(OpaqueTokens.sha256Hex("expired"))).thenReturn(Optional.of(expired));
        when(repository.findByTokenHash(OpaqueTokens.sha256Hex("unknown"))).thenReturn(Optional.empty());

        String messageOfUsed = catchMessage("used");
        String messageOfExpired = catchMessage("expired");
        String messageOfUnknown = catchMessage("unknown");

        assertThat(messageOfUsed).isEqualTo(messageOfExpired).isEqualTo(messageOfUnknown);
    }

    private String catchMessage(String raw) {
        try {
            tokens.consume(raw);
        } catch (InvalidTokenException ex) {
            return ex.getMessage();
        }
        throw new AssertionError("The token should have been refused: " + raw);
    }
}
