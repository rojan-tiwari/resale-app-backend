package com.resalemarket.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.response.PageResponse;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.listing.entity.Listing;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.listing.ListingSearchRepository.PopularRow;
import com.resalemarket.support.MutableClock;
import com.resalemarket.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

/** Paging, the count shortcut, the browse limit and what is left out, without a database. */
@ExtendWith(MockitoExtension.class)
class ListingSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T05:00:00Z");

    @Mock
    private ListingSearchRepository searchRepository;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private ListingResponses responses;

    @Mock
    private UserService userService;

    private final UUID sellerId = UUID.randomUUID();
    private final MutableClock clock = new MutableClock(NOW);
    private ListingSearchService service;

    @BeforeEach
    void setUp() {
        service = serviceWithCacheTtl(Duration.ofMinutes(5));
    }

    private ListingSearchService serviceWithCacheTtl(Duration ttl) {
        return new ListingSearchService(searchRepository, listingRepository, responses, userService, clock, new SearchProperties(ttl));
    }

    private ListingSearchCriteria everything() {
        return ListingSearchCriteria.everything();
    }

    private List<Listing> listings(int count, ListingStatus status) {
        List<Listing> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(ListingFixtures.listing(sellerId, status));
        }
        return list;
    }

    private List<UUID> idsOf(List<Listing> listings) {
        return listings.stream().map(Listing::getId).toList();
    }

    private void repositoryReturns(List<Listing> listings) {
        when(listingRepository.findAllById(anyCollection())).thenReturn(listings);
        when(responses.summaries(any())).thenAnswer(call -> ((List<Listing>) call.getArgument(0)).stream()
                .map(listing -> new ListingSummaryResponse(listing.getId(), listing.getTitle(), listing.getPrice(), "NPR", listing.getPriceType(),
                        listing.getCondition(), listing.getStatus(), null, null, null, null, null, false, null, null))
                .toList());
    }

    // --- paging --------------------------------------------------------------------------------------------------------

    @Test
    void aShortFirstPageKnowsItsTotalWithoutCounting() {
        List<Listing> found = listings(3, ListingStatus.ACTIVE);
        when(searchRepository.ids(any(), anyCollection(), any(), eq(20), eq(0L))).thenReturn(idsOf(found));
        repositoryReturns(found);

        PageResponse<ListingSummaryResponse> page = service.search(everything(), PageRequest.of(0, 20));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.last()).isTrue();
        verify(searchRepository, never()).count(any(), anyCollection(), any());
    }

    @Test
    void aFullPageNeedsTheCount() {
        List<Listing> found = listings(2, ListingStatus.ACTIVE);
        when(searchRepository.ids(any(), anyCollection(), any(), eq(2), eq(0L))).thenReturn(idsOf(found));
        when(searchRepository.count(any(), anyCollection(), any())).thenReturn(5L);
        repositoryReturns(found);

        PageResponse<ListingSummaryResponse> page = service.search(everything(), PageRequest.of(0, 2));

        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.last()).isFalse();
    }

    @Test
    void aShortLaterPageAlsoKnowsTheTotal() {
        List<Listing> found = listings(1, ListingStatus.ACTIVE);
        when(searchRepository.ids(any(), anyCollection(), any(), eq(2), eq(4L))).thenReturn(idsOf(found));
        repositoryReturns(found);

        PageResponse<ListingSummaryResponse> page = service.search(everything(), PageRequest.of(2, 2));

        assertThat(page.totalElements()).isEqualTo(5);
        verify(searchRepository, never()).count(any(), anyCollection(), any());
    }

    @Test
    void anEmptyFirstPageIsAZeroTotalWithoutCounting() {
        when(searchRepository.ids(any(), anyCollection(), any(), anyInt(), anyLong())).thenReturn(List.of());

        PageResponse<ListingSummaryResponse> page = service.search(everything(), PageRequest.of(0, 20));

        assertThat(page.totalElements()).isZero();
        assertThat(page.content()).isEmpty();
        verify(searchRepository, never()).count(any(), anyCollection(), any());
        verifyNoInteractions(listingRepository);
    }

    @Test
    void aPagePastTheEndStillReportsTheRealTotal() {
        when(searchRepository.ids(any(), anyCollection(), any(), anyInt(), anyLong())).thenReturn(List.of());
        when(searchRepository.count(any(), anyCollection(), any())).thenReturn(7L);

        PageResponse<ListingSummaryResponse> page = service.search(everything(), PageRequest.of(5, 20));

        assertThat(page.totalElements()).isEqualTo(7);
        assertThat(page.content()).isEmpty();
    }

    @Test
    void resultsKeepTheOrderOfTheQueryNotOfTheTable() {
        List<Listing> found = listings(3, ListingStatus.ACTIVE);
        List<UUID> inQueryOrder = List.of(found.get(2).getId(), found.get(0).getId(), found.get(1).getId());
        when(searchRepository.ids(any(), anyCollection(), any(), anyInt(), anyLong())).thenReturn(inQueryOrder);
        repositoryReturns(found);

        assertThat(service.search(everything(), PageRequest.of(0, 20)).content()).extracting(ListingSummaryResponse::id).containsExactlyElementsOf(inQueryOrder);
    }

    @Test
    void aListingThatStoppedBeingLiveBetweenTheQueriesIsLeftOutNotShownWrongly() {
        Listing live = ListingFixtures.listing(sellerId, ListingStatus.ACTIVE);
        Listing justSold = ListingFixtures.listing(sellerId, ListingStatus.SOLD);
        when(searchRepository.ids(any(), anyCollection(), any(), anyInt(), anyLong())).thenReturn(List.of(live.getId(), justSold.getId()));
        repositoryReturns(List.of(live, justSold));

        assertThat(service.search(everything(), PageRequest.of(0, 20)).content()).extracting(ListingSummaryResponse::id).containsExactly(live.getId());
    }

    // --- limits and exclusions -----------------------------------------------------------------------------------------

    @Test
    void onlyTheFirstTenThousandResultsCanBeBrowsed() {
        assertThatThrownBy(() -> service.search(everything(), PageRequest.of(100, 100)))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.getViolations().get(0).field()).isEqualTo("page"));
        verifyNoInteractions(searchRepository, userService);

        when(searchRepository.ids(any(), anyCollection(), any(), anyInt(), anyLong())).thenReturn(List.of());
        service.search(everything(), PageRequest.of(99, 100));     // the last page inside the window
    }

    @Test
    void membersWhoAreSuspendedOrDeletedAreHandedToTheQueryAsExclusions() {
        UUID suspended = UUID.randomUUID();
        when(userService.inactiveUserIds()).thenReturn(List.of(suspended));
        when(searchRepository.ids(any(), eq(List.of(suspended)), any(), anyInt(), anyLong())).thenReturn(List.of());

        service.search(everything(), PageRequest.of(0, 20));
        service.recent(8);
        service.featured(8);

        verify(searchRepository).ids(any(), eq(List.of(suspended)), eq(NOW), eq(20), eq(0L));
        verify(searchRepository).featured(eq(List.of(suspended)), eq(NOW), eq(8));
    }

    @Test
    void noCategoriesMeansNoQuery() {
        assertThat(service.newestInCategories(List.of(), List.of(), null, 8)).isEmpty();
        verifyNoInteractions(searchRepository);
    }

    // --- the popular pool ------------------------------------------------------------------------------------------------

    private PopularRow row(Listing listing) {
        return new PopularRow(listing.getId(), listing.getSellerId());
    }

    @Test
    void thePopularPoolIsComputedOncePerTtlAndSharedByEveryCaller() {
        List<Listing> found = listings(3, ListingStatus.ACTIVE);
        when(searchRepository.mostViewed(any(), anyInt())).thenReturn(found.stream().map(this::row).toList());
        repositoryReturns(found);

        service.popular(8, null);
        service.popular(8, UUID.randomUUID());
        service.popular(3, null);
        verify(searchRepository, org.mockito.Mockito.times(1)).mostViewed(any(), anyInt());

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        service.popular(8, null);
        verify(searchRepository, org.mockito.Mockito.times(2)).mostViewed(any(), anyInt());
    }

    @Test
    void aTtlOfZeroSwitchesTheCacheOff() {
        ListingSearchService uncached = serviceWithCacheTtl(Duration.ZERO);
        List<Listing> found = listings(2, ListingStatus.ACTIVE);
        when(searchRepository.mostViewed(any(), anyInt())).thenReturn(found.stream().map(this::row).toList());
        repositoryReturns(found);

        uncached.popular(8, null);
        uncached.popular(8, null);

        verify(searchRepository, org.mockito.Mockito.times(2)).mostViewed(any(), anyInt());
    }

    @Test
    void whatDependsOnTheCallerIsAppliedAfterTheCacheEveryTime() {
        UUID suspended = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        Listing fine = ListingFixtures.listing(sellerId, ListingStatus.ACTIVE);
        Listing ofSuspended = ListingFixtures.listing(suspended, ListingStatus.ACTIVE);
        Listing callersOwn = ListingFixtures.listing(caller, ListingStatus.ACTIVE);
        when(searchRepository.mostViewed(any(), anyInt())).thenReturn(List.of(row(ofSuspended), row(callersOwn), row(fine)));
        repositoryReturns(List.of(fine, ofSuspended, callersOwn));
        when(userService.inactiveUserIds()).thenReturn(List.of(suspended));

        assertThat(service.popular(8, caller)).extracting(ListingSummaryResponse::id).containsExactly(fine.getId());
        assertThat(service.popular(8, null)).extracting(ListingSummaryResponse::id).containsExactly(callersOwn.getId(), fine.getId());
        verify(searchRepository, org.mockito.Mockito.times(1)).mostViewed(any(), anyInt());          // the same cached pool served both
    }

    @Test
    void aListingThatSoldSinceThePoolWasMadeIsNotShownAndTheListIsTrimmedToTheLimit() {
        Listing sold = ListingFixtures.listing(sellerId, ListingStatus.SOLD);
        List<Listing> live = listings(5, ListingStatus.ACTIVE);
        List<PopularRow> pool = new ArrayList<>();
        pool.add(row(sold));
        live.forEach(listing -> pool.add(row(listing)));
        when(searchRepository.mostViewed(any(), anyInt())).thenReturn(pool);
        List<Listing> all = new ArrayList<>(live);
        all.add(sold);
        repositoryReturns(all);

        List<ListingSummaryResponse> shown = service.popular(3, null);

        assertThat(shown).hasSize(3).extracting(ListingSummaryResponse::id).doesNotContain(sold.getId());
        assertThat(shown).extracting(ListingSummaryResponse::id).containsExactlyElementsOf(live.subList(0, 3).stream().map(Listing::getId).toList());
    }

    @Test
    void theSettingRefusesANegativeTtl() {
        assertThatThrownBy(() -> new SearchProperties(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SearchProperties(Duration.ZERO).popularCacheTtl()).isEqualTo(Duration.ZERO);
    }
}
