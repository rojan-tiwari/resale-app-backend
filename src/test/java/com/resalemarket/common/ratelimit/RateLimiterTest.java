package com.resalemarket.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.resalemarket.common.exception.TooManyRequestsException;
import com.resalemarket.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final RateLimiter limiter = new RateLimiter(new RateLimit(3, Duration.ofMinutes(10)), clock);

    private TooManyRequestsException refusal(String key) {
        return catchThrowableOfType(TooManyRequestsException.class, () -> limiter.check(key));
    }

    @Test
    void allowsTheConfiguredNumberOfAttempts() {
        limiter.check("203.0.113.7");
        limiter.check("203.0.113.7");
        limiter.check("203.0.113.7");
    }

    @Test
    void refusesTheNextAttemptAndSaysHowLongToWait() {
        for (int i = 0; i < 3; i++) {
            limiter.check("203.0.113.7");
        }

        TooManyRequestsException refused = refusal("203.0.113.7");

        assertThat(refused).isNotNull();
        assertThat(refused.getStatus().value()).isEqualTo(429);
        assertThat(refused.getCode().name()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(refused.getRetryAfter()).isEqualTo(Duration.ofMinutes(10));
        assertThat(refused.retryAfterSeconds()).isEqualTo(600);
        assertThat(refused.getMessage()).isEqualTo("Too many attempts. Please try again in 10 minutes.");
    }

    @Test
    void theWaitShrinksAsTimePasses() {
        for (int i = 0; i < 3; i++) {
            limiter.check("a");
        }
        clock.advance(Duration.ofMinutes(4));

        assertThat(refusal("a").getRetryAfter()).isEqualTo(Duration.ofMinutes(6));

        clock.advance(Duration.ofMinutes(5).plusSeconds(30));
        TooManyRequestsException almostThere = refusal("a");
        assertThat(almostThere.getRetryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(almostThere.getMessage()).isEqualTo("Too many attempts. Please try again in 30 seconds.");
    }

    @Test
    void theWindowEndsOnScheduleAndTheKeyStartsAgain() {
        for (int i = 0; i < 3; i++) {
            limiter.check("a");
        }
        assertThat(refusal("a")).isNotNull();

        clock.advance(Duration.ofMinutes(10));

        limiter.check("a");
        limiter.check("a");
        limiter.check("a");
        assertThat(refusal("a")).isNotNull();
    }

    @Test
    void keepingOnTryingDoesNotExtendTheWait() {
        for (int i = 0; i < 3; i++) {
            limiter.check("a");
        }
        clock.advance(Duration.ofMinutes(1));
        for (int i = 0; i < 20; i++) {
            assertThat(refusal("a")).isNotNull();
        }
        clock.advance(Duration.ofMinutes(9));

        limiter.check("a");   // ten minutes after the first attempt, however hard it was hammered in between
    }

    @Test
    void everyKeyHasItsOwnCount() {
        for (int i = 0; i < 3; i++) {
            limiter.check("203.0.113.7");
        }

        assertThat(refusal("203.0.113.7")).isNotNull();
        limiter.check("198.51.100.9");   // someone else is not affected
    }

    @Test
    void aRoundedUpWaitIsNeverLessThanOneSecond() {
        assertThat(new TooManyRequestsException(Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
        assertThat(new TooManyRequestsException(Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
        assertThat(new TooManyRequestsException(Duration.ofMillis(1001)).retryAfterSeconds()).isEqualTo(2);
        assertThat(new TooManyRequestsException(Duration.ofSeconds(1)).getMessage())
                .isEqualTo("Too many attempts. Please try again in 1 second.");
        assertThat(new TooManyRequestsException(Duration.ofSeconds(61)).getMessage())
                .isEqualTo("Too many attempts. Please try again in 2 minutes.");
    }

    @Test
    void simultaneousAttemptsNeverLetMoreThanTheLimitThrough() throws Exception {
        RateLimiter strict = new RateLimiter(new RateLimit(10, Duration.ofMinutes(1)), clock);
        int callers = 64;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Void>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<Void> attempt = () -> {
                    start.await();
                    try {
                        strict.check("same-key");
                        allowed.incrementAndGet();
                    } catch (TooManyRequestsException refused) {
                        // expected for most of them
                    }
                    return null;
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            for (Future<Void> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(10);
    }

    @Test
    void aRuleMustAllowAtLeastOneAttemptInAPositiveWindow() {
        assertThatThrownBy(() -> new RateLimit(0, Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit(1, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimit(1, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
