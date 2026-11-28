package com.resalemarket.favorite;

import com.resalemarket.common.ratelimit.RateLimiter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Per-member limit on saving and unsaving, so a script (or a heart clicked in a loop) cannot hammer the database. 429 with {@code Retry-After}. */
@Component
class FavoriteRateLimits {

    private final RateLimiter write;

    FavoriteRateLimits(FavoriteProperties properties, Clock clock) {
        this.write = new RateLimiter(properties.rateLimits().write(), clock);
    }

    void write(UUID userId) {
        write.check(userId.toString());
    }
}
