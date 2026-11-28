package com.resalemarket.messaging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Schema(description = "Write to the seller of a listing. Asking again for a listing you already wrote about returns that conversation.")
public record StartConversationRequest(
        @Schema(description = "The listing the conversation is about; it must be live and not your own", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        @NotNull(message = "Choose the ad you want to ask about.") UUID listingId) {
}
