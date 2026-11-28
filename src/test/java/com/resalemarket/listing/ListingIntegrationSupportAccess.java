package com.resalemarket.listing;

import java.util.UUID;

/**
 * Lets integration tests of other modules (favourites, discovery) reuse the helpers of {@link ListingIntegrationSupport}, which is
 * package-private: signing people in, calling with or without a token, inserting listings by SQL, and the cleanup after each test.
 */
public abstract class ListingIntegrationSupportAccess extends ListingIntegrationSupport {

    /** A caller with exactly this {@code Authorization} header (the constructor of {@link Member} is not visible outside the package). */
    protected Member callerWith(String authorization) {
        return new Member("caller", UUID.randomUUID().toString(), authorization);
    }

    protected ListingRows rowsFor(Member seller) {
        return new ListingRows(jdbc, UUID.fromString(seller.id()));
    }
}
