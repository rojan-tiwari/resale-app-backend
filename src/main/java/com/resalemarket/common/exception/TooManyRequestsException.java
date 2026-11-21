package com.resalemarket.common.exception;

import java.time.Duration;
import org.springframework.http.HttpStatus;

/** A rate limit was exceeded. Answered as 429 with a {@code Retry-After} header telling the client how long to wait. */
public class TooManyRequestsException extends ApiException {

    private final Duration retryAfter;

    public TooManyRequestsException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS, messageFor(retryAfter));
        this.retryAfter = retryAfter;
    }

    /** How long until the caller may try again; at least one second. */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /** The {@code Retry-After} header value: whole seconds, rounded up. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }

    private static String messageFor(Duration wait) {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        if (seconds < 60) {
            return "Too many attempts. Please try again in %d %s.".formatted(seconds, seconds == 1 ? "second" : "seconds");
        }
        long minutes = (seconds + 59) / 60;
        return "Too many attempts. Please try again in %d %s.".formatted(minutes, minutes == 1 ? "minute" : "minutes");
    }
}
