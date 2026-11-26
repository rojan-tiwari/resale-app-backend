package com.resalemarket.discovery.dto;

import com.resalemarket.listing.dto.ListingSummaryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Listings suggested on the home page, and the rule that picked them")
public record RecommendationsResponse(
        @Schema(description = "FAVOURITE_CATEGORIES: ads like the ones the member saved. POPULAR: the most viewed recent ads (visitors, and members who have saved nothing yet).")
                Basis basis,
        List<ListingSummaryResponse> listings) {

    public enum Basis {
        FAVOURITE_CATEGORIES,
        POPULAR
    }
}
