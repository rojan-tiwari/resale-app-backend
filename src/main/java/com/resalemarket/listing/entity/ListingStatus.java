package com.resalemarket.listing.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a listing is in its life; the allowed moves are in {@code ListingStatusPolicy}. The names are stored as text and checked by
 * {@code chk_listings_status}.
 */
public enum ListingStatus {
    DRAFT,
    PENDING_REVIEW,
    ACTIVE,
    RESERVED,
    SOLD,
    REJECTED,
    ARCHIVED;

    /** The statuses anyone may see. The partial indexes of {@code listings} cover exactly these, so every public query repeats them. */
    public static final Set<ListingStatus> PUBLIC = EnumSet.of(ACTIVE, RESERVED);

    private static final Set<ListingStatus> EDITABLE = EnumSet.of(DRAFT, ACTIVE, RESERVED);

    public boolean isPublic() {
        return PUBLIC.contains(this);
    }

    /**
     * Whether the owner may change the content (text, price, photos). A listing under review, a sold one and a removed one are
     * frozen: they must first go back to {@link #DRAFT}, so what a moderator approved or a buyer saw cannot be swapped silently.
     */
    public boolean isEditable() {
        return EDITABLE.contains(this);
    }
}
