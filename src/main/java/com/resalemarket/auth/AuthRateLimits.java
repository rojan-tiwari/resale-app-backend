package com.resalemarket.auth;

import com.resalemarket.common.ratelimit.RateLimiter;
import com.resalemarket.user.entity.User;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Which caller each public authentication endpoint counts attempts for, and the limits (from {@link AuthProperties.RateLimits}).
 * A refused attempt becomes a 429 with {@code Retry-After}. The controllers call these before doing any work.
 *
 * <ul>
 *   <li>Endpoints that anyone can call are counted per <b>IP address</b>. The address comes from
 *       {@code HttpServletRequest.getRemoteAddr()}; behind a reverse proxy the server must be told to trust it
 *       ({@code server.forward-headers-strategy=native}), otherwise every visitor looks like the proxy and shares one limit.</li>
 *   <li>Forgot-password is also counted per <b>e-mail address</b>, so one inbox cannot be flooded from many addresses.
 *       The count does not depend on whether the address has an account.</li>
 *   <li>Change-password is counted per <b>signed-in user</b>, because the caller is known there and the address is not the point.</li>
 * </ul>
 * Guessing one account's password across many addresses is stopped by the lock-out, not by these limits.
 */
@Component
class AuthRateLimits {

    private static final String UNKNOWN_CLIENT = "unknown";

    private final RateLimiter login;
    private final RateLimiter register;
    private final RateLimiter forgotPasswordByIp;
    private final RateLimiter forgotPasswordByEmail;
    private final RateLimiter resetPassword;
    private final RateLimiter changePassword;

    AuthRateLimits(AuthProperties properties, Clock clock) {
        AuthProperties.RateLimits limits = properties.rateLimits();
        this.login = new RateLimiter(limits.login(), clock);
        this.register = new RateLimiter(limits.register(), clock);
        this.forgotPasswordByIp = new RateLimiter(limits.forgotPasswordByIp(), clock);
        this.forgotPasswordByEmail = new RateLimiter(limits.forgotPasswordByEmail(), clock);
        this.resetPassword = new RateLimiter(limits.resetPassword(), clock);
        this.changePassword = new RateLimiter(limits.changePassword(), clock);
    }

    void login(ClientInfo client) {
        login.check(ipOf(client));
    }

    void register(ClientInfo client) {
        register.check(ipOf(client));
    }

    void forgotPassword(ClientInfo client, String email) {
        forgotPasswordByIp.check(ipOf(client));
        forgotPasswordByEmail.check(User.normaliseEmail(email));
    }

    void resetPassword(ClientInfo client) {
        resetPassword.check(ipOf(client));
    }

    void changePassword(UUID userId) {
        changePassword.check(userId.toString());
    }

    private static String ipOf(ClientInfo client) {
        return client.ipAddress() == null ? UNKNOWN_CLIENT : client.ipAddress();
    }
}
