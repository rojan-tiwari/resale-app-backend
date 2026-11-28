/**
 * <b>Authentication</b> (Phase 2, in progress).
 *
 * <p>Registration, login with account lock-out, logout and logout-everywhere, JWT access tokens, rotating refresh tokens (HttpOnly
 * cookie, hashed at rest, re-use detection per token family), password reset by an e-mailed single-use token, and rate limits on the public endpoints.
 * Owns the {@code refresh_tokens} and {@code password_reset_tokens} tables.
 */
package com.resalemarket.auth;
