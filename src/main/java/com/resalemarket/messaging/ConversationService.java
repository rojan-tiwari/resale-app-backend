package com.resalemarket.messaging;

import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.exception.TooManyRequestsException;
import com.resalemarket.common.response.PageResponse;
import com.resalemarket.listing.ListingService;
import com.resalemarket.listing.dto.ListingSummaryResponse;
import com.resalemarket.messaging.dto.ConversationResponse;
import com.resalemarket.messaging.dto.ConversationRole;
import com.resalemarket.messaging.dto.MessagePreview;
import com.resalemarket.messaging.dto.ParticipantResponse;
import com.resalemarket.messaging.entity.Conversation;
import com.resalemarket.messaging.entity.Message;
import com.resalemarket.user.UserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member's conversations about listings. Everything is about the member in the access token, and a conversation can be seen only by
 * its two people: for anybody else it does not exist (404, never 403, so its existence is not revealed). Listings and people are
 * looked at only through {@link ListingService} and {@link UserService}.
 */
@Service
@Transactional(readOnly = true)
public class ConversationService {

    /** The outcome of asking to start a conversation: the conversation, and whether this call created it (HTTP 201 against 200). */
    public record Started(ConversationResponse conversation, boolean created) {
    }

    /** How many characters of the last message a row of the inbox shows. */
    static final int PREVIEW_LENGTH = 140;

    private final ConversationRepository repository;
    private final MessageRepository messageRepository;
    private final ListingService listingService;
    private final UserService userService;
    private final MessagingRateLimits rateLimits;

    public ConversationService(
            ConversationRepository repository,
            MessageRepository messageRepository,
            ListingService listingService,
            UserService userService,
            MessagingRateLimits rateLimits) {
        this.repository = repository;
        this.messageRepository = messageRepository;
        this.listingService = listingService;
        this.userService = userService;
        this.rateLimits = rateLimits;
    }

    /**
     * Opens the buyer's conversation with the seller of a listing, or returns the one that already exists: asking twice (a double
     * click, or two tabs) gives one conversation.
     *
     * @throws ResourceNotFoundException if the listing does not exist or is not live (the same answer for both)
     * @throws BusinessRuleException     if the listing is the member's own
     * @throws TooManyRequestsException  if the member started too many new conversations recently; the conversation is then not
     *                                   created (this transaction is rolled back) and opening an existing one is never refused
     */
    @Transactional
    public Started start(UUID buyerId, UUID listingId) {
        userService.requireActive(buyerId);
        UUID sellerId = listingService.requireLiveSeller(listingId);
        if (sellerId.equals(buyerId)) {
            throw new BusinessRuleException("You cannot start a conversation about your own ad.");
        }
        boolean created = repository.insertIfAbsent(listingId, buyerId, sellerId) == 1;
        if (created) {
            rateLimits.start(buyerId);
        }
        Conversation conversation = repository.findByListingIdAndBuyerId(listingId, buyerId)
                .orElseThrow(() -> new IllegalStateException("The conversation just created for listing " + listingId + " is missing."));
        return new Started(toResponses(buyerId, List.of(conversation)).get(0), created);
    }

    /** The member's conversations, the most recently active first. Ads that are no longer for sale are flagged, not left out. */
    public PageResponse<ConversationResponse> list(UUID userId, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<Conversation> found = repository.findForParticipant(userId, page);
        return PageResponse.from(new PageImpl<>(toResponses(userId, found.getContent()), page, found.getTotalElements()));
    }

    /** @throws ResourceNotFoundException if there is no such conversation, or the member is not one of its two people */
    public ConversationResponse get(UUID userId, UUID id) {
        return toResponses(userId, List.of(requireParticipant(userId, id))).get(0);
    }

    /** The conversation, for a member who is one of its two people; anybody else is told it does not exist. */
    Conversation requireParticipant(UUID userId, UUID id) {
        return repository.findById(id)
                .filter(conversation -> conversation.isParticipant(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
    }

    /**
     * Like {@link #requireParticipant}, and the conversation stays locked until the transaction ends, so what the caller does next
     * (sending a message) is not interleaved with the same thing done by the other person or by a second tab.
     */
    Conversation requireParticipantForUpdate(UUID userId, UUID id) {
        return repository.findWithLockById(id)
                .filter(conversation -> conversation.isParticipant(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", id));
    }

    /** One batch of lookups for the listings, one for the people, one for the last messages and one for the unread counts, however many conversations (no query per row). */
    private List<ConversationResponse> toResponses(UUID me, List<Conversation> conversations) {
        if (conversations.isEmpty()) {
            return List.of();
        }
        Map<UUID, ListingSummaryResponse> cards =
                listingService.savedCards(conversations.stream().map(Conversation::getListingId).distinct().toList());
        Map<UUID, String> names = userService.displayNames(conversations.stream().map(c -> c.otherParty(me)).distinct().toList());
        List<UUID> ids = conversations.stream().map(Conversation::getId).toList();
        Map<UUID, Message> newest = messageRepository.findNewestOfEach(ids).stream()
                .collect(Collectors.toMap(Message::getConversationId, Function.identity()));
        Map<UUID, Long> unread = messageRepository.countUnreadIn(ids, me).stream()
                .collect(Collectors.toMap(MessageRepository.UnreadInConversation::getConversationId, MessageRepository.UnreadInConversation::getUnread));
        return conversations.stream()
                .map(conversation -> {
                    ListingSummaryResponse card = cards.get(conversation.getListingId());
                    UUID otherId = conversation.otherParty(me);
                    String name = names.get(otherId);
                    Message last = newest.get(conversation.getId());
                    return new ConversationResponse(
                            conversation.getId(),
                            conversation.getListingId(),
                            card != null && card.status().isPublic(),
                            card,
                            conversation.getBuyerId().equals(me) ? ConversationRole.BUYER : ConversationRole.SELLER,
                            new ParticipantResponse(otherId, name, name != null),
                            last == null ? null : new MessagePreview(preview(last.getBody()), last.getSenderId().equals(me), last.getCreatedAt()),
                            unread.getOrDefault(conversation.getId(), 0L),
                            conversation.getLastMessageAt(),
                            conversation.getCreatedAt());
                })
                .toList();
    }

    /** A message shortened for one row of the inbox: line breaks and runs of spaces become one space, and a long one ends in an ellipsis. */
    static String preview(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        if (flat.codePointCount(0, flat.length()) <= PREVIEW_LENGTH) {
            return flat;
        }
        return flat.substring(0, flat.offsetByCodePoints(0, PREVIEW_LENGTH - 1)).stripTrailing() + "…";
    }
}
