/**
 * <b>Authentication</b> (delivered in Phase 2).
 *
 * <p>Registration, login, logout, JWT access tokens, rotating refresh tokens (HttpOnly cookie, hashed at rest,
 * re-use detection per token family), password reset with expiring single-use tokens, login rate limiting.
 * Owns the {@code refresh_tokens} and {@code password_reset_tokens} tables.
 */
package com.resalemarket.auth;
