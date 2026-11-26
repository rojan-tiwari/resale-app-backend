package com.resalemarket.listing;

import com.resalemarket.listing.entity.Listing;
import com.resalemarket.listing.entity.ListingStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingRepository extends JpaRepository<Listing, UUID> {

    /**
     * The listing locked for update. Photo changes take this lock so that two uploads at once cannot both count seven photos
     * and add two more.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Listing> findWithLockById(UUID id);

    Page<Listing> findBySellerIdAndStatus(UUID sellerId, ListingStatus status, Pageable pageable);

    Page<Listing> findBySellerIdAndStatusNot(UUID sellerId, ListingStatus status, Pageable pageable);

    /** A seller's public listings; {@code statuses} is always {@link ListingStatus#PUBLIC}. */
    Page<Listing> findBySellerIdAndStatusIn(UUID sellerId, Collection<ListingStatus> statuses, Pageable pageable);

    /**
     * Counts one view. An atomic statement (not "load, add one, save"), so views that arrive together are all counted and the
     * listing's version, and with it a seller's edit in progress, is left alone.
     */
    @Modifying
    @Query("update Listing l set l.viewCount = l.viewCount + 1 where l.id = :id")
    int incrementViewCount(@Param("id") UUID id);

    /**
     * Public listings in the same category, best match first: the same subcategory, then the same district, then the same
     * province, then the newest. {@code statuses} is always {@link ListingStatus#PUBLIC}; the {@code pageable} only carries the
     * limit. A subcategory of {@code 0} matches nothing.
     */
    @Query("""
            select l from Listing l
            where l.status in :statuses and l.id <> :excludeId and l.categoryId = :categoryId
            order by case when l.subcategoryId = :subcategoryId then 0 else 1 end,
                     case when l.districtId = :districtId then 0 when l.provinceId = :provinceId then 1 else 2 end,
                     l.publishedAt desc, l.id
            """)
    List<Listing> findSimilar(
            @Param("statuses") Collection<ListingStatus> statuses,
            @Param("excludeId") UUID excludeId,
            @Param("categoryId") Integer categoryId,
            @Param("subcategoryId") Integer subcategoryId,
            @Param("districtId") Integer districtId,
            @Param("provinceId") Integer provinceId,
            Pageable pageable);
}
