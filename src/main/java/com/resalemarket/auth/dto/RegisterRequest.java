package com.resalemarket.auth.dto;

import com.resalemarket.common.validation.MaxUtf8Bytes;
import com.resalemarket.user.NepalPhone;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Registration of a new account")
public record RegisterRequest(
        @Schema(example = "sita.sharma@example.com")
                @NotBlank
                @Size(max = 254, message = "must be at most 254 characters")
                @Email(regexp = ".+@.+\\..+", message = "must be a valid email address")
                String email,
        @Schema(description = "At least 8 characters and at most 72 bytes in UTF-8", example = "correct horse battery", accessMode = Schema.AccessMode.WRITE_ONLY)
                @NotBlank
                @Size(min = 8, message = "must be at least 8 characters")
                @MaxUtf8Bytes(72)
                String password,
        @Schema(description = "Shown to other users", example = "Sita Sharma")
                @NotBlank
                @Size(min = 2, max = 80, message = "must be between 2 and 80 characters")
                String displayName,
        @Schema(description = "Optional Nepali mobile number, with or without +977", example = "9812345678", nullable = true)
                @Pattern(regexp = NepalPhone.INPUT_PATTERN, message = "must be a Nepali mobile number such as 9812345678")
                String phone) {

    /**
     * Surrounding whitespace is dropped before validation, so a name of " A " is rejected as too short rather than
     * accepted and then refused by the database. The password is kept exactly as typed.
     */
    public RegisterRequest {
        email = email == null ? null : email.strip();
        displayName = displayName == null ? null : displayName.strip();
        phone = phone == null ? null : phone.strip();
    }

    /** Keeps the password out of logs. */
    @Override
    public String toString() {
        return "RegisterRequest[displayName=" + displayName + "]";
    }
}
