package com.resalemarket.listing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "The new order of the photos: every image id of the listing exactly once. The first one becomes the main photo.")
public record ReorderImagesRequest(
        @NotEmpty @Size(max = 8, message = "must list at most 8 images") List<@NotNull UUID> imageIds) {
}
