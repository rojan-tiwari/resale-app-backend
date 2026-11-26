package com.resalemarket.discovery;

import com.resalemarket.discovery.dto.RecommendationsResponse;
import com.resalemarket.listing.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The home page's "recommended" list. Public; it reads the caller from the token when there is one, so the answer is not cached. */
@RestController
@RequestMapping("/api/v1/listings/recommended")
@Tag(name = "Search", description = "Search, filters and the home page lists")
public class RecommendationController {

    private static final int MAX = 24;

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping
    @Operation(
            summary = "Recommended listings",
            description = "Public. For a signed-in member who saved ads: the newest ads in the categories they save the most (never their own "
                    + "or ones already saved). For everyone else: the most viewed ads of the last 30 days. `basis` says which rule applied. "
                    + "`limit` is 8 by default and kept between 1 and 24.")
    public ResponseEntity<RecommendationsResponse> recommended(Authentication authentication, @RequestParam(defaultValue = "8") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX));
        RecommendationsResponse body = recommendationService.recommend(Viewer.of(authentication).userId(), bounded);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
