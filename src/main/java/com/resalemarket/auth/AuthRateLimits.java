package com.resalemarket.auth;

import com.resalemarket.common.ratelimit.RateLimiter;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Which caller each public authentication endpoint counts attempts for, and the limits (from {@link AuthProperties.RateLimits}).
 * A refused attempt becomes a 429 with {@code Retry-After}. The controllers call these before doing any work.
 *
 * <ul>
 *   <li>Endpoints that anyone can call are counted per <b>IP address</b>. The address comes from
 *       {@code HttpServletRequest.getRemoteAddr()}; behind a reverse proxy the server must be told to trust it
 *       ({@code server.forward-headers-strategy=native}), otherwise every visitor looks like the proxy and shares one limit.</li>
 * </ul>
 * Guessing one account's password across many addresses is stopped by the lock-out, not by these limits.
 */
@Component
class AuthRateLimits {

    private static final String UNKNOWN_CLIENT = "unknown";

    private final RateLimiter login;
    private final RateLimiter register;

    AuthRateLimits(AuthProperties properties, Clock clock) {
        AuthProperties.RateLimits limits = properties.rateLimits();
        this.login = new RateLimiter(limits.login(), clock);
        this.register = new RateLimiter(limits.register(), clock);
    }

    void login(ClientInfo client) {
        login.check(ipOf(client));
    }

    void register(ClientInfo client) {
        register.check(ipOf(client));
    }

    private static String ipOf(ClientInfo client) {
        return client.ipAddress() == null ? UNKNOWN_CLIENT : client.ipAddress();
    }
}
