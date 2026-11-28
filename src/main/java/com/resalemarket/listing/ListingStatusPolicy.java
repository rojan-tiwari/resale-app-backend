package com.resalemarket.listing;

import static com.resalemarket.listing.entity.ListingStatus.ACTIVE;
import static com.resalemarket.listing.entity.ListingStatus.ARCHIVED;
import static com.resalemarket.listing.entity.ListingStatus.DRAFT;
import static com.resalemarket.listing.entity.ListingStatus.PENDING_REVIEW;
import static com.resalemarket.listing.entity.ListingStatus.REJECTED;
import static com.resalemarket.listing.entity.ListingStatus.RESERVED;
import static com.resalemarket.listing.entity.ListingStatus.SOLD;

import com.resalemarket.listing.entity.ListingStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * The listing state machine ({@code docs/API.md}, section 4) as one pure function, so the rules can be read, tested and shown to
 * the web app (which offers exactly these buttons) in one place.
 *
 * <pre>
 *   owner:  DRAFT -> ACTIVE (or PENDING_REVIEW when review is required) | ARCHIVED
 *           PENDING_REVIEW -> DRAFT (withdraw)
 *           ACTIVE -> RESERVED | SOLD | ARCHIVED        RESERVED -> ACTIVE | SOLD | ARCHIVED
 *           SOLD -> ARCHIVED       REJECTED -> DRAFT | ARCHIVED       ARCHIVED -> DRAFT (restore)
 *   admin:  PENDING_REVIEW -> ACTIVE | REJECTED | ARCHIVED     ACTIVE, RESERVED -> REJECTED | ARCHIVED
 *           DRAFT, SOLD, REJECTED -> ARCHIVED               (moderation: take any listing down)
 * </pre>
 */
final class ListingStatusPolicy {

    private ListingStatusPolicy() {
    }

    /**
     * The statuses the caller may move a listing in {@code from} to.
     *
     * @param owner          the caller is the seller
     * @param admin          the caller is an administrator (an administrator who sells has both powers)
     * @param reviewRequired new listings need an administrator's approval before they go live ({@code app.listing.require-review})
     */
    static Set<ListingStatus> allowedTargets(ListingStatus from, boolean owner, boolean admin, boolean reviewRequired) {
        Set<ListingStatus> targets = EnumSet.noneOf(ListingStatus.class);
        if (owner) {
            switch (from) {
                case DRAFT -> {
                    targets.add(reviewRequired ? PENDING_REVIEW : ACTIVE);
                    targets.add(ARCHIVED);
                }
                case PENDING_REVIEW -> targets.add(DRAFT);
                case ACTIVE -> targets.addAll(EnumSet.of(RESERVED, SOLD, ARCHIVED));
                case RESERVED -> targets.addAll(EnumSet.of(ACTIVE, SOLD, ARCHIVED));
                case SOLD -> targets.add(ARCHIVED);
                case REJECTED -> targets.addAll(EnumSet.of(DRAFT, ARCHIVED));
                case ARCHIVED -> targets.add(DRAFT);
            }
        }
        if (admin) {
            switch (from) {
                case PENDING_REVIEW -> targets.addAll(EnumSet.of(ACTIVE, REJECTED, ARCHIVED));
                case ACTIVE, RESERVED -> targets.addAll(EnumSet.of(REJECTED, ARCHIVED));
                case DRAFT, SOLD, REJECTED -> targets.add(ARCHIVED);
                case ARCHIVED -> { }
            }
        }
        return targets;
    }
}
