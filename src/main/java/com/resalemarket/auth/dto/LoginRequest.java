package com.resalemarket.auth.dto;

import com.resalemarket.common.validation.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Sign-in with an email address and password")
public record LoginRequest(
        @Schema(example = "sita.sharma@example.com")
                @NotBlank
                @Size(max = 254, message = "must be at most 254 characters")
                String email,
        @Schema(example = "correct horse battery", accessMode = Schema.AccessMode.WRITE_ONLY)
                @NotBlank
                @MaxUtf8Bytes(72)
                String password) {

    /**
     * The address is trimmed because lookups are on the trimmed, lower-case form; the password is kept exactly as typed.
     * Only the shape is checked here, not the e-mail format or password length: a wrong value must be answered like any
     * other wrong credential, not with a hint about the rules.
     */
    public LoginRequest {
        email = email == null ? null : email.strip();
    }

    /** Keeps the password out of logs. */
    @Override
    public String toString() {
        return "LoginRequest[]";
    }
}
