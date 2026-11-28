package com.resalemarket.listing;

import com.resalemarket.listing.entity.ListingCondition;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Everything a search can ask for. Every field is optional and all of them combine (they are ANDed). Public listings only:
 * the status filter is not a criterion, it is always applied.
 *
 * @param keyword        words to look for in the title and description (PostgreSQL full-text) or as part of a title word (trigram)
 * @param conditions     any of these conditions (empty = any)
 * @param negotiableOnly only listings whose price is negotiable
 * @param postedWithinDays only listings that went live in the last this many days
 */
public record ListingSearchCriteria(
        String keyword,
        Integer categoryId,
        Integer subcategoryId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Set<ListingCondition> conditions,
        Integer provinceId,
        Integer districtId,
        Integer municipalityId,
        boolean negotiableOnly,
        Integer postedWithinDays,
        ListingSort sort) {

    /** No filter at all, newest first: what the homepage's "recent" list is. */
    public static ListingSearchCriteria everything() {
        return new ListingSearchCriteria(null, null, null, null, null, Set.of(), null, null, null, false, null, ListingSort.NEWEST);
    }

    public boolean hasKeyword() {
        return keyword != null;
    }
}
