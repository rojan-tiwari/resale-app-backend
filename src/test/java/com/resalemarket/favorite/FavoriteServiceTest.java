package com.resalemarket.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.ConflictException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.exception.UnauthorizedException;
import com.resalemarket.favorite.dto.FavoriteResponse;
import com.resalemarket.favorite.entity.Favorite;
import com.resalemarket.listing.ListingService;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.listing.entity.ListingCondition;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.listing.entity.PriceType;
import com.resalemarket.user.UserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FavoriteServiceTest {

    private static final Instant SAVED_AT = Instant.parse("2026-11-20T10:00:00Z");

    @Mock
    private FavoriteRepository repository;

    @Mock
    private ListingService listingService;

    @Mock
    private UserService userService;

    private final UUID userId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();
    private FavoriteService service;

    @BeforeEach
    void setUp() {
        service = new FavoriteService(repository, listingService, userService, new FavoriteProperties(3, new FavoriteProperties.RateLimits(null)));
    }

    private static ListingSummaryResponse card(UUID id, ListingStatus status) {
        return new ListingSummaryResponse(id, "title", BigDecimal.TEN, "NPR", PriceType.FIXED, ListingCondition.GOOD, status,
                null, null, null, null, null, false, SAVED_AT, SAVED_AT);
    }

    private Favorite saved(UUID listing) {
        Favorite favorite = Favorite.of(userId, listing);
        ReflectionTestUtils.setField(favorite, "createdAt", SAVED_AT);
        return favorite;
    }

    // --- add ---------------------------------------------------------------------------------------------------------

    @Test
    void savingChecksTheAccountThenTheListingThenSaves() {
        when(repository.saveAndFlush(any(Favorite.class))).thenAnswer(call -> {
            Favorite favorite = call.getArgument(0);
            ReflectionTestUtils.setField(favorite, "createdAt", SAVED_AT);
            return favorite;
        });
        when(listingService.savedCards(List.of(listingId))).thenReturn(Map.of(listingId, card(listingId, ListingStatus.ACTIVE)));

        FavoriteResponse response = service.add(userId, listingId);

        verify(userService).requireActive(userId);
        verify(listingService).requireLive(listingId);
        assertThat(response.listingId()).isEqualTo(listingId);
        assertThat(response.available()).isTrue();
        assertThat(response.savedAt()).isEqualTo(SAVED_AT);
    }

    @Test
    void aSuspendedMemberOrAListingThatIsNotLiveSavesNothing() {
        doThrow(new UnauthorizedException("ended")).when(userService).requireActive(userId);
        assertThatThrownBy(() -> service.add(userId, listingId)).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(repository, listingService);

        org.mockito.Mockito.reset(userService);
        doThrow(new ResourceNotFoundException("Listing", listingId)).when(listingService).requireLive(listingId);
        assertThatThrownBy(() -> service.add(userId, listingId)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void savingTwiceIsAConflict() {
        when(repository.existsByUserIdAndListingId(userId, listingId)).thenReturn(true);

        assertThatThrownBy(() -> service.add(userId, listingId)).isInstanceOf(ConflictException.class).hasMessage("This ad is already in your favourites.");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theLimitStopsTheNextSaveWithAnExplanation() {
        when(repository.countByUserId(userId)).thenReturn(3L);

        assertThatThrownBy(() -> service.add(userId, listingId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("You can save at most 3 ads. Remove some you no longer need first.");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void oneBelowTheLimitStillSaves() {
        when(repository.countByUserId(userId)).thenReturn(2L);
        when(repository.saveAndFlush(any(Favorite.class))).thenAnswer(call -> call.getArgument(0));

        service.add(userId, listingId);

        verify(repository).saveAndFlush(any(Favorite.class));
    }

    // --- remove ------------------------------------------------------------------------------------------------------

    @Test
    void removingNeverComplainsAboutAnAdThatWasNotSaved() {
        when(repository.deleteByUserIdAndListingId(userId, listingId)).thenReturn(0);

        service.remove(userId, listingId);

        verify(repository).deleteByUserIdAndListingId(userId, listingId);
    }

    // --- the list ----------------------------------------------------------------------------------------------------

    @Test
    void theListFlagsWhatIsNoLongerForSaleAndHandsOutNoCardForWhatWasTakenDown() {
        UUID live = UUID.randomUUID();
        UUID reserved = UUID.randomUUID();
        UUID sold = UUID.randomUUID();
        UUID takenDown = UUID.randomUUID();
        Page<Favorite> page = new PageImpl<>(List.of(saved(live), saved(reserved), saved(sold), saved(takenDown)), PageRequest.of(0, 20), 4);
        when(repository.findByUserIdOrderByCreatedAtDescIdAsc(any(), any())).thenReturn(page);
        when(listingService.savedCards(List.of(live, reserved, sold, takenDown))).thenReturn(Map.of(
                live, card(live, ListingStatus.ACTIVE),
                reserved, card(reserved, ListingStatus.RESERVED),
                sold, card(sold, ListingStatus.SOLD)));

        List<FavoriteResponse> items = service.list(userId, PageRequest.of(0, 20)).content();

        assertThat(items).extracting(FavoriteResponse::available).containsExactly(true, true, false, false);
        assertThat(items.get(2).listing().status()).isEqualTo(ListingStatus.SOLD);
        assertThat(items.get(3).listing()).isNull();
    }

    @Test
    void theClientsSortIsIgnored() {
        when(repository.findByUserIdOrderByCreatedAtDescIdAsc(any(), any())).thenReturn(Page.empty());

        service.list(userId, PageRequest.of(1, 5, org.springframework.data.domain.Sort.by("listingId")));

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(repository).findByUserIdOrderByCreatedAtDescIdAsc(any(), captor.capture());
        assertThat(captor.getValue().getSort().isUnsorted()).isTrue();
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
    }

    // --- what a member likes -------------------------------------------------------------------------------------------

    @Test
    void favouriteCategoriesAreOrderedByHowOftenTheyAreSavedThenByLowerId() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        when(repository.findListingIdsByUserId(userId)).thenReturn(List.of(a, b, c, d, e));
        when(listingService.categoryIds(List.of(a, b, c, d, e))).thenReturn(Map.of(a, 7, b, 7, c, 3, d, 9, e, 3));

        assertThat(service.favouriteCategoryIds(userId, 2)).containsExactly(3, 7);          // 7 and 3 are saved twice: lower id first, then 9
        assertThat(service.favouriteCategoryIds(userId, 5)).containsExactly(3, 7, 9);
    }

    @Test
    void noFavouritesMeansNoFavouriteCategories() {
        when(repository.findListingIdsByUserId(userId)).thenReturn(List.of());
        when(listingService.categoryIds(List.of())).thenReturn(Map.of());

        assertThat(service.favouriteCategoryIds(userId, 3)).isEmpty();
    }

    @Test
    void theSettingsRefuseAnUnusableLimit() {
        assertThatThrownBy(() -> new FavoriteProperties(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new FavoriteProperties(500, null).rateLimits().write().attempts()).isEqualTo(300);
    }
}
