package com.resalemarket.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A province of Nepal")
public record ProvinceResponse(
        @Schema(example = "3") Integer id,
        @Schema(example = "Bagmati") String name,
        @Schema(example = "bagmati") String slug,
        @Schema(description = "Nepali name (for the future localisation)", example = "बागमती प्रदेश") String nameNe) {
}
