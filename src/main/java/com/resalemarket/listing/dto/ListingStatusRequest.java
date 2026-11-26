package com.resalemarket.listing.dto;

import com.resalemarket.listing.entity.ListingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "The status to move a listing to. The moves allowed to the caller are listed in `allowedStatuses` of the listing.")
public record ListingStatusRequest(
        @Schema(example = "ACTIVE") @NotNull ListingStatus status,
        @Schema(description = "Required when rejecting a listing (administrators); at most 500 characters", nullable = true)
                @Size(max = 500, message = "must be at most 500 characters")
                String reason) {

    public ListingStatusRequest {
        reason = reason == null || reason.isBlank() ? null : reason.strip();
    }
}
