package com.resalemarket.discovery;

import com.resalemarket.discovery.dto.RecommendationsResponse;
import com.resalemarket.discovery.dto.RecommendationsResponse.Basis;
import com.resalemarket.favorite.FavoriteService;
import com.resalemarket.listing.ListingSearchService;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rule-based "recommended" list of the home page. Deliberately simple and explainable (there is no learning in it):
 *
 * <ol>
 *   <li>A signed-in member who saved ads gets the <b>newest live ads in the categories they save the most</b> (up to
 *       {@value #CATEGORIES} of them), leaving out their own ads and the ones they already saved; if that is fewer than asked for,
 *       the rest is filled with popular ads.</li>
 *   <li>Everyone else gets the <b>most viewed ads that went live in the last 30 days</b>.</li>
 * </ol>
 *
 * It lives in its own module because it needs both {@code favorite} and {@code listing}, and those two must not depend on each other
 * in a circle: {@code favorite} already depends on {@code listing}.
 */
@Service
@Transactional(readOnly = true)
public class RecommendationService {

    /** How many of a member's favourite categories are used. */
    static final int CATEGORIES = 3;

    private final FavoriteService favoriteService;
    private final ListingSearchService searchService;

    public RecommendationService(FavoriteService favoriteService, ListingSearchService searchService) {
        this.favoriteService = favoriteService;
        this.searchService = searchService;
    }

    /**
     * @param userId the signed-in member, or {@code null} for a visitor
     * @param limit  how many ads at most
     */
    public RecommendationsResponse recommend(UUID userId, int limit) {
        if (userId != null) {
            List<Integer> categories = favoriteService.favouriteCategoryIds(userId, CATEGORIES);
            if (!categories.isEmpty()) {
                List<ListingSummaryResponse> similar = searchService.newestInCategories(
                        categories, favoriteService.listingIds(userId), userId, limit);
                return new RecommendationsResponse(Basis.FAVOURITE_CATEGORIES, topUp(similar, userId, limit));
            }
        }
        return new RecommendationsResponse(Basis.POPULAR, searchService.popular(limit, userId));
    }

    /** Fills a short list with popular ads that are not already in it and are not the member's own. */
    private List<ListingSummaryResponse> topUp(List<ListingSummaryResponse> picked, UUID userId, int limit) {
        if (picked.size() >= limit) {
            return picked;
        }
        Set<UUID> have = new HashSet<>();
        picked.forEach(card -> have.add(card.id()));
        Set<UUID> saved = new HashSet<>(favoriteService.listingIds(userId));
        List<ListingSummaryResponse> result = new ArrayList<>(picked);
        // Ask for extra: some popular ads will be filtered out as duplicates or already saved.
        for (ListingSummaryResponse card : searchService.popular(limit * 3, userId)) {
            if (result.size() >= limit) {
                break;
            }
            if (have.add(card.id()) && !saved.contains(card.id())) {
                result.add(card);
            }
        }
        return result;
    }
}
