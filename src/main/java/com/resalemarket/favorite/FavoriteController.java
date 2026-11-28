package com.resalemarket.favorite;

import com.resalemarket.common.config.OpenApiConfig;
import com.resalemarket.common.response.PageResponse;
import com.resalemarket.favorite.dto.FavoriteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in member's saved listings. Everything needs an access token and is about that member only. Responses are never cached. */
@RestController
@RequestMapping("/api/v1/favorites")
@Tag(name = "Favourites", description = "Saved listings")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class FavoriteController {

    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final FavoriteService favoriteService;
    private final FavoriteRateLimits rateLimits;

    public FavoriteController(FavoriteService favoriteService, FavoriteRateLimits rateLimits) {
        this.favoriteService = favoriteService;
        this.rateLimits = rateLimits;
    }

    @GetMapping
    @Operation(
            summary = "My saved listings",
            description = "Newest saved first, paginated (only `page` and `size` are used). An ad that is no longer for sale is flagged "
                    + "(`available: false`), not dropped: a sold ad keeps its card (status SOLD), an ad that was taken down has none.")
    public ResponseEntity<PageResponse<FavoriteResponse>> list(Authentication authentication, Pageable pageable) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(favoriteService.list(userId(authentication), pageable));
    }

    @GetMapping("/ids")
    @Operation(
            summary = "The ids of my saved listings",
            description = "Everything the member saved, newest first (at most 500 by default): what the web app needs to show the filled heart on cards.")
    public ResponseEntity<List<UUID>> ids(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(favoriteService.listingIds(userId(authentication)));
    }

    @PostMapping("/{listingId}")
    @Operation(summary = "Save a listing")
    @ApiResponse(responseCode = "201", description = "Saved")
    @ApiResponse(responseCode = "404", description = "The listing does not exist or is not live (NOT_FOUND)")
    @ApiResponse(responseCode = "409", description = "Already saved (CONFLICT)")
    @ApiResponse(responseCode = "422", description = "The member saved the most listings they may (BUSINESS_RULE_VIOLATION)")
    @ApiResponse(responseCode = "429", description = "Too many saves and removals recently (TOO_MANY_REQUESTS)")
    public ResponseEntity<FavoriteResponse> add(@PathVariable UUID listingId, Authentication authentication) {
        UUID userId = userId(authentication);
        rateLimits.write(userId);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(favoriteService.add(userId, listingId));
    }

    @DeleteMapping("/{listingId}")
    @Operation(summary = "Remove a saved listing", description = "Removing one that is not saved is fine (204 either way).")
    @ApiResponse(responseCode = "204", description = "Removed, or was not saved")
    @ApiResponse(responseCode = "429", description = "Too many saves and removals recently (TOO_MANY_REQUESTS)")
    public ResponseEntity<Void> remove(@PathVariable UUID listingId, Authentication authentication) {
        UUID userId = userId(authentication);
        rateLimits.write(userId);
        favoriteService.remove(userId, listingId);
        return ResponseEntity.noContent().build();
    }

    /** Deny-by-default security guarantees a verified access token here; its subject is the member's id. */
    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
