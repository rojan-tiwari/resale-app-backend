package com.resalemarket.messaging;

import com.resalemarket.common.config.OpenApiConfig;
import com.resalemarket.messaging.dto.MessageListResponse;
import com.resalemarket.messaging.dto.MessageResponse;
import com.resalemarket.messaging.dto.SendMessageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The messages of one conversation. Everything needs an access token; only the conversation's two people may use it. Responses are never cached. */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
@Tag(name = "Messaging", description = "Conversations between a buyer and a seller about a listing")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class MessageController {

    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final MessageService messageService;
    private final MessagingRateLimits rateLimits;

    public MessageController(MessageService messageService, MessagingRateLimits rateLimits) {
        this.messageService = messageService;
        this.rateLimits = rateLimits;
    }

    @GetMapping
    @Operation(
            summary = "Messages of a conversation",
            description = "A run of messages, **oldest first** (as a chat draws them). Without `before` or `after`: the newest ones. "
                    + "`before=<message id>`: the ones just before that message, for \"load older messages\". `after=<message id>`: the ones just "
                    + "after it, for polling an open chat. `hasMore` says whether more exist beyond the run in the direction asked for. "
                    + "Reading does not mark anything as read.")
    @ApiResponse(responseCode = "400", description = "Both `before` and `after` were given (VALIDATION_FAILED)")
    @ApiResponse(responseCode = "404", description = "No such conversation, the caller is not one of its two people, or the cursor is not one of its messages (NOT_FOUND)")
    @ApiResponse(responseCode = "429", description = "Too many calls of the kind an open chat repeats (TOO_MANY_REQUESTS)")
    public ResponseEntity<MessageListResponse> list(
            @PathVariable UUID conversationId,
            @Parameter(description = "Older than this message") @RequestParam(required = false) UUID before,
            @Parameter(description = "Newer than this message") @RequestParam(required = false) UUID after,
            @Parameter(description = "How many, 1 to 100") @RequestParam(defaultValue = "30") int size,
            Authentication authentication) {
        UUID userId = userId(authentication);
        rateLimits.poll(userId);
        return ResponseEntity.ok().cacheControl(NO_STORE).body(messageService.list(userId, conversationId, before, after, size));
    }

    @PostMapping
    @Operation(summary = "Send a message", description = "To the other person of the conversation. Plain text, at most 2000 characters.")
    @ApiResponse(responseCode = "201", description = "Sent")
    @ApiResponse(responseCode = "400", description = "Empty, too long, or has characters that cannot be sent (VALIDATION_FAILED, `errors[].field` is `body`)")
    @ApiResponse(responseCode = "404", description = "No such conversation, or the caller is not one of its two people (NOT_FOUND)")
    @ApiResponse(responseCode = "409", description = "The very same text as the caller's last message, sent a moment ago (CONFLICT)")
    @ApiResponse(responseCode = "422", description = "The other person's account was suspended or deleted (BUSINESS_RULE_VIOLATION)")
    @ApiResponse(responseCode = "429", description = "Too many messages sent recently (TOO_MANY_REQUESTS)")
    public ResponseEntity<MessageResponse> send(
            @PathVariable UUID conversationId, @Valid @RequestBody SendMessageRequest request, Authentication authentication) {
        UUID userId = userId(authentication);
        rateLimits.send(userId);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(messageService.send(userId, conversationId, request.body()));
    }

    /** Deny-by-default security guarantees a verified access token here; its subject is the member's id. */
    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
