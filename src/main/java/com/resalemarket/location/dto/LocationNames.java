package com.resalemarket.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The names behind a province / district / municipality choice, for showing a place without extra calls. Any part may be null. */
@Schema(description = "Names of a person's or ad's place; each part is null when it was not chosen")
public record LocationNames(
        @Schema(example = "Bagmati", nullable = true) String province,
        @Schema(example = "Kathmandu", nullable = true) String district,
        @Schema(example = "Kathmandu", nullable = true) String municipality) {

    public static final LocationNames NONE = new LocationNames(null, null, null);
}
