package com.resalemarket.messaging;

import com.resalemarket.common.ratelimit.RateLimit;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Messaging settings, bound from {@code app.messaging.*}.
 *
 * @param maxMessageLength how many characters one message may have; the column holds 2000, so a larger value could not be stored
 * @param duplicateWindow  how long after a message the same person may not send the very same text again as their next message
 *                         ({@code 0s} turns the check off)
 * @param rateLimits       how often one member may do the things that can be abused
 */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
        @DefaultValue("2000") int maxMessageLength, @DefaultValue("1m") Duration duplicateWindow, @DefaultValue RateLimits rateLimits) {

    /** The most the {@code messages.body} column can hold. */
    public static final int COLUMN_LIMIT = 2000;

    public MessagingProperties {
        if (maxMessageLength < 1 || maxMessageLength > COLUMN_LIMIT) {
            throw new IllegalArgumentException("app.messaging.max-message-length must be between 1 and " + COLUMN_LIMIT + ".");
        }
        if (duplicateWindow == null || duplicateWindow.isNegative()) {
            throw new IllegalArgumentException("app.messaging.duplicate-window must not be negative.");
        }
        rateLimits = rateLimits != null ? rateLimits : new RateLimits(null, null);   // @DefaultValue only applies when binding from configuration
    }

    /**
     * @param start starting a conversation with a seller who has not heard from the member about that ad yet (counted per member)
     * @param send  sending messages, in all conversations together (counted per member)
     */
    public record RateLimits(RateLimit start, RateLimit send) {

        public RateLimits {
            start = start != null ? start : new RateLimit(20, Duration.ofDays(1));
            send = send != null ? send : new RateLimit(30, Duration.ofMinutes(5));
        }
    }
}
