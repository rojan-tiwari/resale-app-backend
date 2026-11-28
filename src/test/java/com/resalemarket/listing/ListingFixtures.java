package com.resalemarket.listing;

import com.resalemarket.listing.entity.Listing;
import com.resalemarket.listing.entity.ListingCondition;
import com.resalemarket.listing.entity.ListingDetails;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.listing.entity.PriceType;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

/** Entities have no setters on purpose (state changes go through domain methods), so unit tests build them here. */
final class ListingFixtures {

    private ListingFixtures() {
    }

    static ListingDetails details() {
        return new ListingDetails(
                "iPhone 13", "Bought last year, no scratches.", new BigDecimal("85000.00"), PriceType.NEGOTIABLE,
                ListingCondition.LIKE_NEW, 1, 2, 3, 25, 1, "Baneshwor");
    }

    /** A listing "loaded from the database": it has an id and a status. */
    static Listing listing(UUID sellerId, ListingStatus status) {
        Listing listing = Listing.draft(sellerId, details());
        ReflectionTestUtils.setField(listing, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(listing, "status", status);
        return listing;
    }
}
