package com.resalemarket.messaging.dto;

import com.resalemarket.listing.dto.ListingSummaryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A conversation about one listing, from the point of view of the signed-in member")
public record ConversationResponse(
        UUID id,
        UUID listingId,
        @Schema(description = "The listing is still for sale (live). False for a sold ad, which keeps its card, and for one that was taken down, which has none")
                boolean listingAvailable,
        @Schema(description = "The ad the conversation is about; null when it was taken down", nullable = true) ListingSummaryResponse listing,
        @Schema(description = "Whether the member is the one asking or the one selling") ConversationRole myRole,
        ParticipantResponse otherMember,
        @Schema(description = "The last message; null while nobody has written (such a conversation is not in the inbox, but it can be opened by its id)",
                nullable = true) MessagePreview lastMessage,
        @Schema(description = "When the last message was sent (the creation time while there is none); the inbox is ordered by it (UTC)")
                Instant lastMessageAt,
        @Schema(description = "UTC") Instant createdAt) {
}
