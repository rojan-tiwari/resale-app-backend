package com.resalemarket.listing.dto;

import com.resalemarket.category.dto.CategorySummary;
import com.resalemarket.listing.entity.ListingCondition;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.listing.entity.PriceType;
import com.resalemarket.location.dto.LocationNames;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A listing as shown on a card: enough to browse, nothing private")
public record ListingSummaryResponse(
        UUID id,
        @Schema(example = "iPhone 13, 128 GB, with box") String title,
        @Schema(description = "NPR, two decimals", example = "85000.00") BigDecimal price,
        @Schema(example = "NPR") String currency,
        PriceType priceType,
        ListingCondition condition,
        ListingStatus status,
        CategorySummary category,
        @Schema(nullable = true) CategorySummary subcategory,
        LocationNames location,
        @Schema(description = "Free-text area", nullable = true) String locality,
        @Schema(description = "The main photo, or null when there is none yet", nullable = true) ListingImageResponse primaryImage,
        @Schema(description = "A promotion is running") boolean featured,
        @Schema(description = "UTC") Instant createdAt,
        @Schema(description = "When it went live (UTC); null for a listing that never did", nullable = true) Instant publishedAt) {
}
