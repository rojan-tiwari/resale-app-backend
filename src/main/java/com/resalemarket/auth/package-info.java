/**
 * <b>Authentication</b> (Phase 2, in progress: registration, login, refresh and logout are built).
 *
 * <p>Registration, login, logout, JWT access tokens, rotating refresh tokens (HttpOnly cookie, hashed at rest, re-use
 * detection per token family) are done; password reset with expiring single-use tokens and login rate limiting follow.
 * Owns the {@code refresh_tokens} and {@code password_reset_tokens} tables.
 */
package com.resalemarket.auth;
