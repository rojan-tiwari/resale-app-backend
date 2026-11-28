package com.resalemarket.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds and reads the refresh-token cookie. It is {@code HttpOnly} (scripts cannot read it, so an XSS bug cannot
 * steal the session), {@code Secure} outside development, {@code SameSite} and limited to the authentication path.
 */
@Component
class RefreshCookies {

    private final AuthProperties.RefreshCookie settings;
    private final Duration lifetime;

    RefreshCookies(AuthProperties properties) {
        this.settings = properties.refreshCookie();
        this.lifetime = properties.refreshTokenTtl();
    }

    ResponseCookie issue(String token) {
        return builder(token).maxAge(lifetime).build();
    }

    /** An expired cookie with the same name and path, which makes the browser delete the real one. */
    ResponseCookie clear() {
        return builder("").maxAge(Duration.ZERO).build();
    }

    /** The refresh token the browser sent, or {@code null} when there is none. */
    String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (settings.name().equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder builder(String value) {
        return ResponseCookie.from(settings.name(), value)
                .httpOnly(true)
                .secure(settings.secure())
                .sameSite(settings.sameSite())
                .path(settings.path());
    }
}
