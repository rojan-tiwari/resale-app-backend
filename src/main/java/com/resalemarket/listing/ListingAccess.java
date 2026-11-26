package com.resalemarket.listing;

import com.resalemarket.common.exception.ForbiddenException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.listing.entity.Listing;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place that decides who may see and who may change a listing, so every endpoint answers the same way.
 *
 * <ul>
 *   <li><b>Seeing:</b> everyone sees a listing that is live ({@code ACTIVE} or {@code RESERVED}); its seller and administrators see it in
 *       every status. For anyone else it <b>does not exist</b>: 404, never 403, so a draft cannot be discovered by guessing ids.</li>
 *   <li><b>Changing:</b> only the seller (administrators have their own status moves, see {@link ListingStatusPolicy}). Someone who
 *       can see a live listing but does not own it gets 403.</li>
 * </ul>
 */
@Component
class ListingAccess {

    private final ListingRepository repository;

    ListingAccess(ListingRepository repository) {
        this.repository = repository;
    }

    static boolean canSee(Listing listing, Viewer viewer) {
        return listing.getStatus().isPublic() || viewer.admin() || viewer.owns(listing);
    }

    /** @throws ResourceNotFoundException unless the viewer may see the listing */
    Listing visible(UUID id, Viewer viewer) {
        Listing listing = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Listing", id));
        return requireVisible(listing, viewer);
    }

    /**
     * The listing, for its seller to change.
     *
     * @throws ResourceNotFoundException if the viewer may not see it
     * @throws ForbiddenException        if the viewer can see it but it is not theirs
     */
    Listing owned(UUID id, Viewer viewer) {
        return requireOwner(visible(id, viewer), viewer);
    }

    /** Like {@link #owned} but locks the row until the end of the transaction (for photo changes). */
    Listing ownedForUpdate(UUID id, Viewer viewer) {
        Listing listing = repository.findWithLockById(id).orElseThrow(() -> new ResourceNotFoundException("Listing", id));
        return requireOwner(requireVisible(listing, viewer), viewer);
    }

    private static Listing requireVisible(Listing listing, Viewer viewer) {
        if (!canSee(listing, viewer)) {
            throw new ResourceNotFoundException("Listing", listing.getId());
        }
        return listing;
    }

    private static Listing requireOwner(Listing listing, Viewer viewer) {
        if (!viewer.owns(listing)) {
            throw new ForbiddenException("You can only change your own listings.");
        }
        return listing;
    }
}
