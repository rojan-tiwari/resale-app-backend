package com.resalemarket.auth.dto;

import com.resalemarket.common.validation.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Sets a new password with the token from a password-reset link")
public record ResetPasswordRequest(
        @Schema(description = "The `token` from the link in the e-mail") @NotBlank @Size(max = 200, message = "is not a valid token") String token,
        @Schema(description = "At least 8 characters and at most 72 bytes in UTF-8", accessMode = Schema.AccessMode.WRITE_ONLY)
                @NotBlank
                @Size(min = 8, message = "must be at least 8 characters")
                @MaxUtf8Bytes(72)
                String newPassword) {

    public ResetPasswordRequest {
        token = token == null ? null : token.strip();
    }

    /** Keeps the token and the password out of logs. */
    @Override
    public String toString() {
        return "ResetPasswordRequest[]";
    }
}
