package com.resalemarket.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.resalemarket.common.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts attempts per key (an IP address, an e-mail address, a user id) and refuses the ones over the limit.
 * One instance enforces one {@link RateLimit}; keep a separate instance for every rule.
 *
 * <p>The window is a <b>fixed window that starts at the first attempt</b>: after {@code window} the counter is gone and the
 * key starts again. That is cheap and easy to reason about, at the price that a caller who times it well can make up to
 * twice the limit across a window boundary. For brute-force and e-mail-bomb protection that is acceptable.
 *
 * <p>Two properties worth knowing:
 * <ul>
 *   <li><b>The counters live in this JVM.</b> With several API instances each one counts for itself, so the effective
 *       limit is the configured one times the number of instances. A shared store (Redis, the database) is the upgrade
 *       path when the API is scaled out.</li>
 *   <li><b>Memory is bounded.</b> Counters expire with their window and at most {@link #MAX_KEYS} are kept; when a flood
 *       of distinct keys overflows that, the least recently useful ones are dropped, which means those keys are counted
 *       afresh (fail open) rather than the API running out of memory.</li>
 * </ul>
 */
public final class RateLimiter {

    /** Upper bound on tracked keys, so an attacker cannot grow the map by inventing keys. */
    static final long MAX_KEYS = 100_000;

    private final RateLimit rule;
    private final Clock clock;
    private final Cache<String, Window> windows;

    public RateLimiter(RateLimit rule, Clock clock) {
        this.rule = rule;
        this.clock = clock;
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(rule.window())
                // The same clock decides when a window ends and when its counter is evicted.
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .maximumSize(MAX_KEYS)
                .build();
    }

    /**
     * Records one attempt for {@code key}.
     *
     * @throws TooManyRequestsException if the key has already used up its attempts in the current window; the refused
     *     attempt counts too, but the window still ends on schedule, so hammering does not extend the wait
     */
    public void check(String key) {
        Instant now = clock.instant();
        Window window = windows.get(key, ignored -> new Window(now));
        if (window.attempts.incrementAndGet() > rule.attempts()) {
            Duration wait = Duration.between(now, window.start.plus(rule.window()));
            throw new TooManyRequestsException(wait.isNegative() ? Duration.ZERO : wait);
        }
    }

    private static final class Window {

        private final Instant start;
        private final AtomicInteger attempts = new AtomicInteger();

        private Window(Instant start) {
            this.start = start;
        }
    }
}
