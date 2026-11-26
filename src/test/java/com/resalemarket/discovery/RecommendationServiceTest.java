package com.resalemarket.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.resalemarket.discovery.dto.RecommendationsResponse;
import com.resalemarket.discovery.dto.RecommendationsResponse.Basis;
import com.resalemarket.favorite.FavoriteService;
import com.resalemarket.listing.ListingSearchService;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.listing.entity.ListingCondition;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.listing.entity.PriceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private FavoriteService favoriteService;

    @Mock
    private ListingSearchService searchService;

    private final UUID userId = UUID.randomUUID();
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(favoriteService, searchService);
    }

    private static ListingSummaryResponse card(String title) {
        return new ListingSummaryResponse(UUID.randomUUID(), title, BigDecimal.TEN, "NPR", PriceType.FIXED, ListingCondition.GOOD,
                ListingStatus.ACTIVE, null, null, null, null, null, false, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void aVisitorGetsWhatIsPopular() {
        List<ListingSummaryResponse> popular = List.of(card("p1"), card("p2"));
        when(searchService.popular(8, null)).thenReturn(popular);

        RecommendationsResponse response = service.recommend(null, 8);

        assertThat(response.basis()).isEqualTo(Basis.POPULAR);
        assertThat(response.listings()).isEqualTo(popular);
        verify(favoriteService, never()).favouriteCategoryIds(org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void aMemberWhoSavedNothingGetsPopularAdsWithoutTheirOwn() {
        when(favoriteService.favouriteCategoryIds(userId, 3)).thenReturn(List.of());
        when(searchService.popular(8, userId)).thenReturn(List.of(card("p1")));

        RecommendationsResponse response = service.recommend(userId, 8);

        assertThat(response.basis()).isEqualTo(Basis.POPULAR);
        verify(searchService).popular(8, userId);
    }

    @Test
    void aMemberWithFavouritesGetsAdsFromThoseCategoriesLeavingOutTheirOwnAndSavedOnes() {
        UUID savedId = UUID.randomUUID();
        List<ListingSummaryResponse> similar = List.of(card("s1"), card("s2"));
        when(favoriteService.favouriteCategoryIds(userId, 3)).thenReturn(List.of(5, 9));
        when(favoriteService.listingIds(userId)).thenReturn(List.of(savedId));
        when(searchService.newestInCategories(List.of(5, 9), List.of(savedId), userId, 2)).thenReturn(similar);

        RecommendationsResponse response = service.recommend(userId, 2);

        assertThat(response.basis()).isEqualTo(Basis.FAVOURITE_CATEGORIES);
        assertThat(response.listings()).isEqualTo(similar);
        verify(searchService, never()).popular(anyInt(), org.mockito.ArgumentMatchers.any());     // nothing to top up
    }

    @Test
    void aShortListIsToppedUpWithPopularAdsThatAreNewToIt() {
        ListingSummaryResponse similar = card("similar");
        ListingSummaryResponse popularDuplicate = similar;
        ListingSummaryResponse alreadySaved = card("saved");
        ListingSummaryResponse fresh1 = card("fresh1");
        ListingSummaryResponse fresh2 = card("fresh2");
        ListingSummaryResponse fresh3 = card("fresh3");
        when(favoriteService.favouriteCategoryIds(userId, 3)).thenReturn(List.of(5));
        when(favoriteService.listingIds(userId)).thenReturn(List.of(alreadySaved.id()));
        when(searchService.newestInCategories(eq(List.of(5)), anyCollection(), eq(userId), eq(3))).thenReturn(List.of(similar));
        when(searchService.popular(9, userId)).thenReturn(List.of(popularDuplicate, alreadySaved, fresh1, fresh2, fresh3));

        RecommendationsResponse response = service.recommend(userId, 3);

        assertThat(response.basis()).isEqualTo(Basis.FAVOURITE_CATEGORIES);
        assertThat(response.listings()).containsExactly(similar, fresh1, fresh2);       // no duplicate, no saved ad, no more than asked for
    }
}
