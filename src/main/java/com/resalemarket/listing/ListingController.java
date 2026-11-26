package com.resalemarket.listing;

import com.resalemarket.common.config.OpenApiConfig;
import com.resalemarket.common.response.PageResponse;
import com.resalemarket.listing.dto.ListingRequest;
import com.resalemarket.listing.dto.ListingResponse;
import com.resalemarket.listing.dto.ListingStatusRequest;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.listing.entity.ListingStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listings: sellers create, edit, publish and take them down; everyone reads the live ones. The controller only translates HTTP;
 * who may do what is decided in {@link ListingService} and {@link ListingAccess}. Public endpoints (see {@code SecurityConfig})
 * read the caller from the token when there is one, so a seller sees their own drafts there.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Listings", description = "Create, publish, edit and read listings")
public class ListingController {

    private static final int MAX_SIMILAR = 24;
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final ListingService listingService;
    private final ListingRateLimits rateLimits;

    public ListingController(ListingService listingService, ListingRateLimits rateLimits) {
        this.listingService = listingService;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/listings")
    @Operation(
            summary = "Create a listing (as a draft)",
            description = "Needs an access token. The listing starts as DRAFT: upload at least one photo, then move it to ACTIVE "
                    + "with PATCH /listings/{id}/status (or PENDING_REVIEW when administrator approval is required).",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "201", description = "The new draft")
    @ApiResponse(responseCode = "400", description = "Invalid input, or a category / place that does not fit together (VALIDATION_FAILED)")
    @ApiResponse(responseCode = "401", description = "No access token, or it is invalid or expired (UNAUTHORIZED)")
    @ApiResponse(responseCode = "429", description = "Too many listings created recently (TOO_MANY_REQUESTS)")
    public ResponseEntity<ListingResponse> create(Authentication authentication, @Valid @RequestBody ListingRequest request) {
        Viewer viewer = Viewer.of(authentication);
        rateLimits.create(viewer.userId());
        ListingResponse created = listingService.create(viewer, request);
        return ResponseEntity.created(URI.create("/api/v1/listings/" + created.id()))
                .cacheControl(NO_STORE)
                .body(created);
    }

    @GetMapping("/listings/mine")
    @Operation(
            summary = "My listings",
            description = "Needs an access token. Newest first. Archived listings are left out unless `status=ARCHIVED`. "
                    + "Only `page` and `size` are used; any `sort` is ignored.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    public ResponseEntity<PageResponse<ListingSummaryResponse>> mine(
            Authentication authentication,
            @Parameter(description = "Only listings in this status") @RequestParam(required = false) ListingStatus status,
            Pageable pageable) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(listingService.mine(Viewer.of(authentication), status, pageable));
    }

    @GetMapping("/listings/{id}")
    @Operation(
            summary = "One listing",
            description = "Public for live listings (ACTIVE, RESERVED); counts a view unless the caller is the seller or an administrator. "
                    + "A listing in any other status is only shown to its seller and administrators and is a 404 for everyone else.")
    @ApiResponse(responseCode = "200", description = "The listing")
    @ApiResponse(responseCode = "404", description = "No such listing, or it is not public and the caller may not see it (NOT_FOUND)")
    public ResponseEntity<ListingResponse> get(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(listingService.get(id, Viewer.of(authentication)));
    }

    @PutMapping("/listings/{id}")
    @Operation(
            summary = "Edit a listing",
            description = "The seller only. Replaces the content as a whole. Allowed while the listing is DRAFT, ACTIVE or RESERVED.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "403", description = "The listing is someone else's (FORBIDDEN)")
    @ApiResponse(responseCode = "404", description = "No such listing, or the caller may not see it (NOT_FOUND)")
    @ApiResponse(responseCode = "422", description = "The listing is in a status that cannot be edited (BUSINESS_RULE_VIOLATION)")
    public ResponseEntity<ListingResponse> update(
            @PathVariable UUID id, Authentication authentication, @Valid @RequestBody ListingRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(listingService.update(id, Viewer.of(authentication), request));
    }

    @DeleteMapping("/listings/{id}")
    @Operation(
            summary = "Take a listing down (archive)",
            description = "The seller or an administrator. The listing is archived, not erased: it disappears from the public pages and "
                    + "the seller can restore it to a draft. Repeating the call is fine.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "204", description = "Archived")
    @ApiResponse(responseCode = "403", description = "The listing is someone else's (FORBIDDEN)")
    @ApiResponse(responseCode = "404", description = "No such listing, or the caller may not see it (NOT_FOUND)")
    public ResponseEntity<Void> archive(@PathVariable UUID id, Authentication authentication) {
        listingService.archive(id, Viewer.of(authentication));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/listings/{id}/status")
    @Operation(
            summary = "Change the status of a listing",
            description = "The seller or an administrator, within the state machine of docs/API.md. `allowedStatuses` of the listing says "
                    + "what the caller may choose now. Moving a draft to ACTIVE or PENDING_REVIEW needs at least one photo; "
                    + "REJECTED (administrators) needs a `reason`.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @ApiResponse(responseCode = "400", description = "A rejection without a reason (VALIDATION_FAILED)")
    @ApiResponse(responseCode = "403", description = "The listing is someone else's and the caller is not an administrator (FORBIDDEN)")
    @ApiResponse(responseCode = "404", description = "No such listing, or the caller may not see it (NOT_FOUND)")
    @ApiResponse(responseCode = "422", description = "The move is not allowed from the current status, or a photo is missing (BUSINESS_RULE_VIOLATION)")
    public ResponseEntity<ListingResponse> changeStatus(
            @PathVariable UUID id, Authentication authentication, @Valid @RequestBody ListingStatusRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(listingService.changeStatus(id, Viewer.of(authentication), request));
    }

    @GetMapping("/listings/{id}/similar")
    @Operation(
            summary = "Similar listings",
            description = "Public. Live listings of the same category, those in the same subcategory and place first. "
                    + "`limit` is 8 by default and is kept between 1 and " + MAX_SIMILAR + ".")
    @ApiResponse(responseCode = "404", description = "No such listing, or it is not public (NOT_FOUND)")
    public ResponseEntity<List<ListingSummaryResponse>> similar(
            @PathVariable UUID id, Authentication authentication, @RequestParam(defaultValue = "8") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_SIMILAR));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(listingService.similar(id, Viewer.of(authentication), bounded));
    }

    @GetMapping("/users/{userId}/listings")
    @Operation(
            summary = "A seller's live listings",
            description = "Public. Newest first. The total in the page is the seller's number of live listings. A suspended or deleted "
                    + "member answers 404 like an unknown id.")
    @ApiResponse(responseCode = "404", description = "No active account with this id (NOT_FOUND)")
    public ResponseEntity<PageResponse<ListingSummaryResponse>> sellerListings(@PathVariable UUID userId, Pageable pageable) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(listingService.sellerListings(userId, pageable));
    }
}
