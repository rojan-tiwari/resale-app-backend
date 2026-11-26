package com.resalemarket.favorite.dto;

import com.resalemarket.listing.dto.ListingSummaryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A saved listing. An ad that is no longer for sale is flagged, not dropped silently.")
public record FavoriteResponse(
        @Schema(description = "The saved listing's id") UUID listingId,
        @Schema(description = "When it was saved (UTC)") Instant savedAt,
        @Schema(description = "true while the listing is live (ACTIVE or RESERVED) and can be opened") boolean available,
        @Schema(description = "The card. Present while the ad is live and for an ad that was sold (its `status` is SOLD); "
                        + "null for an ad that was taken down, so nothing is handed out that was meant to be private",
                nullable = true)
                ListingSummaryResponse listing) {
}
