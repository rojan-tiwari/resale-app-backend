package com.resalemarket.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The few facts about a category that a listing shows next to itself. */
@Schema(description = "A category, as named on a listing")
public record CategorySummary(
        @Schema(example = "1") Integer id,
        @Schema(example = "Mobiles & Tablets") String name,
        @Schema(example = "mobiles-tablets") String slug) {
}
