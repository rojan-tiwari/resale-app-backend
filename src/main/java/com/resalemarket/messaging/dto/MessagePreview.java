package com.resalemarket.messaging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "The last message of a conversation, shortened for a list row")
public record MessagePreview(
        @Schema(description = "At most 140 characters, line breaks turned into spaces, ending in an ellipsis when it was shortened") String text,
        @Schema(description = "The signed-in member wrote it") boolean mine,
        @Schema(description = "UTC") Instant sentAt) {
}
