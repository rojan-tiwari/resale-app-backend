package com.resalemarket.listing;

import java.util.Arrays;
import java.util.Optional;

/** How search results are ordered. The {@link #token} is what the API accepts in {@code sort}. Ties are always broken by newest, then id, so paging is stable. */
public enum ListingSort {
    /** Best keyword match first. Only meaningful with a keyword; without one it is the same as {@link #NEWEST}. */
    RELEVANCE("relevance"),
    NEWEST("newest"),
    PRICE_ASC("priceAsc"),
    PRICE_DESC("priceDesc");

    private final String token;

    ListingSort(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static Optional<ListingSort> fromToken(String token) {
        return Arrays.stream(values()).filter(sort -> sort.token.equals(token)).findFirst();
    }
}
