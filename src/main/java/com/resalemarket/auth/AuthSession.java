package com.resalemarket.auth;

import com.resalemarket.auth.dto.AuthResponse;

/**
 * What the auth service hands to the controller after a sign-in or a refresh.
 *
 * @param response     the JSON body
 * @param refreshToken the new refresh token for the cookie, or {@code null} when the cookie must stay as it is
 */
public record AuthSession(AuthResponse response, String refreshToken) {

    /** Keeps the refresh token out of logs. */
    @Override
    public String toString() {
        return "AuthSession[" + response + "]";
    }
}
