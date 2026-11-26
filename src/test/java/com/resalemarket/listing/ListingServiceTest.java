package com.resalemarket.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.category.CategoryService;
import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ForbiddenException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.exception.UnauthorizedException;
import com.resalemarket.listing.dto.ListingRequest;
import com.resalemarket.listing.dto.ListingResponse;
import com.resalemarket.listing.dto.ListingStatusRequest;
import com.resalemarket.listing.entity.Listing;
import com.resalemarket.listing.entity.ListingCondition;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.location.LocationService;
import com.resalemarket.support.MutableClock;
import com.resalemarket.user.UserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

/** The rules around the state machine, without a database: who is refused, what is checked, and what is saved. */
@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T05:00:00Z");

    @Mock
    private ListingRepository repository;

    @Mock
    private ListingImageRepository imageRepository;

    @Mock
    private ListingAccess access;

    @Mock
    private ListingResponses responses;

    @Mock
    private CategoryService categoryService;

    @Mock
    private LocationService locationService;

    @Mock
    private UserService userService;

    private final MutableClock clock = new MutableClock(NOW);
    private final UUID sellerId = UUID.randomUUID();
    private final Viewer seller = new Viewer(sellerId, false);
    private final Viewer stranger = new Viewer(UUID.randomUUID(), false);
    private final Viewer admin = new Viewer(UUID.randomUUID(), true);

    private ListingService service;

    @BeforeEach
    void setUp() {
        service = serviceWithReview(false);
    }

    private ListingService serviceWithReview(boolean requireReview) {
        ListingProperties properties = new ListingProperties(requireReview, DataSize.ofMegabytes(5), new ListingProperties.RateLimits(null, null));
        return new ListingService(
                repository, imageRepository, access, responses, categoryService, locationService, userService, properties, clock);
    }

    private Listing listing(ListingStatus status) {
        Listing listing = ListingFixtures.listing(sellerId, status);
        when(access.visible(listing.getId(), seller)).thenReturn(listing);
        return listing;
    }

    // --- creating ----------------------------------------------------------------------------------------------------

    @Test
    void creatingSavesADraftOfTheSignedInSellerAfterCheckingCategoryPlaceAndAccount() {
        ListingRequest request = new ListingRequest(
                "iPhone 13", "Bought last year, no scratches.", new BigDecimal("85000"), null, ListingCondition.GOOD, 1, 2, 3, 25, 1, null);
        when(repository.saveAndFlush(any(Listing.class))).thenAnswer(call -> call.getArgument(0));

        service.create(seller, request);

        verify(userService).requireActive(sellerId);
        verify(categoryService).requireSelectable(1, 2);
        verify(locationService).resolve(3, 25, 1);
        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ListingStatus.DRAFT);
        assertThat(saved.getValue().getSellerId()).isEqualTo(sellerId);
        assertThat(saved.getValue().getPriceType()).isEqualTo(com.resalemarket.listing.entity.PriceType.FIXED);
    }

    @Test
    void aBadCategoryOrPlaceSavesNothing() {
        ListingRequest request = new ListingRequest(
                "iPhone 13", "Bought last year, no scratches.", BigDecimal.ONE, null, ListingCondition.GOOD, 1, 2, 3, 25, 1, null);
        org.mockito.Mockito.doThrow(new FieldValidationException("subcategoryId", "nope")).when(categoryService).requireSelectable(1, 2);

        assertThatThrownBy(() -> service.create(seller, request)).isInstanceOf(FieldValidationException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aSuspendedAccountCreatesNothing() {
        org.mockito.Mockito.doThrow(new UnauthorizedException("ended")).when(userService).requireActive(sellerId);

        assertThatThrownBy(() -> service.create(seller, null)).isInstanceOf(UnauthorizedException.class);

        verifyNoInteractions(repository, categoryService, locationService);
    }

    // --- status ------------------------------------------------------------------------------------------------------

    @Test
    void publishingNeedsAPhotoAndSavesNothingWithout() {
        Listing draft = listing(ListingStatus.DRAFT);
        when(imageRepository.countByListingId(draft.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.changeStatus(draft.getId(), seller, new ListingStatusRequest(ListingStatus.ACTIVE, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Add at least one photo before publishing the listing.");

        assertThat(draft.getStatus()).isEqualTo(ListingStatus.DRAFT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publishingWithAPhotoGoesLiveAtTheCurrentTime() {
        Listing draft = listing(ListingStatus.DRAFT);
        when(imageRepository.countByListingId(draft.getId())).thenReturn(1L);

        service.changeStatus(draft.getId(), seller, new ListingStatusRequest(ListingStatus.ACTIVE, null));

        assertThat(draft.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(draft.getPublishedAt()).isEqualTo(NOW);
        verify(userService).requireActive(sellerId);
        verify(repository).saveAndFlush(draft);
    }

    @Test
    void withReviewRequiredADraftGoesToReviewAndCannotSkipIt() {
        ListingService reviewing = serviceWithReview(true);
        Listing draft = listing(ListingStatus.DRAFT);
        when(imageRepository.countByListingId(draft.getId())).thenReturn(2L);

        assertThatThrownBy(() -> reviewing.changeStatus(draft.getId(), seller, new ListingStatusRequest(ListingStatus.ACTIVE, null)))
                .isInstanceOf(BusinessRuleException.class);
        reviewing.changeStatus(draft.getId(), seller, new ListingStatusRequest(ListingStatus.PENDING_REVIEW, null));

        assertThat(draft.getStatus()).isEqualTo(ListingStatus.PENDING_REVIEW);
        assertThat(draft.getPublishedAt()).isNull();
    }

    @Test
    void aMoveTheMachineForbidsIsRefusedWithAReadableMessage() {
        Listing sold = listing(ListingStatus.SOLD);

        assertThatThrownBy(() -> service.changeStatus(sold.getId(), seller, new ListingStatusRequest(ListingStatus.ACTIVE, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("A listing that is sold cannot be moved to active.");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aStrangerWhoCanSeeALiveListingIsForbiddenNotTold404() {
        Listing live = ListingFixtures.listing(sellerId, ListingStatus.ACTIVE);
        when(access.visible(live.getId(), stranger)).thenReturn(live);

        assertThatThrownBy(() -> service.changeStatus(live.getId(), stranger, new ListingStatusRequest(ListingStatus.SOLD, null)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.archive(live.getId(), stranger)).isInstanceOf(ForbiddenException.class);
        assertThat(live.getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    void aListingTheCallerCannotSeeIsNotFound() {
        UUID id = UUID.randomUUID();
        when(access.visible(id, stranger)).thenThrow(new ResourceNotFoundException("Listing", id));

        assertThatThrownBy(() -> service.changeStatus(id, stranger, new ListingStatusRequest(ListingStatus.ARCHIVED, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectingNeedsAReasonAndKeepsIt() {
        Listing live = ListingFixtures.listing(sellerId, ListingStatus.ACTIVE);
        when(access.visible(live.getId(), admin)).thenReturn(live);

        assertThatThrownBy(() -> service.changeStatus(live.getId(), admin, new ListingStatusRequest(ListingStatus.REJECTED, null)))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        ex -> assertThat(ex.getViolations().get(0).field()).isEqualTo("reason"));
        assertThat(live.getStatus()).isEqualTo(ListingStatus.ACTIVE);

        service.changeStatus(live.getId(), admin, new ListingStatusRequest(ListingStatus.REJECTED, "Wrong photos"));

        assertThat(live.getStatus()).isEqualTo(ListingStatus.REJECTED);
        assertThat(live.getRejectionReason()).isEqualTo("Wrong photos");
    }

    @Test
    void anAdministratorApprovingDoesNotNeedThePhotoCheckOrTheSellersAccount() {
        ListingService reviewing = serviceWithReview(true);
        Listing pending = ListingFixtures.listing(sellerId, ListingStatus.PENDING_REVIEW);
        when(access.visible(pending.getId(), admin)).thenReturn(pending);

        reviewing.changeStatus(pending.getId(), admin, new ListingStatusRequest(ListingStatus.ACTIVE, null));

        assertThat(pending.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        verifyNoInteractions(userService, imageRepository);
    }

    // --- archiving ---------------------------------------------------------------------------------------------------

    @Test
    void archivingMovesTheListingToArchived() {
        Listing live = listing(ListingStatus.ACTIVE);

        service.archive(live.getId(), seller);

        assertThat(live.getStatus()).isEqualTo(ListingStatus.ARCHIVED);
        verify(repository).saveAndFlush(live);
    }

    @Test
    void archivingTwiceDoesNothingTheSecondTime() {
        Listing archived = listing(ListingStatus.ARCHIVED);

        service.archive(archived.getId(), seller);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aPendingListingCannotBeArchivedByItsSellerDirectly() {
        Listing pending = listing(ListingStatus.PENDING_REVIEW);

        assertThatThrownBy(() -> service.archive(pending.getId(), seller)).isInstanceOf(BusinessRuleException.class);
    }

    // --- editing -----------------------------------------------------------------------------------------------------

    @Test
    void aFrozenListingCannotBeEditedAndTheMessageSaysHowToUnfreezeIt() {
        Listing archived = ListingFixtures.listing(sellerId, ListingStatus.ARCHIVED);
        when(access.owned(archived.getId(), seller)).thenReturn(archived);
        ListingRequest request = new ListingRequest(
                "iPhone 13", "Bought last year, no scratches.", BigDecimal.ONE, null, ListingCondition.GOOD, 1, 2, 3, 25, 1, null);

        assertThatThrownBy(() -> service.update(archived.getId(), seller, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("A listing that is archived cannot be edited. Move it back to draft first.");
        verify(repository, never()).saveAndFlush(any());
        verify(categoryService, never()).requireSelectable(anyInt(), any());
    }

    // --- reading -----------------------------------------------------------------------------------------------------

    @Test
    void aVisitFromSomeoneElseCountsAsAViewAndTheSellersOwnDoesNot() {
        Listing live = ListingFixtures.listing(sellerId, ListingStatus.ACTIVE);
        when(access.visible(live.getId(), stranger)).thenReturn(live);
        when(access.visible(live.getId(), seller)).thenReturn(live);
        when(access.visible(live.getId(), Viewer.ANONYMOUS)).thenReturn(live);
        when(responses.detail(any(), any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(null);

        service.get(live.getId(), stranger);
        service.get(live.getId(), Viewer.ANONYMOUS);
        service.get(live.getId(), seller);

        verify(repository, org.mockito.Mockito.times(2)).incrementViewCount(live.getId());
    }

    @Test
    void aPrivateListingIsNeverCountedAsViewed() {
        Listing draft = ListingFixtures.listing(sellerId, ListingStatus.DRAFT);
        when(access.visible(draft.getId(), admin)).thenReturn(draft);

        service.get(draft.getId(), admin);

        verify(repository, never()).incrementViewCount(any());
    }

    @Test
    void theResponseCarriesTheViewJustCounted() {
        Listing live = ListingFixtures.listing(sellerId, ListingStatus.ACTIVE);
        org.springframework.test.util.ReflectionTestUtils.setField(live, "viewCount", 41L);
        when(access.visible(live.getId(), stranger)).thenReturn(live);
        ListingResponse expected = org.mockito.Mockito.mock(ListingResponse.class);
        when(responses.detail(live, stranger, 42L)).thenReturn(expected);

        assertThat(service.get(live.getId(), stranger)).isSameAs(expected);
    }
}
