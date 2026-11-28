package com.resalemarket.messaging;

import com.resalemarket.common.config.OpenApiConfig;
import com.resalemarket.common.response.PageResponse;
import com.resalemarket.messaging.dto.ConversationResponse;
import com.resalemarket.messaging.dto.StartConversationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in member's conversations. Everything needs an access token and is about that member only. Responses are never cached. */
@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Messaging", description = "Conversations between a buyer and a seller about a listing")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ConversationController {

    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    @Operation(
            summary = "My conversations (the inbox)",
            description = "As buyer or as seller, the most recently active first, paginated (only `page` and `size` are used). An ad that is "
                    + "no longer for sale is flagged (`listingAvailable: false`), not dropped: a sold ad keeps its card, an ad that was "
                    + "taken down has none.")
    public ResponseEntity<PageResponse<ConversationResponse>> list(Authentication authentication, Pageable pageable) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(conversationService.list(userId(authentication), pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One of my conversations")
    @ApiResponse(responseCode = "404", description = "There is no such conversation, or the member is not one of its two people (NOT_FOUND)")
    public ResponseEntity<ConversationResponse> get(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(conversationService.get(userId(authentication), id));
    }

    @PostMapping
    @Operation(
            summary = "Start a conversation about a listing",
            description = "Opens the member's conversation with the seller of the listing, or returns the one that already exists "
                    + "(asking twice is harmless).")
    @ApiResponse(responseCode = "201", description = "A new conversation was created")
    @ApiResponse(responseCode = "200", description = "The member already had a conversation about this listing")
    @ApiResponse(responseCode = "404", description = "The listing does not exist or is not live (NOT_FOUND)")
    @ApiResponse(responseCode = "422", description = "The listing is the member's own (BUSINESS_RULE_VIOLATION)")
    @ApiResponse(responseCode = "429", description = "Too many new conversations started recently (TOO_MANY_REQUESTS)")
    public ResponseEntity<ConversationResponse> start(@Valid @RequestBody StartConversationRequest request, Authentication authentication) {
        ConversationService.Started started = conversationService.start(userId(authentication), request.listingId());
        ConversationResponse body = started.conversation();
        if (!started.created()) {
            return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/conversations/" + body.id())).cacheControl(NO_STORE).body(body);
    }

    /** Deny-by-default security guarantees a verified access token here; its subject is the member's id. */
    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
