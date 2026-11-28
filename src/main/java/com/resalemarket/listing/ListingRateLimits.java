package com.resalemarket.listing;

import com.resalemarket.common.ratelimit.RateLimiter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Per-user limits on the two listing actions a script could abuse; a refused call becomes a 429 with {@code Retry-After}. */
@Component
class ListingRateLimits {

    private final RateLimiter create;
    private final RateLimiter imageUpload;

    ListingRateLimits(ListingProperties properties, Clock clock) {
        this.create = new RateLimiter(properties.rateLimits().create(), clock);
        this.imageUpload = new RateLimiter(properties.rateLimits().imageUpload(), clock);
    }

    void create(UUID userId) {
        create.check(userId.toString());
    }

    void imageUpload(UUID userId) {
        imageUpload.check(userId.toString());
    }
}
