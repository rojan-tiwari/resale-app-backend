package com.resalemarket.user.dto;

import com.resalemarket.user.NepalPhone;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "The editable part of an account. The whole profile is replaced: an optional field that is left out or null is cleared.")
public record UpdateProfileRequest(
        @Schema(description = "Shown to other users", example = "Sita Sharma")
                @NotBlank
                @Size(min = 2, max = 80, message = "must be between 2 and 80 characters")
                String displayName,
        @Schema(description = "Optional Nepali mobile number, with or without +977", example = "9812345678", nullable = true)
                @Pattern(regexp = NepalPhone.INPUT_PATTERN, message = "must be a Nepali mobile number such as 9812345678")
                String phone,
        @Schema(description = "Show the phone number on the public profile. Only allowed together with a phone number. "
                        + "Missing or null means false.", defaultValue = "false")
                Boolean showPhone,
        @Schema(description = "Up to 500 characters", nullable = true)
                @Size(max = 500, message = "must be at most 500 characters")
                String bio,
        @Schema(example = "3", nullable = true) @Positive Integer provinceId,
        @Schema(description = "Must belong to the province", example = "25", nullable = true) @Positive Integer districtId,
        @Schema(description = "Must belong to the district", example = "1", nullable = true) @Positive Integer municipalityId) {

    /**
     * Surrounding whitespace is dropped before validation (as in registration), and an empty phone or bio means "none".
     * {@code showPhone} is a wrapper on purpose: Jackson 3 refuses a missing or null primitive {@code boolean} as an
     * unreadable body, and a client that leaves the flag out should get the documented "left out means cleared" instead.
     */
    public UpdateProfileRequest {
        showPhone = Boolean.TRUE.equals(showPhone);
        displayName = displayName == null ? null : displayName.strip();
        phone = blankToNull(phone);
        bio = blankToNull(bio);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
