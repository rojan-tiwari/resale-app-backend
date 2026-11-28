package com.resalemarket.auth.entity;

/** Why a refresh token stopped being valid; mirrors the {@code chk_refresh_tokens_reason} constraint. */
public enum RevocationReason {
    /** Exchanged for a new token (the normal end of every token). */
    ROTATED,
    /** The person signed out. */
    LOGOUT,
    /** A token that had already been exchanged was presented again: the session may be stolen, so all of it is revoked. */
    REUSE_DETECTED,
    PASSWORD_CHANGED,
    ADMIN
}
