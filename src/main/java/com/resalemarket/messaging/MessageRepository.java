package com.resalemarket.messaging;

import com.resalemarket.messaging.entity.Message;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every read is served by {@code idx_messages_conversation_created (conversation_id, created_at DESC, id DESC)}. Messages are ordered by
 * time and, for the rare tie, by id, so the order is total and a page boundary never splits or repeats a message.
 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByIdAndConversationId(UUID id, UUID conversationId);

    /** The newest messages, newest first. */
    @Query("select m from Message m where m.conversationId = :conversationId order by m.createdAt desc, m.id desc")
    List<Message> findLatest(@Param("conversationId") UUID conversationId, Pageable limit);

    /** The messages just before the one at ({@code at}, {@code id}), newest first. */
    @Query("""
            select m from Message m
            where m.conversationId = :conversationId and (m.createdAt < :at or (m.createdAt = :at and m.id < :id))
            order by m.createdAt desc, m.id desc""")
    List<Message> findBefore(
            @Param("conversationId") UUID conversationId, @Param("at") Instant at, @Param("id") UUID id, Pageable limit);

    /** The messages just after the one at ({@code at}, {@code id}), oldest first. */
    @Query("""
            select m from Message m
            where m.conversationId = :conversationId and (m.createdAt > :at or (m.createdAt = :at and m.id > :id))
            order by m.createdAt asc, m.id asc""")
    List<Message> findAfter(
            @Param("conversationId") UUID conversationId, @Param("at") Instant at, @Param("id") UUID id, Pageable limit);

    /** What one person sent last in a conversation (newest first, so ask for one): the basis for refusing the same text twice in a row. */
    @Query("select m from Message m where m.conversationId = :conversationId and m.senderId = :senderId order by m.createdAt desc, m.id desc")
    List<Message> findLatestBySender(@Param("conversationId") UUID conversationId, @Param("senderId") UUID senderId, Pageable limit);

    /**
     * The newest message of each of the conversations, in one query for a whole page of the inbox. {@code DISTINCT ON} walks the index
     * of each conversation to its first entry, so the cost depends on the number of conversations, not on how long they are.
     */
    @Query(value = """
            SELECT DISTINCT ON (m.conversation_id) m.*
            FROM messages m
            WHERE m.conversation_id IN (:conversationIds)
            ORDER BY m.conversation_id, m.created_at DESC, m.id DESC
            """, nativeQuery = true)
    List<Message> findNewestOfEach(@Param("conversationIds") Collection<UUID> conversationIds);
}
