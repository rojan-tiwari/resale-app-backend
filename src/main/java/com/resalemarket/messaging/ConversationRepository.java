package com.resalemarket.messaging;

import com.resalemarket.messaging.entity.Conversation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByListingIdAndBuyerId(UUID listingId, UUID buyerId);

    /**
     * A person's conversations as buyer or as seller, the most recently active first. Each half of the {@code OR} is served by its
     * own index ({@code idx_conversations_buyer}, {@code idx_conversations_seller}), so the inbox never scans other people's rows.
     */
    @Query("select c from Conversation c where c.buyerId = :userId or c.sellerId = :userId order by c.lastMessageAt desc, c.id")
    Page<Conversation> findForParticipant(@Param("userId") UUID userId, Pageable pageable);

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
