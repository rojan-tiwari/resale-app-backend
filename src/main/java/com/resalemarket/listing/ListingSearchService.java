package com.resalemarket.listing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.response.PageResponse;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.listing.entity.Listing;
import com.resalemarket.user.UserService;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What visitors browse: the public search and the lists on the home page. Everything here shows live listings (status
 * {@code ACTIVE} or {@code RESERVED}) of members in good standing, and nothing else, whatever the filters say.
 *
 * <p>The ids come from {@link ListingSearchRepository} (one SQL statement, in order); the rows are then loaded in one query and
 * turned into cards in bulk by {@link ListingResponses}, so a page of 24 cards costs a fixed number of queries.
 */
@Service
@Transactional(readOnly = true)
public class ListingSearchService {

    /** Results can be browsed this deep and no further ({@code page * size}); deeper offsets get slow and nobody pages that far. */
    static final int MAX_RESULT_WINDOW = 10_000;

    /** "Popular" means the most viewed among the listings that went live this recently. */
    static final Duration POPULAR_WINDOW = Duration.ofDays(30);

    /** How many of the most viewed listings are kept in the cached pool (enough for the longest list asked for, with room to filter). */
    static final int POPULAR_POOL = 100;

    private final ListingSearchRepository searchRepository;
    private final ListingRepository listingRepository;
    private final ListingResponses responses;
    private final UserService userService;
    private final Clock clock;

    /** The shared pool of most viewed listings; one key, because the list is the same for everybody. */
    private final Cache<String, List<ListingSearchRepository.PopularRow>> popularPool;

    public ListingSearchService(
            ListingSearchRepository searchRepository,
            ListingRepository listingRepository,
            ListingResponses responses,
            UserService userService,
            Clock clock,
            SearchProperties properties) {
        this.searchRepository = searchRepository;
        this.listingRepository = listingRepository;
        this.responses = responses;
        this.userService = userService;
        this.clock = clock;
        this.popularPool = Caffeine.newBuilder()
                .expireAfterWrite(properties.popularCacheTtl())
                // The same clock decides when the pool expires, so tests can move time.
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .maximumSize(1)
                .build();
    }

    /**
     * One page of search results with the total.
     *
     * @throws FieldValidationException if the page asked for lies beyond the {@value #MAX_RESULT_WINDOW} results that can be browsed
     */
    public PageResponse<ListingSummaryResponse> search(ListingSearchCriteria criteria, Pageable page) {
        if (page.getOffset() + page.getPageSize() > MAX_RESULT_WINDOW) {
            throw new FieldValidationException(
                    "page", "Only the first %,d results can be browsed. Narrow your search with a filter.".formatted(MAX_RESULT_WINDOW));
        }
        List<UUID> excluded = userService.inactiveUserIds();
        var now = clock.instant();
        List<UUID> ids = searchRepository.ids(criteria, excluded, now, page.getPageSize(), page.getOffset());

        // A short page tells the total by itself; only a full page, or a page past the end, needs the count query.
        long total = ids.size() < page.getPageSize() && (page.getPageNumber() == 0 || !ids.isEmpty())
                ? page.getOffset() + ids.size()
                : searchRepository.count(criteria, excluded, now);
        return PageResponse.from(new PageImpl<>(cards(ids), page, total));
    }

    /** Listings whose promotion is running, newest first, at most {@code limit}. */
    public List<ListingSummaryResponse> featured(int limit) {
        return cards(searchRepository.featured(userService.inactiveUserIds(), clock.instant(), limit));
    }

    /** The newest live listings, at most {@code limit}. */
    public List<ListingSummaryResponse> recent(int limit) {
        List<UUID> ids = searchRepository.ids(ListingSearchCriteria.everything(), userService.inactiveUserIds(), clock.instant(), limit, 0);
        return cards(ids);
    }

    /**
     * The most viewed among the listings that went live in the last {@value #POPULAR_WINDOW}.
     *
     * <p>Drawn from a pool of the {@value #POPULAR_POOL} most viewed, which is computed at most once per {@code app.search.popular-cache-ttl}
     * and shared by everybody (concurrent callers wait for one computation instead of each running it). What depends on the caller
     * is applied <em>after</em> the cache, every time: members who are suspended or deleted, the caller's own ads, and a listing that
     * has meanwhile stopped being live, so none of those can be served from a stale pool.
     *
     * @param excludedSeller a member whose own ads are left out (the signed-in member, who is not shown their own ads as suggestions); may be {@code null}
     */
    public List<ListingSummaryResponse> popular(int limit, UUID excludedSeller) {
        List<UUID> inactive = userService.inactiveUserIds();
        List<UUID> candidates = popularPool()
                .stream()
                .filter(row -> !inactive.contains(row.sellerId()) && !row.sellerId().equals(excludedSeller))
                .map(ListingSearchRepository.PopularRow::id)
                .limit(limit + 8L)             // a few spare, for listings that stopped being live since the pool was made
                .toList();
        List<ListingSummaryResponse> cards = cards(candidates);
        return cards.size() > limit ? cards.subList(0, limit) : cards;
    }

    private List<ListingSearchRepository.PopularRow> popularPool() {
        return popularPool.get("pool", key -> searchRepository.mostViewed(clock.instant().minus(POPULAR_WINDOW), POPULAR_POOL));
    }

    /**
     * The newest live listings in any of these categories, leaving out some listings and a member's own.
     *
     * @param categoryIds top-level or sub categories; must not be empty
     */
    public List<ListingSummaryResponse> newestInCategories(
            Collection<Integer> categoryIds, Collection<UUID> excludedListings, UUID excludedSeller, int limit) {
        if (categoryIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = searchRepository.newestInCategories(
                categoryIds, excludedListings, excludedSeller, userService.inactiveUserIds(), limit);
        return cards(ids);
    }

    /** Loads the rows of the ids and returns the cards in the same order. */
    private List<ListingSummaryResponse> cards(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Listing> byId = listingRepository.findAllById(ids).stream().collect(Collectors.toMap(Listing::getId, Function.identity()));
        // A listing that changed between the id query and this one (sold, taken down) is left out rather than shown wrongly.
        List<Listing> ordered = ids.stream().map(byId::get).filter(listing -> listing != null && listing.getStatus().isPublic()).toList();
        return responses.summaries(ordered);
    }
}
