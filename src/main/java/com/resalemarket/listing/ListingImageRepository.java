package com.resalemarket.listing;

import com.resalemarket.listing.entity.ListingImage;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingImageRepository extends JpaRepository<ListingImage, UUID> {

    List<ListingImage> findByListingIdOrderBySortOrderAsc(UUID listingId);

    /** The main photos of many listings in one query (for a page of cards). */
    List<ListingImage> findByListingIdInAndPrimaryTrue(Collection<UUID> listingIds);

    Optional<ListingImage> findByIdAndListingId(UUID id, UUID listingId);

    long countByListingId(UUID listingId);
}
