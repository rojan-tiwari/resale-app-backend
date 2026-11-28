package com.resalemarket.messaging.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What the signed-in member has not read yet, over all their conversations: the number on the Messages icon")
public record UnreadCountsResponse(
        @Schema(description = "Messages the other people sent that the member has not read") long unreadMessages,
        @Schema(description = "In how many conversations; the header shows this one") long unreadConversations) {
}
