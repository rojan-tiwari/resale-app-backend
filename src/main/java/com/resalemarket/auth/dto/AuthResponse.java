package com.resalemarket.auth.dto;

import com.resalemarket.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A signed-in session. The refresh token is not part of the body: it travels in an HttpOnly cookie.")
public record AuthResponse(
        @Schema(description = "JWT to send as `Authorization: Bearer <token>`. Keep it in memory only.") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Seconds until the access token expires", example = "900") long expiresIn,
        UserResponse user) {

    public static final String BEARER = "Bearer";

    /** Keeps the token out of logs. */
    @Override
    public String toString() {
        return "AuthResponse[user=" + (user == null ? null : user.id()) + "]";
    }
}
