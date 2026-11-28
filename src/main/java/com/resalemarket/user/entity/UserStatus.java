package com.resalemarket.user.entity;

/** Account state; mirrors the {@code chk_users_status} constraint. Accounts are soft-deleted, never removed. */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    DELETED
}
