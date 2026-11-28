package com.resalemarket.messaging;

import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.ConflictException;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.messaging.dto.MessageListResponse;
import com.resalemarket.messaging.dto.MessageResponse;
import com.resalemarket.messaging.entity.Conversation;
import com.resalemarket.messaging.entity.Message;
import com.resalemarket.user.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The messages of a conversation. Only the conversation's two people can read or send anything in it: for anybody else it does not
 * exist (404). Sending is guarded against abuse by a length limit, a limit on how often a member may send (in the controller, so
 * a refused call costs nothing), and a refusal to send the very same text twice in a row.
 */
@Service
@Transactional(readOnly = true)
public class MessageService {

    /** How many messages a call returns when the caller does not say; also the most it returns. */
    static final int DEFAULT_SIZE = 30;
    static final int MAX_SIZE = 100;

    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final ConversationService conversationService;
    private final UserService userService;
    private final MessagingProperties properties;
    private final Clock clock;

    public MessageService(
            MessageRepository messages,
            ConversationRepository conversations,
            ConversationService conversationService,
            UserService userService,
            MessagingProperties properties,
            Clock clock) {
        this.messages = messages;
        this.conversations = conversations;
        this.conversationService = conversationService;
        this.userService = userService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Sends a message to the other person of the conversation.
     *
     * @throws ResourceNotFoundException if there is no such conversation, or the sender is not one of its two people
     * @throws FieldValidationException  if the text is empty, too long or has characters that cannot be stored
     * @throws BusinessRuleException     if the other person's account was suspended or deleted
     * @throws ConflictException         if it is the very same text the sender sent as their last message a moment ago
     */
    @Transactional
    public MessageResponse send(UUID senderId, UUID conversationId, String text) {
        userService.requireActive(senderId);
        // Locked until the end of this transaction: sends in one conversation run one after the other, so the check for a repeat
        // below sees the message a simultaneous request has just stored.
        Conversation conversation = conversationService.requireParticipantForUpdate(senderId, conversationId);
        String body = clean(text);
        if (!userService.isActive(conversation.otherParty(senderId))) {
            throw new BusinessRuleException("This member can no longer receive messages.");
        }
        Instant now = clock.instant();
        rejectRepeat(conversationId, senderId, body, now);
        Message saved = messages.saveAndFlush(Message.of(conversationId, senderId, body, now));
        conversations.touch(conversationId, now);
        return toResponse(saved, senderId);
    }

    /**
     * A run of messages, oldest first. With neither cursor: the newest ones. With {@code before}: those just before that message. With
     * {@code after}: those just after it, which is how the open chat asks for what arrived since its last answer.
     *
     * @param size how many, at most; clamped to 1..{@value #MAX_SIZE}
     * @throws ResourceNotFoundException if there is no such conversation or the member is not one of its people, or a cursor is not a
     *                                   message of this conversation
     * @throws FieldValidationException  if both cursors are given
     */
    public MessageListResponse list(UUID userId, UUID conversationId, UUID before, UUID after, int size) {
        conversationService.requireParticipant(userId, conversationId);
        if (before != null && after != null) {
            throw new FieldValidationException("before", "Ask for older messages (before) or for newer ones (after), not both.");
        }
        int limit = Math.max(1, Math.min(size, MAX_SIZE));
        Pageable oneMore = PageRequest.of(0, limit + 1);       // the extra row tells whether there is more, without a count query
        List<Message> found;
        if (after != null) {
            Message cursor = requireMessage(conversationId, after);
            found = messages.findAfter(conversationId, cursor.getCreatedAt(), cursor.getId(), oneMore);
        } else if (before != null) {
            Message cursor = requireMessage(conversationId, before);
            found = messages.findBefore(conversationId, cursor.getCreatedAt(), cursor.getId(), oneMore);
        } else {
            found = messages.findLatest(conversationId, oneMore);
        }
        boolean hasMore = found.size() > limit;
        List<Message> run = new ArrayList<>(found.subList(0, Math.min(found.size(), limit)));
        if (after == null) {
            Collections.reverse(run);                          // fetched newest first, shown oldest first
        }
        return new MessageListResponse(run.stream().map(message -> toResponse(message, userId)).toList(), hasMore);
    }

    private Message requireMessage(UUID conversationId, UUID messageId) {
        return messages.findByIdAndConversationId(messageId, conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", messageId));
    }

    /** The text as it is stored: surrounding space removed, line breaks as {@code \n}; refused when empty, too long or not storable. */
    private String clean(String text) {
        String body = text == null ? "" : text.strip().replace("\r\n", "\n").replace('\r', '\n');
        if (body.isEmpty()) {
            throw new FieldValidationException("body", "Write a message first.");
        }
        if (body.codePointCount(0, body.length()) > properties.maxMessageLength()) {
            throw new FieldValidationException("body", "A message can be at most %d characters long.".formatted(properties.maxMessageLength()));
        }
        // Control characters (a NUL, for one, cannot be stored in PostgreSQL text) are never typed by a person; line breaks and tabs are.
        if (body.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new FieldValidationException("body", "Your message has characters that cannot be sent.");
        }
        return body;
    }

    /** Refuses the same text twice in a row from the same person within the window: a double click, a retry, or a copy-paste flood. */
    private void rejectRepeat(UUID conversationId, UUID senderId, String body, Instant now) {
        if (properties.duplicateWindow().isZero()) {
            return;
        }
        messages.findLatestBySender(conversationId, senderId, PageRequest.of(0, 1)).stream()
                .findFirst()
                .filter(last -> last.getBody().equals(body) && last.getCreatedAt().isAfter(now.minus(properties.duplicateWindow())))
                .ifPresent(last -> {
                    throw new ConflictException("You just sent this message. Wait a little before sending it again.");
                });
    }

    private static MessageResponse toResponse(Message message, UUID me) {
        return new MessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getSenderId(),
                message.getSenderId().equals(me),
                message.getBody(),
                message.getCreatedAt(),
                message.getReadAt());
    }
}
