package com.resalemarket.auth.dto;

import com.resalemarket.common.validation.MaxUtf8Bytes;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Changes the password of the signed-in account")
public record ChangePasswordRequest(
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) @NotBlank @MaxUtf8Bytes(72) String currentPassword,
        @Schema(description = "At least 8 characters and at most 72 bytes in UTF-8; must differ from the current one", accessMode = Schema.AccessMode.WRITE_ONLY)
                @NotBlank
                @Size(min = 8, message = "must be at least 8 characters")
                @MaxUtf8Bytes(72)
                String newPassword) {

    /** Keeps both passwords out of logs. */
    @Override
    public String toString() {
        return "ChangePasswordRequest[]";
    }
}
