package com.resalemarket.messaging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "A message to the other person of a conversation. Plain text; line breaks are kept.")
public record SendMessageRequest(
        @Schema(description = "At most 2000 characters (`app.messaging.max-message-length`); surrounding spaces are removed", example = "Hello, is it still available?")
        @NotBlank(message = "Write a message first.") String body) {
}
