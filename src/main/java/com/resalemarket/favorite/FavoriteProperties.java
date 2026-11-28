package com.resalemarket.favorite;

import com.resalemarket.common.ratelimit.RateLimit;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Favourites settings, bound from {@code app.favorite.*}.
 *
 * @param maxPerUser how many listings one member may save; it also bounds {@code GET /favorites/ids}, which the web app loads once
 *                   after sign-in to show the heart on every card
 * @param rateLimits how often one member may save and unsave
 */
@ConfigurationProperties(prefix = "app.favorite")
public record FavoriteProperties(@DefaultValue("500") int maxPerUser, @DefaultValue RateLimits rateLimits) {

    public FavoriteProperties {
        if (maxPerUser < 1) {
            throw new IllegalArgumentException("app.favorite.max-per-user must be at least 1.");
        }
        rateLimits = rateLimits != null ? rateLimits : new RateLimits(null);   // @DefaultValue only applies when binding from configuration
    }

    /** @param write adding and removing, counted together per signed-in member */
    public record RateLimits(RateLimit write) {

        public RateLimits {
            write = write != null ? write : new RateLimit(300, Duration.ofHours(1));
        }
    }
}
