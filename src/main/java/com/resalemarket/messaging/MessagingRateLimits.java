package com.resalemarket.messaging;

import com.resalemarket.common.ratelimit.RateLimiter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Per-member limits on messaging, so that nobody can write to hundreds of sellers in a loop. 429 with {@code Retry-After}. */
@Component
class MessagingRateLimits {

    private final RateLimiter start;

    MessagingRateLimits(MessagingProperties properties, Clock clock) {
        this.start = new RateLimiter(properties.rateLimits().start(), clock);
    }

    /** Counts one conversation started by this member. Opening a conversation that already exists is not counted. */
    void start(UUID userId) {
        start.check(userId.toString());
    }
}
