package com.resalemarket.listing.dto;

import com.resalemarket.category.dto.CategorySummary;
import com.resalemarket.listing.entity.ListingCondition;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.listing.entity.PriceType;
import com.resalemarket.location.dto.LocationNames;
import com.resalemarket.user.dto.PublicUserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A listing in full, as the caller may see it")
public record ListingResponse(
        UUID id,
        @Schema(example = "iPhone 13, 128 GB, with box") String title,
        String description,
        @Schema(description = "NPR, two decimals", example = "85000.00") BigDecimal price,
        @Schema(example = "NPR") String currency,
        PriceType priceType,
        ListingCondition condition,
        ListingStatus status,
        @Schema(description = "Why a moderator rejected it; only shown to the seller and administrators", nullable = true)
                String rejectionReason,
        CategorySummary category,
        @Schema(nullable = true) CategorySummary subcategory,
        @Schema(example = "3") Integer provinceId,
        @Schema(example = "25") Integer districtId,
        @Schema(nullable = true, example = "1") Integer municipalityId,
        LocationNames location,
        @Schema(description = "Free-text area", nullable = true) String locality,
        @Schema(description = "A promotion is running") boolean featured,
        long viewCount,
        @Schema(description = "In gallery order; the first is the main photo") List<ListingImageResponse> images,
        @Schema(description = "The seller's public profile (the phone number only if they chose to show it)") PublicUserResponse seller,
        @Schema(description = "The statuses the caller may move this listing to now; empty for everyone but the seller and administrators")
                List<ListingStatus> allowedStatuses,
        @Schema(description = "UTC") Instant createdAt,
        @Schema(description = "UTC") Instant updatedAt,
        @Schema(nullable = true) Instant publishedAt,
        @Schema(nullable = true) Instant soldAt) {
}
