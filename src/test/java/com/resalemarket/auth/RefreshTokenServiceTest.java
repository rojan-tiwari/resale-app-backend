package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resalemarket.auth.entity.RefreshToken;
import com.resalemarket.auth.entity.RevocationReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
class RefreshTokenServiceTest {

    private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "Mozilla/5.0");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID FAMILY = UUID.randomUUID();

    @Mock
    private RefreshTokenRepository repository;

    private final TestClock clock = new TestClock(START);
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(
                Duration.ofDays(14),
                Duration.ofSeconds(10),
                new AuthProperties.RefreshCookie("refresh_token", true, "Strict", "/api/v1/auth"));
        service = new RefreshTokenService(repository, properties, clock);
    }

    /** Gives saved tokens an id the way the database would. */
    private void saveAssignsIds() {
        when(repository.saveAndFlush(any(RefreshToken.class))).thenAnswer(call -> {
            RefreshToken token = call.getArgument(0);
            ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
            return token;
        });
    }

    private RefreshToken stored(String rawToken, Instant issuedAt, Instant expiresAt) {
        RefreshToken token = RefreshToken.issue(USER, FAMILY, RefreshTokenService.hash(rawToken), issuedAt, expiresAt, null, null);
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        when(repository.findByTokenHash(RefreshTokenService.hash(rawToken))).thenReturn(Optional.of(token));
        return token;
    }

    private RefreshToken storedActive(String rawToken) {
        return stored(rawToken, START.minus(Duration.ofDays(1)), START.plus(Duration.ofDays(13)));
    }

    private RefreshToken storedRotated(String rawToken, Duration rotatedAgo) {
        RefreshToken token = storedActive(rawToken);
        token.rotate(START.minus(rotatedAgo), UUID.randomUUID());
        return token;
    }

    // --- start ---------------------------------------------------------------------------------------------------

    @Test
    void startStoresOnlyTheHashOfANewTokenInANewFamily() {
        saveAssignsIds();

        String raw = service.start(USER, CLIENT);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).saveAndFlush(saved.capture());
        RefreshToken token = saved.getValue();
        assertThat(raw).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(token.getTokenHash()).hasSize(64).isEqualTo(RefreshTokenService.hash(raw)).isNotEqualTo(raw);
        assertThat(token.getUserId()).isEqualTo(USER);
        assertThat(token.getFamilyId()).isNotNull();
        assertThat(token.getIssuedAt()).isEqualTo(START);
        assertThat(token.getExpiresAt()).isEqualTo(START.plus(Duration.ofDays(14)));
        assertThat(token.getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(token.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(token.isRevoked()).isFalse();
    }

    @Test
    void everySignInGetsADifferentTokenAndFamily() {
        saveAssignsIds();

        String first = service.start(USER, CLIENT);
        String second = service.start(USER, CLIENT);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
        assertThat(first).isNotEqualTo(second);
        assertThat(saved.getAllValues().get(0).getFamilyId()).isNotEqualTo(saved.getAllValues().get(1).getFamilyId());
    }

    // --- rotate: the normal path ---------------------------------------------------------------------------------

    @Test
    void rotatingReplacesTheTokenAndKeepsTheFamily() {
        saveAssignsIds();
        RefreshToken old = storedActive("old-token");

        Optional<RefreshTokenService.Rotation> rotation = service.rotate("old-token", CLIENT);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).saveAndFlush(saved.capture());
        RefreshToken successor = saved.getValue();
        assertThat(rotation).isPresent();
        assertThat(rotation.get().userId()).isEqualTo(USER);
        assertThat(rotation.get().token()).isNotNull().isNotEqualTo("old-token");
        assertThat(successor.getTokenHash()).isEqualTo(RefreshTokenService.hash(rotation.get().token()));
        assertThat(successor.getFamilyId()).isEqualTo(FAMILY);
        assertThat(successor.getExpiresAt()).isEqualTo(START.plus(Duration.ofDays(14)));
        assertThat(old.isRevoked()).isTrue();
        assertThat(old.getRevokedReason()).isEqualTo(RevocationReason.ROTATED);
        assertThat(old.getRevokedAt()).isEqualTo(START);
        assertThat(old.getReplacedById()).isEqualTo(successor.getId());
    }

    // --- rotate: refusals ----------------------------------------------------------------------------------------

    @Test
    void anUnknownTokenIsRefused() {
        when(repository.findByTokenHash(RefreshTokenService.hash("who-knows"))).thenReturn(Optional.empty());

        assertThat(service.rotate("who-knows", CLIENT)).isEmpty();

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void anExpiredTokenIsRefusedAndNothingIsIssued() {
        RefreshToken expired = stored("expired", START.minus(Duration.ofDays(15)), START.minus(Duration.ofDays(1)));

        assertThat(service.rotate("expired", CLIENT)).isEmpty();

        assertThat(expired.isRevoked()).isFalse();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aTokenThatWasSignedOutIsRefusedWithoutRevokingAnythingElse() {
        RefreshToken token = storedActive("signed-out");
        token.revoke(RevocationReason.LOGOUT, START.minus(Duration.ofHours(1)));

        assertThat(service.rotate("signed-out", CLIENT)).isEmpty();

        verify(repository, never()).revokeActiveInFamily(any(), any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    // --- rotate: reuse of a token that was already exchanged -----------------------------------------------------

    @Test
    void reusingARotatedTokenAfterTheGraceWindowRevokesTheWholeSession() {
        storedRotated("stolen", Duration.ofMinutes(5));
        when(repository.existsByFamilyIdAndRevokedAtIsNull(FAMILY)).thenReturn(true);
        when(repository.revokeActiveInFamily(FAMILY, START, RevocationReason.REUSE_DETECTED)).thenReturn(1);

        assertThat(service.rotate("stolen", CLIENT)).isEmpty();

        verify(repository).revokeActiveInFamily(FAMILY, START, RevocationReason.REUSE_DETECTED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void reusingARotatedTokenJustAfterTheGraceWindowEndsIsTheft() {
        storedRotated("late", Duration.ofSeconds(10));
        when(repository.existsByFamilyIdAndRevokedAtIsNull(FAMILY)).thenReturn(true);

        assertThat(service.rotate("late", CLIENT)).isEmpty();

        verify(repository).revokeActiveInFamily(FAMILY, START, RevocationReason.REUSE_DETECTED);
    }

    @Test
    void reuseWithinTheGraceWindowIsATabRaceNotTheft() {
        storedRotated("raced", Duration.ofSeconds(3));
        when(repository.existsByFamilyIdAndRevokedAtIsNull(FAMILY)).thenReturn(true);

        Optional<RefreshTokenService.Rotation> rotation = service.rotate("raced", CLIENT);

        assertThat(rotation).isPresent();
        assertThat(rotation.get().userId()).isEqualTo(USER);
        assertThat(rotation.get().token()).as("no new refresh token: the winning tab's cookie must stay").isNull();
        verify(repository, never()).revokeActiveInFamily(any(), any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theGraceWindowDoesNotApplyOnceTheSessionWasEnded() {
        storedRotated("signed-out-meanwhile", Duration.ofSeconds(3));
        when(repository.existsByFamilyIdAndRevokedAtIsNull(FAMILY)).thenReturn(false);

        assertThat(service.rotate("signed-out-meanwhile", CLIENT)).isEmpty();

        verify(repository, never()).revokeActiveInFamily(any(), any(), any());
    }

    @Test
    void reusingAnOldTokenOfAnAlreadyEndedSessionIsRefusedQuietly() {
        storedRotated("old", Duration.ofDays(2));
        when(repository.existsByFamilyIdAndRevokedAtIsNull(FAMILY)).thenReturn(false);

        assertThat(service.rotate("old", CLIENT)).isEmpty();

        verify(repository, never()).revokeActiveInFamily(any(), any(), any());
    }

    // --- revoke ---------------------------------------------------------------------------------------------------

    @Test
    void signingOutRevokesTheWholeSession() {
        storedActive("current");

        service.revoke("current");

        verify(repository).revokeActiveInFamily(FAMILY, START, RevocationReason.LOGOUT);
    }

    @Test
    void signingOutWithAnUnknownTokenDoesNothing() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        service.revoke("unknown");

        verify(repository, never()).revokeActiveInFamily(any(), any(), eq(RevocationReason.LOGOUT));
    }

    @Test
    void signingOutOfAllDevicesRevokesEveryOpenTokenOfTheUser() {
        when(repository.revokeActiveForUser(USER, START, RevocationReason.LOGOUT)).thenReturn(3);

        assertThat(service.revokeAll(USER)).isEqualTo(3);

        verify(repository).revokeActiveForUser(USER, START, RevocationReason.LOGOUT);
        verify(repository, never()).revokeActiveInFamily(any(), any(), any());
    }

    @Test
    void hashesAreStableLowerCaseHex() {
        assertThat(RefreshTokenService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    /** A clock the test can read but never moves by itself. */
    private static final class TestClock extends Clock {

        private final Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
