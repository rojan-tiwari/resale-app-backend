/**
 * <b>Authentication</b> (Phase 2, done).
 *
 * <p>Registration, login with account lock-out, logout and logout-everywhere, JWT access tokens, rotating refresh tokens (HttpOnly
 * cookie, hashed at rest, re-use detection per token family), password reset by an e-mailed single-use token, change password,
 * and rate limits on the public endpoints. Forgot, reset and change password live here (in {@code PasswordController}) because each
 * one ends sessions, which this module owns.
 * Owns the {@code refresh_tokens} and {@code password_reset_tokens} tables.
 */
package com.resalemarket.auth;
