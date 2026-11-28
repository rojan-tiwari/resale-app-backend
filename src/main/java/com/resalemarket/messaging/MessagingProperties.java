package com.resalemarket.messaging;

import com.resalemarket.common.ratelimit.RateLimit;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Messaging settings, bound from {@code app.messaging.*}.
 *
 * @param rateLimits how often one member may do the things that can be abused
 */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(@DefaultValue RateLimits rateLimits) {

    public MessagingProperties {
        rateLimits = rateLimits != null ? rateLimits : new RateLimits(null);   // @DefaultValue only applies when binding from configuration
    }

    /** @param start starting a conversation with a seller who has not heard from the member about that ad yet (counted per member) */
    public record RateLimits(RateLimit start) {

        public RateLimits {
            start = start != null ? start : new RateLimit(20, Duration.ofDays(1));
        }
    }
}
