package com.resalemarket.location.dto;

import com.resalemarket.location.entity.MunicipalityType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A city or municipality")
public record MunicipalityResponse(
        @Schema(example = "1") Integer id,
        @Schema(example = "25") Integer districtId,
        @Schema(example = "Kathmandu") String name,
        @Schema(example = "kathmandu") String slug,
        MunicipalityType type,
        @Schema(description = "Major cities are listed first in location pickers") boolean major) {
}
