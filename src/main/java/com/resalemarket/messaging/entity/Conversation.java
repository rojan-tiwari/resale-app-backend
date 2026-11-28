package com.resalemarket.messaging.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A buyer's conversation with the seller of one listing. There is one per listing and buyer ({@code uq_conversations_listing_buyer}),
 * and the two people are always different ({@code chk_conversations_distinct_parties}); the seller is the listing's seller, copied
 * here so that the inbox of either person is one indexed lookup. Listing, buyer and seller are plain ids of other modules.
 *
 * <p>Rows are created by {@code ConversationRepository.insertIfAbsent} (an {@code INSERT ... ON CONFLICT DO NOTHING}, so two
 * simultaneous "start" requests cannot fail each other) and are never changed through this entity: the time of the last message is
 * moved by a single {@code UPDATE} when a message is sent.
 */
@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "listing_id", nullable = false, updatable = false, insertable = false)
    private UUID listingId;

    @Column(name = "buyer_id", nullable = false, updatable = false, insertable = false)
    private UUID buyerId;

    @Column(name = "seller_id", nullable = false, updatable = false, insertable = false)
    private UUID sellerId;

    @Generated(event = EventType.INSERT)
    @Column(name = "last_message_at", nullable = false, insertable = false, updatable = false)
    private Instant lastMessageAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    public boolean isParticipant(UUID userId) {
        return buyerId.equals(userId) || sellerId.equals(userId);
    }

    /** The person at the other end of the conversation, seen from {@code userId} (who must be one of the two). */
    public UUID otherParty(UUID userId) {
        return buyerId.equals(userId) ? sellerId : buyerId;
    }
}
