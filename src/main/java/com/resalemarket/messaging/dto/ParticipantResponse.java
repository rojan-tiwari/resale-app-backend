package com.resalemarket.messaging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "The person at the other end of a conversation. Only a name: no e-mail address and no phone number.")
public record ParticipantResponse(
        UUID id,
        @Schema(description = "Null when the account was suspended or deleted; show a neutral label then", example = "Sita Sharma", nullable = true)
                String displayName,
        @Schema(description = "False when the account was suspended or deleted: the conversation stays readable, but nothing can be sent to them")
                boolean active) {
}
