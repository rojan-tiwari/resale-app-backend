package com.resalemarket.listing;

import static com.resalemarket.listing.entity.ListingStatus.ACTIVE;
import static com.resalemarket.listing.entity.ListingStatus.ARCHIVED;
import static com.resalemarket.listing.entity.ListingStatus.DRAFT;
import static com.resalemarket.listing.entity.ListingStatus.PENDING_REVIEW;
import static com.resalemarket.listing.entity.ListingStatus.REJECTED;
import static com.resalemarket.listing.entity.ListingStatus.RESERVED;
import static com.resalemarket.listing.entity.ListingStatus.SOLD;
import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.listing.entity.ListingStatus;
import org.junit.jupiter.api.Test;

/** The state machine of docs/API.md section 4, row by row. */
class ListingStatusPolicyTest {

    private static java.util.Set<ListingStatus> owner(ListingStatus from, boolean review) {
        return ListingStatusPolicy.allowedTargets(from, true, false, review);
    }

    private static java.util.Set<ListingStatus> admin(ListingStatus from) {
        return ListingStatusPolicy.allowedTargets(from, false, true, false);
    }

    @Test
    void ownerOfADraftPublishesOrArchives() {
        assertThat(owner(DRAFT, false)).containsExactlyInAnyOrder(ACTIVE, ARCHIVED);
    }

    @Test
    void whenReviewIsRequiredADraftGoesToReviewInsteadOfLive() {
        assertThat(owner(DRAFT, true)).containsExactlyInAnyOrder(PENDING_REVIEW, ARCHIVED);
        assertThat(owner(DRAFT, true)).doesNotContain(ACTIVE);
    }

    @Test
    void ownerCanWithdrawAListingFromReviewButNotApproveIt() {
        assertThat(owner(PENDING_REVIEW, true)).containsExactly(DRAFT);
    }

    @Test
    void ownerOfALiveListingReservesSellsOrArchives() {
        assertThat(owner(ACTIVE, false)).containsExactlyInAnyOrder(RESERVED, SOLD, ARCHIVED);
        assertThat(owner(RESERVED, false)).containsExactlyInAnyOrder(ACTIVE, SOLD, ARCHIVED);
    }

    @Test
    void aSoldListingCanOnlyBeArchived() {
        assertThat(owner(SOLD, false)).containsExactly(ARCHIVED);
    }

    @Test
    void aRejectedListingCanBeFixedOrArchived() {
        assertThat(owner(REJECTED, false)).containsExactlyInAnyOrder(DRAFT, ARCHIVED);
    }

    @Test
    void anArchivedListingCanBeRestoredToADraft() {
        assertThat(owner(ARCHIVED, false)).containsExactly(DRAFT);
    }

    @Test
    void theOwnerNeverApprovesOrRejectsAListing() {
        for (ListingStatus from : ListingStatus.values()) {
            assertThat(owner(from, false)).doesNotContain(REJECTED, PENDING_REVIEW);
            assertThat(owner(from, true)).doesNotContain(REJECTED);
        }
    }

    @Test
    void anAdministratorApprovesOrRejectsWhatIsUnderReview() {
        assertThat(admin(PENDING_REVIEW)).containsExactlyInAnyOrder(ACTIVE, REJECTED, ARCHIVED);
    }

    @Test
    void anAdministratorCanRejectOrRemoveALiveListing() {
        assertThat(admin(ACTIVE)).containsExactlyInAnyOrder(REJECTED, ARCHIVED);
        assertThat(admin(RESERVED)).containsExactlyInAnyOrder(REJECTED, ARCHIVED);
    }

    @Test
    void anAdministratorCanRemoveAnyListingButNotMarkItSoldOrRestoreIt() {
        assertThat(admin(DRAFT)).containsExactly(ARCHIVED);
        assertThat(admin(SOLD)).containsExactly(ARCHIVED);
        assertThat(admin(REJECTED)).containsExactly(ARCHIVED);
        assertThat(admin(ARCHIVED)).isEmpty();
        for (ListingStatus from : ListingStatus.values()) {
            assertThat(admin(from)).doesNotContain(SOLD, RESERVED, DRAFT);
        }
    }

    @Test
    void anAdministratorWhoIsAlsoTheSellerHasBothSetsOfMoves() {
        assertThat(ListingStatusPolicy.allowedTargets(ACTIVE, true, true, false))
                .containsExactlyInAnyOrder(RESERVED, SOLD, ARCHIVED, REJECTED);
    }

    @Test
    void aStrangerHasNoMovesAtAll() {
        for (ListingStatus from : ListingStatus.values()) {
            assertThat(ListingStatusPolicy.allowedTargets(from, false, false, false)).isEmpty();
        }
    }

    @Test
    void noStatusCanMoveToItself() {
        for (ListingStatus from : ListingStatus.values()) {
            assertThat(ListingStatusPolicy.allowedTargets(from, true, true, false)).doesNotContain(from);
        }
    }

    @Test
    void onlyActiveAndReservedAreVisibleToThePublic() {
        for (ListingStatus status : ListingStatus.values()) {
            assertThat(status.isPublic()).isEqualTo(status == ACTIVE || status == RESERVED);
        }
    }

    @Test
    void onlyDraftActiveAndReservedCanBeEdited() {
        for (ListingStatus status : ListingStatus.values()) {
            assertThat(status.isEditable()).isEqualTo(status == DRAFT || status == ACTIVE || status == RESERVED);
        }
    }
}
