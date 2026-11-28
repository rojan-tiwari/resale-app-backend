package com.resalemarket.messaging;

import com.resalemarket.messaging.entity.Conversation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByListingIdAndBuyerId(UUID listingId, UUID buyerId);

    /**
     * The conversation, locked for the rest of the transaction, so that the messages sent in it are handled one after the other: two
     * identical messages sent at the same moment cannot both pass the check for a repeat.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Conversation> findWithLockById(UUID id);

    /**
     * A person's conversations as buyer or as seller that have at least one message, the most recently active first. A conversation
     * nobody has written in yet (a buyer who only opened the chat) is not shown to either person. Each half of the {@code OR} is
     * served by its own index ({@code idx_conversations_buyer}, {@code idx_conversations_seller}), so the inbox never scans other
     * people's rows, and the {@code EXISTS} is one probe of {@code idx_messages_conversation_created}.
     */
    @Query("""
            select c from Conversation c
            where (c.buyerId = :userId or c.sellerId = :userId)
              and exists (select m.id from Message m where m.conversationId = c.id)
            order by c.lastMessageAt desc, c.id""")
    Page<Conversation> findForParticipant(@Param("userId") UUID userId, Pageable pageable);

    /** Moves the time of the last message, which orders the inbox. A plain {@code UPDATE}: the entity never writes this column. */
    @Modifying
    @Query(value = "UPDATE conversations SET last_message_at = :at WHERE id = :id", nativeQuery = true)
    int touch(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Starts a conversation unless the buyer already has one about the listing. Deciding in the database (instead of "look, then
     * insert") means two simultaneous requests end with exactly one row and neither of them fails.
     *
     * @return 1 when a conversation was created, 0 when there already was one
     */
    @Modifying
    @Query(value = """
            INSERT INTO conversations (listing_id, buyer_id, seller_id)
            VALUES (:listingId, :buyerId, :sellerId)
            ON CONFLICT ON CONSTRAINT uq_conversations_listing_buyer DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("listingId") UUID listingId, @Param("buyerId") UUID buyerId, @Param("sellerId") UUID sellerId);
}
