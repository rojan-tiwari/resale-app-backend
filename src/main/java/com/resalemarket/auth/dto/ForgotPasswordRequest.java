package com.resalemarket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Asks for a password-reset link to be sent to an address")
public record ForgotPasswordRequest(
        @Schema(example = "sita.sharma@example.com")
                @NotBlank
                @Size(max = 254, message = "must be at most 254 characters")
                @Email(regexp = ".+@.+\\..+", message = "must be a valid email address")
                String email) {

    public ForgotPasswordRequest {
        email = email == null ? null : email.strip();
    }
}
