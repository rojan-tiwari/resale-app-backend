package com.resalemarket.user.dto;

import com.resalemarket.location.dto.LocationNames;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "What anyone may see about a person (a seller's profile). No e-mail address; the phone number only when its owner chose to show it.")
public record PublicUserResponse(
        @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") UUID id,
        @Schema(example = "Sita Sharma") String displayName,
        @Schema(nullable = true) String bio,
        @Schema(description = "When the account was created (UTC)", example = "2026-10-20T08:15:00Z") Instant memberSince,
        LocationNames location,
        @Schema(description = "E.164 number; null unless its owner agreed to show it", example = "+9779812345678", nullable = true)
                String phone) {
}
