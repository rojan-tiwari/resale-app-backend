package com.resalemarket.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.exception.TooManyRequestsException;
import com.resalemarket.common.exception.UnauthorizedException;
import com.resalemarket.common.ratelimit.RateLimit;
import com.resalemarket.common.response.PageResponse;
import com.resalemarket.listing.ListingService;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.listing.entity.ListingCondition;
import com.resalemarket.listing.entity.ListingStatus;
import com.resalemarket.listing.entity.PriceType;
import com.resalemarket.messaging.dto.ConversationResponse;
import com.resalemarket.messaging.dto.ConversationRole;
import com.resalemarket.messaging.entity.Conversation;
import com.resalemarket.support.MutableClock;
import com.resalemarket.user.UserService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    private static final Instant CREATED = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant LAST = Instant.parse("2026-11-21T09:30:00Z");

    @Mock
    private ConversationRepository repository;

    @Mock
    private ListingService listingService;

    @Mock
    private UserService userService;

    private final MutableClock clock = new MutableClock(CREATED);
    private final UUID buyer = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();
    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = serviceWithStartLimit(20);
    }

    private ConversationService serviceWithStartLimit(int attempts) {
        MessagingProperties properties = new MessagingProperties(new MessagingProperties.RateLimits(new RateLimit(attempts, Duration.ofDays(1))));
        return new ConversationService(repository, listingService, userService, new MessagingRateLimits(properties, clock));
    }

    private static Conversation conversation(UUID id, UUID listing, UUID buyerId, UUID sellerId) {
        Conversation conversation = BeanUtils.instantiateClass(Conversation.class);
        ReflectionTestUtils.setField(conversation, "id", id);
        ReflectionTestUtils.setField(conversation, "listingId", listing);
        ReflectionTestUtils.setField(conversation, "buyerId", buyerId);
        ReflectionTestUtils.setField(conversation, "sellerId", sellerId);
        ReflectionTestUtils.setField(conversation, "createdAt", CREATED);
        ReflectionTestUtils.setField(conversation, "lastMessageAt", LAST);
        return conversation;
    }

    private static ListingSummaryResponse card(UUID id, ListingStatus status) {
        return new ListingSummaryResponse(id, "title", BigDecimal.TEN, "NPR", PriceType.FIXED, ListingCondition.GOOD, status,
                null, null, null, null, null, false, CREATED, CREATED);
    }

    // --- start ---------------------------------------------------------------------------------------------------------

    @Test
    void startingCreatesTheConversationAndShowsTheSellerAndTheAd() {
        UUID id = UUID.randomUUID();
        when(listingService.requireLiveSeller(listingId)).thenReturn(seller);
        when(repository.insertIfAbsent(listingId, buyer, seller)).thenReturn(1);
        when(repository.findByListingIdAndBuyerId(listingId, buyer)).thenReturn(Optional.of(conversation(id, listingId, buyer, seller)));
        when(listingService.savedCards(List.of(listingId))).thenReturn(Map.of(listingId, card(listingId, ListingStatus.ACTIVE)));
        when(userService.displayNames(List.of(seller))).thenReturn(Map.of(seller, "Sita Sharma"));

        ConversationService.Started started = service.start(buyer, listingId);

        assertThat(started.created()).isTrue();
        ConversationResponse response = started.conversation();
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.myRole()).isEqualTo(ConversationRole.BUYER);
        assertThat(response.otherMember().id()).isEqualTo(seller);
        assertThat(response.otherMember().displayName()).isEqualTo("Sita Sharma");
        assertThat(response.otherMember().active()).isTrue();
        assertThat(response.listingAvailable()).isTrue();
        assertThat(response.listing().title()).isEqualTo("title");
        assertThat(response.lastMessageAt()).isEqualTo(LAST);
        verify(userService).requireActive(buyer);
    }

    @Test
    void startingAgainReturnsTheExistingConversationAndIsNeverCounted() {
        service = serviceWithStartLimit(1);
        UUID id = UUID.randomUUID();
        when(listingService.requireLiveSeller(listingId)).thenReturn(seller);
        when(repository.insertIfAbsent(listingId, buyer, seller)).thenReturn(0);
        when(repository.findByListingIdAndBuyerId(listingId, buyer)).thenReturn(Optional.of(conversation(id, listingId, buyer, seller)));

        // With a limit of one new conversation a day, opening the same one again and again is still fine.
        for (int i = 0; i < 3; i++) {
            ConversationService.Started started = service.start(buyer, listingId);
            assertThat(started.created()).isFalse();
            assertThat(started.conversation().id()).isEqualTo(id);
        }
    }

    @Test
    void onlyNewConversationsCountAgainstTheLimitOfNewConversations() {
        service = serviceWithStartLimit(1);
        UUID secondListing = UUID.randomUUID();
        when(listingService.requireLiveSeller(any())).thenReturn(seller);
        when(repository.insertIfAbsent(any(), any(), any())).thenReturn(1);
        when(repository.findByListingIdAndBuyerId(any(), any()))
                .thenReturn(Optional.of(conversation(UUID.randomUUID(), listingId, buyer, seller)));

        service.start(buyer, listingId);

        assertThatThrownBy(() -> service.start(buyer, secondListing)).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void theLimitIsPerMemberSoAnotherBuyerIsNotAffected() {
        service = serviceWithStartLimit(1);
        UUID otherBuyer = UUID.randomUUID();
        when(listingService.requireLiveSeller(any())).thenReturn(seller);
        when(repository.insertIfAbsent(any(), any(), any())).thenReturn(1);
        when(repository.findByListingIdAndBuyerId(any(), any()))
                .thenReturn(Optional.of(conversation(UUID.randomUUID(), listingId, buyer, seller)));

        service.start(buyer, listingId);

        assertThat(service.start(otherBuyer, listingId).created()).isTrue();
    }

    @Test
    void youCannotStartAConversationAboutYourOwnAd() {
        when(listingService.requireLiveSeller(listingId)).thenReturn(buyer);

        assertThatThrownBy(() -> service.start(buyer, listingId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("You cannot start a conversation about your own ad.");
        verify(repository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void anAdThatIsNotLiveCannotBeAskedAbout() {
        when(listingService.requireLiveSeller(listingId)).thenThrow(new ResourceNotFoundException("Listing", listingId));

        assertThatThrownBy(() -> service.start(buyer, listingId)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void aSuspendedAccountWithAStillValidTokenCannotStartAnything() {
        doThrow(new UnauthorizedException("Your session has expired. Please sign in again.")).when(userService).requireActive(buyer);

        assertThatThrownBy(() -> service.start(buyer, listingId)).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(listingService, repository);
    }

    // --- the inbox -----------------------------------------------------------------------------------------------------

    @Test
    void theInboxShowsBothRolesAndLooksUpAdsAndPeopleInOneBatchEach() {
        UUID me = buyer;
        UUID soldListing = UUID.randomUUID();
        UUID takenDownListing = UUID.randomUUID();
        UUID myAd = UUID.randomUUID();
        UUID suspendedSeller = UUID.randomUUID();
        UUID asker = UUID.randomUUID();
        Conversation asBuyerOfSold = conversation(UUID.randomUUID(), soldListing, me, seller);
        Conversation asBuyerOfTakenDown = conversation(UUID.randomUUID(), takenDownListing, me, suspendedSeller);
        Conversation asSeller = conversation(UUID.randomUUID(), myAd, asker, me);
        when(repository.findForParticipant(me, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(asBuyerOfSold, asBuyerOfTakenDown, asSeller), PageRequest.of(0, 20), 3));
        when(listingService.savedCards(List.of(soldListing, takenDownListing, myAd)))
                .thenReturn(Map.of(soldListing, card(soldListing, ListingStatus.SOLD), myAd, card(myAd, ListingStatus.RESERVED)));
        when(userService.displayNames(List.of(seller, suspendedSeller, asker))).thenReturn(Map.of(seller, "Sita", asker, "Ram"));

        PageResponse<ConversationResponse> inbox = service.list(me, PageRequest.of(0, 20));

        assertThat(inbox.totalElements()).isEqualTo(3);
        ConversationResponse sold = inbox.content().get(0);
        assertThat(sold.myRole()).isEqualTo(ConversationRole.BUYER);
        assertThat(sold.listing().status()).isEqualTo(ListingStatus.SOLD);
        assertThat(sold.listingAvailable()).as("a sold ad keeps its card but is no longer for sale").isFalse();
        ConversationResponse takenDown = inbox.content().get(1);
        assertThat(takenDown.listing()).as("an ad that was taken down has no card").isNull();
        assertThat(takenDown.listingAvailable()).isFalse();
        assertThat(takenDown.otherMember().displayName()).as("a suspended account has no name").isNull();
        assertThat(takenDown.otherMember().active()).isFalse();
        ConversationResponse mine = inbox.content().get(2);
        assertThat(mine.myRole()).isEqualTo(ConversationRole.SELLER);
        assertThat(mine.otherMember().id()).isEqualTo(asker);
        assertThat(mine.otherMember().displayName()).isEqualTo("Ram");
        assertThat(mine.listingAvailable()).as("a reserved ad is still live").isTrue();
        verify(listingService, times(1)).savedCards(anyCollection());
        verify(userService, times(1)).displayNames(anyCollection());
    }

    @Test
    void anEmptyInboxAsksNobodyForAnything() {
        when(repository.findForParticipant(buyer, PageRequest.of(0, 20))).thenReturn(Page.empty(PageRequest.of(0, 20)));

        assertThat(service.list(buyer, PageRequest.of(0, 20)).content()).isEmpty();
        verifyNoInteractions(listingService, userService);
    }

    @Test
    void theInboxIgnoresAnyOrderingTheCallerAsksFor() {
        when(repository.findForParticipant(any(), any())).thenReturn(Page.empty());

        service.list(buyer, PageRequest.of(2, 5, Sort.by("createdAt")));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findForParticipant(any(), page.capture());
        assertThat(page.getValue()).isEqualTo(PageRequest.of(2, 5));
    }

    // --- one conversation ----------------------------------------------------------------------------------------------

    @Test
    void bothPeopleCanOpenTheConversationEachSeeingTheOtherOne() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(conversation(id, listingId, buyer, seller)));
        when(userService.displayNames(List.of(seller))).thenReturn(Map.of(seller, "Sita"));
        when(userService.displayNames(List.of(buyer))).thenReturn(Map.of(buyer, "Ram"));

        ConversationResponse forBuyer = service.get(buyer, id);
        ConversationResponse forSeller = service.get(seller, id);

        assertThat(forBuyer.myRole()).isEqualTo(ConversationRole.BUYER);
        assertThat(forBuyer.otherMember().displayName()).isEqualTo("Sita");
        assertThat(forSeller.myRole()).isEqualTo(ConversationRole.SELLER);
        assertThat(forSeller.otherMember().displayName()).isEqualTo("Ram");
    }

    @Test
    void forAnybodyElseTheConversationDoesNotExist() {
        UUID id = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(conversation(id, listingId, buyer, seller)));
        when(repository.findById(UUID.fromString("00000000-0000-0000-0000-000000000000"))).thenReturn(Optional.empty());

        // The same answer for "somebody else's" and "no such id", so the response does not reveal that it exists.
        assertThatThrownBy(() -> service.get(outsider, id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(outsider, UUID.fromString("00000000-0000-0000-0000-000000000000")))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(listingService, userService);
    }
}
