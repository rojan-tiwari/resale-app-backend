package com.resalemarket.user.dto;

/**
 * What the {@code auth} module hands to {@link com.resalemarket.user.UserService} to create an account.
 * Carries a password <em>hash</em>, never the plain password; {@code phone} may be {@code null}.
 */
public record NewUser(String email, String passwordHash, String displayName, String phone) {

    /** Keeps the hash out of logs. */
    @Override
    public String toString() {
        return "NewUser[displayName=" + displayName + "]";
    }
}
