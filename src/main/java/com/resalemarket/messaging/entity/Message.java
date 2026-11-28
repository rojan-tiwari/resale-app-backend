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

/**
 * One message of a conversation. A message is never edited or deleted by its people, so only {@code read_at} ever changes, and that
 * only by a single {@code UPDATE} when the other person opens the conversation (see {@code MessageRepository}); the entity reads it
 * but never writes it. The conversation and the sender are plain ids of this module and of the user module.
 */
@Entity
@Table(name = "messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    @Column(name = "body", nullable = false, updatable = false, length = 2000)
    private String body;

    /** Set by the application from its {@code Clock}, so the message and the conversation's {@code last_message_at} agree to the microsecond. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the other person read it; {@code null} while they have not. */
    @Column(name = "read_at", insertable = false, updatable = false)
    private Instant readAt;

    public static Message of(UUID conversationId, UUID senderId, String body, Instant createdAt) {
        Message message = new Message();
        message.conversationId = conversationId;
        message.senderId = senderId;
        message.body = body;
        message.createdAt = createdAt;
        return message;
    }
}
