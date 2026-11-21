package com.resalemarket.common.ratelimit;

import java.time.Duration;

/**
 * A rule: at most {@code attempts} per {@code window}, counted from the first attempt in the window.
 *
 * @param attempts how many calls are allowed in one window; the next one is refused
 * @param window   how long a window lasts
 */
public record RateLimit(int attempts, Duration window) {

    public RateLimit {
        if (attempts < 1) {
            throw new IllegalArgumentException("A rate limit must allow at least one attempt.");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("A rate limit window must be positive.");
        }
    }
}
