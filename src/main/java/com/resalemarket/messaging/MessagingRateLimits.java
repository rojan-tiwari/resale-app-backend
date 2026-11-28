package com.resalemarket.messaging;

import com.resalemarket.common.ratelimit.RateLimiter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Per-member limits on messaging, so that nobody can write to hundreds of sellers or flood a conversation in a loop. 429 with {@code Retry-After}. */
@Component
class MessagingRateLimits {

    private final RateLimiter start;
    private final RateLimiter send;
    private final RateLimiter poll;

    MessagingRateLimits(MessagingProperties properties, Clock clock) {
        this.start = new RateLimiter(properties.rateLimits().start(), clock);
        this.send = new RateLimiter(properties.rateLimits().send(), clock);
        this.poll = new RateLimiter(properties.rateLimits().poll(), clock);
    }

    /** Counts one conversation started by this member. Opening a conversation that already exists is not counted. */
    void start(UUID userId) {
        start.check(userId.toString());
    }

    /** Counts one message sent by this member, whichever conversation it is in and whether or not it is accepted. */
    void send(UUID userId) {
        send.check(userId.toString());
    }

    /** Counts one call of the kind an open chat repeats every few seconds. The limit is generous: several tabs fit, a script does not. */
    void poll(UUID userId) {
        poll.check(userId.toString());
    }
}
