package com.resalemarket.listing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "One photo of a listing")
public record ListingImageResponse(
        @Schema(example = "0b8f0a54-6a52-4d3c-9f43-0a3d4a8d6c11") UUID id,
        @Schema(description = "Where to load the picture from (same origin as the API; usable in an img tag)",
                        example = "/api/v1/listings/7c9e6679-7425-40de-944b-e07fc1f90ae7/images/0b8f0a54-6a52-4d3c-9f43-0a3d4a8d6c11")
                String url,
        @Schema(description = "Pixels, from the file header", example = "1200") Integer width,
        @Schema(example = "900") Integer height,
        @Schema(description = "Position in the gallery, starting at 0", example = "0") int position,
        @Schema(description = "The main photo shown on cards; always the one at position 0") boolean primary) {
}
