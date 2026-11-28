package com.resalemarket.listing.dto;

import com.resalemarket.listing.entity.ListingCondition;
import com.resalemarket.listing.entity.PriceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "What a seller writes about an item. Used to create a listing and to replace its content as a whole.")
public record ListingRequest(
        @Schema(description = "3 to 150 characters", example = "iPhone 13, 128 GB, with box")
                @NotBlank
                @Size(min = 3, max = 150, message = "must be between 3 and 150 characters")
                String title,
        @Schema(description = "10 to 5000 characters", example = "Bought last year, battery health 91%. No scratches.")
                @NotBlank
                @Size(min = 10, max = 5000, message = "must be between 10 and 5000 characters")
                String description,
        @Schema(description = "Asking price in NPR, at most two decimals; 0 is allowed (free)", example = "85000.00")
                @NotNull
                @DecimalMin(value = "0", message = "must be 0 or more")
                @Digits(integer = 10, fraction = 2, message = "must have at most 10 digits and 2 decimals")
                BigDecimal price,
        @Schema(description = "Missing or null means FIXED", defaultValue = "FIXED") PriceType priceType,
        @Schema(example = "LIKE_NEW") @NotNull ListingCondition condition,
        @Schema(description = "A top-level category from /categories", example = "1") @NotNull @Positive Integer categoryId,
        @Schema(description = "Required when the category has subcategories; must be one of its children", example = "2", nullable = true)
                @Positive Integer subcategoryId,
        @Schema(example = "3") @NotNull @Positive Integer provinceId,
        @Schema(description = "Must belong to the province", example = "25") @NotNull @Positive Integer districtId,
        @Schema(description = "Must belong to the district", example = "1", nullable = true) @Positive Integer municipalityId,
        @Schema(description = "Free-text area, up to 120 characters", example = "Baneshwor", nullable = true)
                @Size(max = 120, message = "must be at most 120 characters")
                String locality) {

    /** Surrounding whitespace is dropped before validation, an empty locality means none, and an omitted price type means FIXED. */
    public ListingRequest {
        title = title == null ? null : title.strip();
        description = description == null ? null : description.strip();
        priceType = priceType == null ? PriceType.FIXED : priceType;
        locality = locality == null || locality.isBlank() ? null : locality.strip();
    }
}
