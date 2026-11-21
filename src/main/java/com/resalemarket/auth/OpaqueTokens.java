package com.resalemarket.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The one way this module creates and stores secret tokens (refresh tokens, password-reset tokens): 256 random bits handed
 * to the person, and only their SHA-256 hash kept in the database, so a leaked table cannot be used to sign in or reset a password.
 * SHA-256 (not BCrypt) is right here because the input is already unguessable; there is nothing to brute-force.
 */
final class OpaqueTokens {

    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private OpaqueTokens() {
    }

    /** A new random token: 43 URL-safe characters, so it can go into a link or a cookie unescaped. */
    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 as lower-case hex: 64 characters, the width of the {@code token_hash} columns. */
    static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime.", ex);
        }
    }
}
