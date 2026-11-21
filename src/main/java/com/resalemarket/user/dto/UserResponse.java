package com.resalemarket.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "An account as seen by its owner. Never contains the password or its hash.")
public record UserResponse(
        @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") UUID id,
        @Schema(description = "Always lower-case", example = "sita.sharma@example.com") String email,
        @Schema(example = "Sita Sharma") String displayName,
        @Schema(description = "E.164 Nepali mobile number, or null", example = "+9779812345678", nullable = true)
                String phone,
        @Schema(description = "Whether the phone number is shown on the public profile (only ever true with a phone number)")
                boolean showPhone,
        @Schema(description = "A few words about the person, or null", nullable = true) String bio,
        @Schema(description = "Id of the province from /locations/provinces, or null", example = "3", nullable = true)
                Integer provinceId,
        @Schema(description = "Id of the district, or null; only set together with a province", example = "25", nullable = true)
                Integer districtId,
        @Schema(description = "Id of the municipality, or null; only set together with a district", example = "1", nullable = true)
                Integer municipalityId,
        @Schema(description = "UTC", example = "2026-10-20T08:15:00Z") Instant createdAt,
        @Schema(description = "Role names, sorted", example = "[\"ROLE_USER\"]") List<String> roles) {
}
