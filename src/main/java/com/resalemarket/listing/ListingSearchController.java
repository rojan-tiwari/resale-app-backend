package com.resalemarket.listing;

import com.resalemarket.common.response.PageResponse;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public discovery: search with combinable filters, and the lists on the home page. Everything is read-only and public; only live
 * listings of members in good standing are ever shown. The filters are all optional query parameters so a search is a link that
 * can be shared.
 */
@RestController
@RequestMapping("/api/v1/listings")
@Tag(name = "Search", description = "Search, filters and the home page lists")
public class ListingSearchController {

    private static final int MAX_SECTION = 24;
    /** The same search a moment later gives the same answer, so a short shared cache saves the database real work. */
    private static final CacheControl CACHE_BRIEFLY = CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    private final ListingSearchService searchService;

    public ListingSearchController(ListingSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(
            summary = "Search and filter live listings",
            description = "Public. Every parameter is optional and all of them combine. `q` searches titles and descriptions "
                    + "(whole words, ranked; or part of a title word). `sort` is `relevance` (the default with `q`), `newest` (the default "
                    + "otherwise), `priceAsc` or `priceDesc`. Paginated with `page` and `size` (default 20, at most 100); "
                    + "the first 10,000 results can be browsed. Blank parameters count as not given.")
    @ApiResponse(responseCode = "200", description = "One page of cards")
    @ApiResponse(responseCode = "400", description = "A parameter is invalid (VALIDATION_FAILED, `errors` names it)")
    public ResponseEntity<PageResponse<ListingSummaryResponse>> search(
            @Parameter(description = "Keyword, 2 to 100 characters") @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) Integer subcategoryId,
            @Parameter(description = "In NPR") @RequestParam(required = false) BigDecimal minPrice,
            @Parameter(description = "In NPR") @RequestParam(required = false) BigDecimal maxPrice,
            @Parameter(description = "NEW, LIKE_NEW, GOOD, FAIR, FOR_PARTS; repeat the parameter or separate with commas")
                    @RequestParam(required = false) List<String> condition,
            @RequestParam(required = false) Integer provinceId,
            @RequestParam(required = false) Integer districtId,
            @RequestParam(required = false) Integer municipalityId,
            @Parameter(description = "true = only negotiable prices") @RequestParam(required = false) Boolean negotiable,
            @Parameter(description = "Only listings that went live in the last this many days (1 to 365)")
                    @RequestParam(required = false) Integer postedWithinDays,
            @RequestParam(required = false) String sort,
            Pageable pageable) {
        ListingSearchCriteria criteria = ListingSearchParser.parse(
                q, categoryId, subcategoryId, minPrice, maxPrice, condition, provinceId, districtId, municipalityId,
                negotiable, postedWithinDays, sort);
        // The client's own `sort` is read above as a name; the page's order is never taken from Spring's Sort.
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return ResponseEntity.ok().cacheControl(CACHE_BRIEFLY).body(searchService.search(criteria, page));
    }

    @GetMapping("/featured")
    @Operation(summary = "Featured listings", description = "Public. Listings whose promotion is running, newest first. `limit` is 8 by default and kept between 1 and 24.")
    public ResponseEntity<List<ListingSummaryResponse>> featured(@RequestParam(defaultValue = "8") int limit) {
        return ResponseEntity.ok().cacheControl(CACHE_BRIEFLY).body(searchService.featured(bounded(limit)));
    }

    @GetMapping("/recent")
    @Operation(summary = "Recently posted listings", description = "Public. The newest live listings. `limit` is 8 by default and kept between 1 and 24.")
    public ResponseEntity<List<ListingSummaryResponse>> recent(@RequestParam(defaultValue = "8") int limit) {
        return ResponseEntity.ok().cacheControl(CACHE_BRIEFLY).body(searchService.recent(bounded(limit)));
    }

    private static int bounded(int limit) {
        return Math.max(1, Math.min(limit, MAX_SECTION));
    }
}
