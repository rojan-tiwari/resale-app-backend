package com.resalemarket.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A category with its subcategories (empty for a subcategory)")
public record CategoryResponse(
        @Schema(example = "1") Integer id,
        @Schema(description = "Id of the parent category; null for a top-level category", example = "1", nullable = true)
                Integer parentId,
        @Schema(example = "Mobiles & Tablets") String name,
        @Schema(example = "mobiles-tablets") String slug,
        @Schema(description = "Icon key for the frontend", example = "smartphone", nullable = true) String icon,
        @Schema(nullable = true) String description,
        @Schema(description = "Active subcategories in display order") List<CategoryResponse> children) {
}
