package com.resalemarket.messaging;

import com.resalemarket.common.config.OpenApiConfig;
import com.resalemarket.messaging.dto.UnreadCountsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the signed-in member has read: the number for the header and the act of opening a conversation. Responses are never cached. */
@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Messaging", description = "Conversations between a buyer and a seller about a listing")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ConversationReadController {

    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final MessageService messageService;
    private final MessagingRateLimits rateLimits;

    public ConversationReadController(MessageService messageService, MessagingRateLimits rateLimits) {
        this.messageService = messageService;
        this.rateLimits = rateLimits;
    }

    @GetMapping("/unread-count")
    @Operation(
            summary = "What I have not read",
            description = "The number on the Messages icon: unread messages, and in how many conversations. Cheap on purpose, so the "
                    + "web app can ask every half minute.")
    @ApiResponse(responseCode = "429", description = "Too many calls of the kind an open chat repeats (TOO_MANY_REQUESTS)")
    public ResponseEntity<UnreadCountsResponse> unreadCount(Authentication authentication) {
        UUID userId = userId(authentication);
        rateLimits.poll(userId);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(messageService.unreadCounts(userId));
    }

    @PatchMapping("/{id}/read")
    @Operation(
            summary = "Mark a conversation as read",
            description = "Marks what the other person sent as read (what opening the conversation means). Harmless to repeat. Answers "
                    + "with what is still unread overall, so the badge can be updated without another call.")
    @ApiResponse(responseCode = "404", description = "No such conversation, or the caller is not one of its two people (NOT_FOUND)")
    @ApiResponse(responseCode = "429", description = "Too many calls of the kind an open chat repeats (TOO_MANY_REQUESTS)")
    public ResponseEntity<UnreadCountsResponse> markRead(@PathVariable UUID id, Authentication authentication) {
        UUID userId = userId(authentication);
        rateLimits.poll(userId);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(messageService.markRead(userId, id));
    }

    /** Deny-by-default security guarantees a verified access token here; its subject is the member's id. */
    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
