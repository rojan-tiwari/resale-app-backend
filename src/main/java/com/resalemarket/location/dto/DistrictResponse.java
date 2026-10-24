package com.resalemarket.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A district of Nepal")
public record DistrictResponse(
        @Schema(example = "25") Integer id,
        @Schema(example = "3") Integer provinceId,
        @Schema(example = "Kathmandu") String name,
        @Schema(example = "kathmandu") String slug) {
}
