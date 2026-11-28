package com.resalemarket.favorite;

import com.resalemarket.favorite.entity.Favorite;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {

    boolean existsByUserIdAndListingId(UUID userId, UUID listingId);

    long countByUserId(UUID userId);

    /** Newest saved first; served by {@code idx_favorites_user_created}. */
    Page<Favorite> findByUserIdOrderByCreatedAtDescIdAsc(UUID userId, Pageable pageable);

    /** Every listing id the member saved, newest first. At most {@code app.favorite.max-per-user} rows. */
    @Query("select f.listingId from Favorite f where f.userId = :userId order by f.createdAt desc, f.id")
    List<UUID> findListingIdsByUserId(@Param("userId") UUID userId);

    /** @return how many rows were removed (0 when the listing was not saved) */
    @Modifying
    @Query("delete from Favorite f where f.userId = :userId and f.listingId = :listingId")
    int deleteByUserIdAndListingId(@Param("userId") UUID userId, @Param("listingId") UUID listingId);
}
