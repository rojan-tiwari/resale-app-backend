package com.resalemarket.listing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * An advertisement. Other modules' data is held as plain ids (seller, category, place), because each module owns its tables.
 *
 * <p>State changes go through {@link #edit} and {@link #changeStatus}; the entity has no setters. Whether a change is
 * <em>allowed</em> (who may do it, from which status) is decided before, by {@code ListingStatusPolicy} and the service.
 */
@Entity
@Table(name = "listings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Listing {

    public static final String CURRENCY = "NPR";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private UUID sellerId;

    @Column(name = "category_id", nullable = false)
    private Integer categoryId;

    @Column(name = "subcategory_id")
    private Integer subcategoryId;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 5000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency = CURRENCY;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 15)
    private PriceType priceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", nullable = false, length = 20)
    private ListingCondition condition;

    @Column(name = "province_id", nullable = false)
    private Integer provinceId;

    @Column(name = "district_id", nullable = false)
    private Integer districtId;

    @Column(name = "municipality_id")
    private Integer municipalityId;

    /** Free text for the area or tole, e.g. "Baneshwor". */
    @Column(length = 120)
    private String locality;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status = ListingStatus.DRAFT;

    /** Set by an administrator or a later promotion feature; never by the seller, so it is read-only here. */
    @Column(name = "is_featured", nullable = false, insertable = false, updatable = false)
    private boolean featured;

    @Column(name = "featured_until", insertable = false, updatable = false)
    private Instant featuredUntil;

    /**
     * Counted with an atomic {@code UPDATE ... SET view_count = view_count + 1}, never by saving this entity: the column is
     * read-only here so that saving a listing can never write back a stale count over views that arrived in the meantime.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "view_count", nullable = false, insertable = false, updatable = false)
    private long viewCount;

    /** Why a moderator rejected the listing; kept only while the status is {@code REJECTED}. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "sold_at")
    private Instant soldAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by the {@code trg_listings_updated_at} trigger (which skips view-count-only updates). */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Optimistic locking: two people editing at once cannot silently overwrite each other. {@code null} until the first save. */
    @Version
    private Long version;

    /** A new listing is always a draft: it has no photos yet, and publishing is a separate, checked step. */
    public static Listing draft(UUID sellerId, ListingDetails details) {
        Listing listing = new Listing();
        listing.sellerId = sellerId;
        listing.apply(details);
        return listing;
    }

    /** Replaces everything the seller wrote. */
    public void edit(ListingDetails details) {
        apply(details);
    }

    /**
     * Moves the listing to {@code next} and keeps the dates the database insists on consistent: {@code published_at} for
     * every status that is or was live, {@code sold_at} for sold. Publishing (from a draft, or after approval) starts the
     * "posted" date afresh, so restoring an old ad puts it at the top again, while reserving and un-reserving does not.
     *
     * @param reason why a moderator rejected it; only used for {@link ListingStatus#REJECTED}
     */
    public void changeStatus(ListingStatus next, Instant now, String reason) {
        ListingStatus previous = this.status;
        if (next == ListingStatus.ACTIVE && (previous == ListingStatus.DRAFT || previous == ListingStatus.PENDING_REVIEW)) {
            this.publishedAt = now;
        }
        if (next == ListingStatus.SOLD) {
            this.soldAt = now;
        }
        this.rejectionReason = next == ListingStatus.REJECTED ? reason : null;
        this.status = next;
    }

    public boolean isOwnedBy(UUID userId) {
        return sellerId.equals(userId);
    }

    /** Featured (with its promotion still running) at the given moment. */
    public boolean isFeaturedAt(Instant now) {
        return featured && (featuredUntil == null || featuredUntil.isAfter(now));
    }

    private void apply(ListingDetails details) {
        this.title = details.title();
        this.description = details.description();
        this.price = details.price();
        this.priceType = details.priceType();
        this.condition = details.condition();
        this.categoryId = details.categoryId();
        this.subcategoryId = details.subcategoryId();
        this.provinceId = details.provinceId();
        this.districtId = details.districtId();
        this.municipalityId = details.municipalityId();
        this.locality = details.locality();
    }
}
