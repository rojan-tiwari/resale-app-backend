package com.resalemarket.listing;

import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.listing.entity.ListingCondition;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the raw query parameters of {@code GET /listings} into {@link ListingSearchCriteria}, and says what is wrong with
 * a field the way a form would (a 400 with the field named), instead of a bare "failed to convert". Blank values mean "not given",
 * so a search form can send all its fields and leave the empty ones empty.
 */
final class ListingSearchParser {

    static final int MIN_KEYWORD = 2;
    static final int MAX_KEYWORD = 100;
    static final int MAX_POSTED_WITHIN_DAYS = 365;
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    private ListingSearchParser() {
    }

    static ListingSearchCriteria parse(
            String keyword,
            Integer categoryId,
            Integer subcategoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            List<String> conditions,
            Integer provinceId,
            Integer districtId,
            Integer municipalityId,
            Boolean negotiable,
            Integer postedWithinDays,
            String sort) {
        // Control characters (a NUL byte is one) mean nothing in a search, and PostgreSQL refuses a NUL in text: treat them as spaces.
        String words = keyword == null ? null : keyword.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (words != null && words.isEmpty()) {
            words = null;
        }
        if (words != null && (words.length() < MIN_KEYWORD || words.length() > MAX_KEYWORD)) {
            throw new FieldValidationException("q", "Search for between %d and %d characters.".formatted(MIN_KEYWORD, MAX_KEYWORD));
        }
        positive("categoryId", categoryId);
        positive("subcategoryId", subcategoryId);
        positive("provinceId", provinceId);
        positive("districtId", districtId);
        positive("municipalityId", municipalityId);
        price("minPrice", minPrice);
        price("maxPrice", maxPrice);
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new FieldValidationException("minPrice", "The lowest price cannot be above the highest price.");
        }
        if (postedWithinDays != null && (postedWithinDays < 1 || postedWithinDays > MAX_POSTED_WITHIN_DAYS)) {
            throw new FieldValidationException("postedWithinDays", "Choose between 1 and %d days.".formatted(MAX_POSTED_WITHIN_DAYS));
        }

        return new ListingSearchCriteria(
                words,
                categoryId,
                subcategoryId,
                minPrice,
                maxPrice,
                parseConditions(conditions),
                provinceId,
                districtId,
                municipalityId,
                Boolean.TRUE.equals(negotiable),
                postedWithinDays,
                parseSort(sort, words != null));
    }

    private static Set<ListingCondition> parseConditions(List<String> values) {
        Set<ListingCondition> conditions = EnumSet.noneOf(ListingCondition.class);
        if (values == null) {
            return conditions;
        }
        for (String value : values) {
            // A form may send "NEW,GOOD" as one value; both that and repeated parameters are understood.
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    conditions.add(ListingCondition.valueOf(part.strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    throw new FieldValidationException("condition", "Unknown condition. Use NEW, LIKE_NEW, GOOD, FAIR or FOR_PARTS.");
                }
            }
        }
        return conditions;
    }

    /** Relevance is the default with a keyword, newest without one; asking for relevance with no keyword just means newest. */
    private static ListingSort parseSort(String token, boolean hasKeyword) {
        ListingSort fallback = hasKeyword ? ListingSort.RELEVANCE : ListingSort.NEWEST;
        if (token == null || token.isBlank()) {
            return fallback;
        }
        ListingSort sort = ListingSort.fromToken(token.strip())
                .orElseThrow(() -> new FieldValidationException("sort", "Sort by relevance, newest, priceAsc or priceDesc."));
        return sort == ListingSort.RELEVANCE && !hasKeyword ? ListingSort.NEWEST : sort;
    }

    private static void positive(String field, Integer id) {
        if (id != null && id < 1) {
            throw new FieldValidationException(field, "Not a valid id.");
        }
    }

    private static void price(String field, BigDecimal price) {
        if (price != null && (price.signum() < 0 || price.compareTo(MAX_PRICE) > 0)) {
            throw new FieldValidationException(field, "Enter a price of 0 or more.");
        }
    }
}
