package com.resalemarket.listing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.listing.entity.Listing;
import com.resalemarket.listing.entity.ListingStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The dates and the reason that the database insists on staying consistent with the status. */
class ListingTest {

    private static final Instant T1 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-03T10:00:00Z");

    private final UUID seller = UUID.randomUUID();

    @Test
    void aNewListingIsADraftWithNoDates() {
        Listing listing = Listing.draft(seller, ListingFixtures.details());

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.DRAFT);
        assertThat(listing.getSellerId()).isEqualTo(seller);
        assertThat(listing.getPublishedAt()).isNull();
        assertThat(listing.getSoldAt()).isNull();
        assertThat(listing.getCurrency()).isEqualTo("NPR");
        assertThat(listing.isOwnedBy(seller)).isTrue();
        assertThat(listing.isOwnedBy(UUID.randomUUID())).isFalse();
    }

    @Test
    void publishingSetsThePostedDate() {
        Listing listing = ListingFixtures.listing(seller, ListingStatus.DRAFT);

        listing.changeStatus(ListingStatus.ACTIVE, T1, null);

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(listing.getPublishedAt()).isEqualTo(T1);
    }

    @Test
    void approvalAfterReviewSetsThePostedDateToTheApproval() {
        Listing listing = ListingFixtures.listing(seller, ListingStatus.PENDING_REVIEW);

        listing.changeStatus(ListingStatus.ACTIVE, T2, null);

        assertThat(listing.getPublishedAt()).isEqualTo(T2);
    }

    @Test
    void reservingAndUnreservingKeepsThePostedDate() {
        Listing listing = ListingFixtures.listing(seller, ListingStatus.DRAFT);
        listing.changeStatus(ListingStatus.ACTIVE, T1, null);

        listing.changeStatus(ListingStatus.RESERVED, T2, null);
        listing.changeStatus(ListingStatus.ACTIVE, T3, null);

        assertThat(listing.getPublishedAt()).isEqualTo(T1);
    }

    @Test
    void relistingAfterRestoringStartsTheClockAgain() {
        Listing listing = ListingFixtures.listing(seller, ListingStatus.DRAFT);
        listing.changeStatus(ListingStatus.ACTIVE, T1, null);
        listing.changeStatus(ListingStatus.ARCHIVED, T2, null);
        listing.changeStatus(ListingStatus.DRAFT, T2, null);

        listing.changeStatus(ListingStatus.ACTIVE, T3, null);

        assertThat(listing.getPublishedAt()).isEqualTo(T3);
    }

    @Test
    void sellingRecordsWhenAndIsKeptWhenArchived() {
        Listing listing = ListingFixtures.listing(seller, ListingStatus.ACTIVE);
        ReflectionTestUtils.setField(listing, "publishedAt", T1);

        listing.changeStatus(ListingStatus.SOLD, T2, null);
        listing.changeStatus(ListingStatus.ARCHIVED, T3, null);

        assertThat(listing.getSoldAt()).isEqualTo(T2);
        assertThat(listing.getPublishedAt()).isEqualTo(T1);
    }

    @Test
    void theRejectionReasonLivesOnlyWhileRejected() {
        Listing listing = ListingFixtures.listing(seller, ListingStatus.ACTIVE);

        listing.changeStatus(ListingStatus.REJECTED, T1, "Wrong photos");
        assertThat(listing.getRejectionReason()).isEqualTo("Wrong photos");

        listing.changeStatus(ListingStatus.DRAFT, T2, null);
        assertThat(listing.getRejectionReason()).isNull();
    }

    @Test
    void aReasonGivenForAnotherStatusIsNotKept() {
        Listing listing = ListingFixtures.listing(seller, ListingStatus.ACTIVE);

        listing.changeStatus(ListingStatus.ARCHIVED, T1, "ignored");

        assertThat(listing.getRejectionReason()).isNull();
    }

    @Test
    void editingReplacesTheContentAndNothingElse() {
        Listing listing = ListingFixtures.listing(seller, ListingStatus.ACTIVE);
        var changed = new com.resalemarket.listing.entity.ListingDetails(
                "New title", "New description text", new java.math.BigDecimal("70000.00"),
                com.resalemarket.listing.entity.PriceType.FIXED, com.resalemarket.listing.entity.ListingCondition.GOOD,
                5, null, 1, 2, null, null);

        listing.edit(changed);

        assertThat(listing.getTitle()).isEqualTo("New title");
        assertThat(listing.getPrice()).isEqualByComparingTo("70000");
        assertThat(listing.getSubcategoryId()).isNull();
        assertThat(listing.getMunicipalityId()).isNull();
        assertThat(listing.getLocality()).isNull();
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(listing.getSellerId()).isEqualTo(seller);
    }

    @Test
    void aPromotionCountsOnlyWhileItRuns() {
        Listing listing = ListingFixtures.listing(seller, ListingStatus.ACTIVE);
        assertThat(listing.isFeaturedAt(T1)).isFalse();

        ReflectionTestUtils.setField(listing, "featured", true);
        assertThat(listing.isFeaturedAt(T1)).isTrue();                       // no end date

        ReflectionTestUtils.setField(listing, "featuredUntil", T2);
        assertThat(listing.isFeaturedAt(T1)).isTrue();
        assertThat(listing.isFeaturedAt(T3)).isFalse();
    }
}
