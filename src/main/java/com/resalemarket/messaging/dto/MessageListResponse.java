package com.resalemarket.messaging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A run of messages, oldest first, as a chat window draws them. Not counted: a chat grows while it is open, so page numbers would shift.")
public record MessageListResponse(
        List<MessageResponse> content,
        @Schema(description = "More messages exist beyond this run, in the direction asked for: older ones for the latest messages and for `before`, newer ones for `after`")
                boolean hasMore) {
}
