package com.resalemarket.messaging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One message of a conversation")
public record MessageResponse(
        UUID id,
        UUID conversationId,
        UUID senderId,
        @Schema(description = "The signed-in member wrote it: the chat draws these on the right") boolean mine,
        String body,
        @Schema(description = "UTC") Instant createdAt,
        @Schema(description = "When the other person read it (UTC); null until they have", nullable = true) Instant readAt) {
}
