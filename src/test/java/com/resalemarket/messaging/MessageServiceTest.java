package com.resalemarket.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.ConflictException;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.exception.UnauthorizedException;
import com.resalemarket.messaging.dto.MessageListResponse;
import com.resalemarket.messaging.dto.MessageResponse;
import com.resalemarket.messaging.entity.Conversation;
import com.resalemarket.messaging.entity.Message;
import com.resalemarket.support.MutableClock;
import com.resalemarket.user.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-21T09:30:00Z");

    @Mock
    private MessageRepository messages;

    @Mock
    private ConversationRepository conversations;

    @Mock
    private ConversationService conversationService;

    @Mock
    private UserService userService;

    private final MutableClock clock = new MutableClock(NOW);
    private final UUID buyer = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final Conversation conversation = conversation();
    private MessageService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(2000, Duration.ofMinutes(1));
    }

    private MessageService serviceWith(int maxLength, Duration duplicateWindow) {
        MessagingProperties properties = new MessagingProperties(maxLength, duplicateWindow, new MessagingProperties.RateLimits(null, null));
        return new MessageService(messages, conversations, conversationService, userService, properties, clock);
    }

    private Conversation conversation() {
        Conversation created = BeanUtils.instantiateClass(Conversation.class);
        ReflectionTestUtils.setField(created, "id", conversationId);
        ReflectionTestUtils.setField(created, "listingId", UUID.randomUUID());
        ReflectionTestUtils.setField(created, "buyerId", buyer);
        ReflectionTestUtils.setField(created, "sellerId", seller);
        return created;
    }

    private Message stored(UUID sender, String body, Instant at) {
        Message message = Message.of(conversationId, sender, body, at);
        ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
        return message;
    }

    /** The conversation can be written in by the buyer, and the seller's account is fine. Storing is possible, but a test may end before it. */
    private void buyerMayWrite() {
        when(conversationService.requireParticipantForUpdate(buyer, conversationId)).thenReturn(conversation);
        when(userService.isActive(seller)).thenReturn(true);
        lenient().when(messages.saveAndFlush(any(Message.class))).thenAnswer(call -> {
            Message message = call.getArgument(0);
            ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
            return message;
        });
    }

    // --- sending -------------------------------------------------------------------------------------------------------

    @Test
    void aMessageIsStoredAndMovesTheConversationUpTheInbox() {
        buyerMayWrite();

        MessageResponse sent = service.send(buyer, conversationId, "Is it still available?");

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getConversationId()).isEqualTo(conversationId);
        assertThat(saved.getValue().getSenderId()).isEqualTo(buyer);
        assertThat(saved.getValue().getBody()).isEqualTo("Is it still available?");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        verify(conversations).touch(conversationId, NOW);
        verify(userService).requireActive(buyer);
        assertThat(sent.mine()).isTrue();
        assertThat(sent.senderId()).isEqualTo(buyer);
        assertThat(sent.readAt()).isNull();
    }

    @Test
    void theTextIsStoredWithoutSurroundingSpaceAndWithPlainLineBreaks() {
        buyerMayWrite();

        MessageResponse sent = service.send(buyer, conversationId, "  Hello,\r\nis it available?\rThanks \n ");

        assertThat(sent.body()).isEqualTo("Hello,\nis it available?\nThanks");
    }

    @Test
    void tabsAndLineBreaksAreFine() {
        buyerMayWrite();

        assertThat(service.send(buyer, conversationId, "a\tb\nc").body()).isEqualTo("a\tb\nc");
    }

    @Test
    void anEmptyMessageIsRefused() {
        when(conversationService.requireParticipantForUpdate(buyer, conversationId)).thenReturn(conversation);

        for (String text : new String[] {null, "", "   ", "\n \t"}) {
            assertThatThrownBy(() -> service.send(buyer, conversationId, text))
                    .isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((FieldValidationException) e).getViolations().get(0).field()).isEqualTo("body"));
        }
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void aMessageOverTheLimitIsRefusedAndOneAtTheLimitIsNot() {
        service = serviceWith(5, Duration.ofMinutes(1));
        buyerMayWrite();

        assertThat(service.send(buyer, conversationId, "12345").body()).isEqualTo("12345");
        assertThatThrownBy(() -> service.send(buyer, conversationId, "123456"))
                .isInstanceOf(FieldValidationException.class)
                .hasMessage("One or more fields are invalid.")
                .satisfies(e -> assertThat(((FieldValidationException) e).getViolations().get(0).message())
                        .isEqualTo("A message can be at most 5 characters long."));
    }

    @Test
    void theLimitCountsCharactersNotUnitsOfTheirEncoding() {
        service = serviceWith(3, Duration.ofMinutes(1));
        buyerMayWrite();
        String threeEmoji = "😀".repeat(3);      // six UTF-16 units, three characters

        assertThat(service.send(buyer, conversationId, threeEmoji).body()).isEqualTo(threeEmoji);
    }

    @Test
    void controlCharactersThatCannotBeStoredAreRefused() {
        when(conversationService.requireParticipantForUpdate(buyer, conversationId)).thenReturn(conversation);

        for (String text : new String[] {"hello\u0000world", "bell\u0007", "escape\u001b[0m"}) {
            assertThatThrownBy(() -> service.send(buyer, conversationId, text)).isInstanceOf(FieldValidationException.class);
        }
        verify(messages, never()).saveAndFlush(any());
    }

    // --- who may send --------------------------------------------------------------------------------------------------

    @Test
    void aSuspendedAccountWithAStillValidTokenCannotSend() {
        doThrow(new UnauthorizedException("Your session has expired. Please sign in again.")).when(userService).requireActive(buyer);

        assertThatThrownBy(() -> service.send(buyer, conversationId, "hello")).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(conversationService, messages, conversations);
    }

    @Test
    void forAnybodyWhoIsNotInTheConversationItDoesNotExist() {
        UUID outsider = UUID.randomUUID();
        when(conversationService.requireParticipantForUpdate(outsider, conversationId))
                .thenThrow(new ResourceNotFoundException("Conversation", conversationId));

        assertThatThrownBy(() -> service.send(outsider, conversationId, "hello")).isInstanceOf(ResourceNotFoundException.class);
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void nothingCanBeSentToAnAccountThatWasSuspendedOrDeleted() {
        when(conversationService.requireParticipantForUpdate(buyer, conversationId)).thenReturn(conversation);
        when(userService.isActive(seller)).thenReturn(false);

        assertThatThrownBy(() -> service.send(buyer, conversationId, "hello"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("This member can no longer receive messages.");
        verify(messages, never()).saveAndFlush(any());
        verify(conversations, never()).touch(any(), any());
    }

    @Test
    void theSellerCanAnswerToo() {
        when(conversationService.requireParticipantForUpdate(seller, conversationId)).thenReturn(conversation);
        when(userService.isActive(buyer)).thenReturn(true);
        when(messages.saveAndFlush(any(Message.class))).thenAnswer(call -> call.getArgument(0));

        MessageResponse sent = service.send(seller, conversationId, "Yes, it is.");

        assertThat(sent.senderId()).isEqualTo(seller);
        assertThat(sent.mine()).isTrue();
    }

    // --- the same text twice in a row ----------------------------------------------------------------------------------

    @Test
    void theSameTextRightAfterTheSendersOwnLastMessageIsRefused() {
        buyerMayWrite();
        when(messages.findLatestBySender(eq(conversationId), eq(buyer), any(Pageable.class)))
                .thenReturn(List.of(stored(buyer, "Is it available?", NOW.minusSeconds(10))));

        assertThatThrownBy(() -> service.send(buyer, conversationId, "  Is it available?  "))
                .isInstanceOf(ConflictException.class)
                .hasMessage("You just sent this message. Wait a little before sending it again.");
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void theSameTextAfterTheWindowHasPassedIsFine() {
        buyerMayWrite();
        when(messages.findLatestBySender(eq(conversationId), eq(buyer), any(Pageable.class)))
                .thenReturn(List.of(stored(buyer, "Is it available?", NOW.minus(Duration.ofMinutes(2)))));

        assertThat(service.send(buyer, conversationId, "Is it available?").body()).isEqualTo("Is it available?");
    }

    @Test
    void anotherTextIsFineEvenRightAfter() {
        buyerMayWrite();
        when(messages.findLatestBySender(eq(conversationId), eq(buyer), any(Pageable.class)))
                .thenReturn(List.of(stored(buyer, "Is it available?", NOW.minusSeconds(1))));

        assertThat(service.send(buyer, conversationId, "What is your lowest price?")).isNotNull();
    }

    @Test
    void theFirstMessageOfAPersonHasNothingToRepeat() {
        buyerMayWrite();
        when(messages.findLatestBySender(eq(conversationId), eq(buyer), any(Pageable.class))).thenReturn(List.of());

        assertThat(service.send(buyer, conversationId, "Hello")).isNotNull();
    }

    @Test
    void theCheckCanBeSwitchedOff() {
        service = serviceWith(2000, Duration.ZERO);
        buyerMayWrite();

        assertThat(service.send(buyer, conversationId, "Hello")).isNotNull();
        verify(messages, never()).findLatestBySender(any(), any(), any());
    }

    // --- reading -------------------------------------------------------------------------------------------------------

    private List<Message> newestFirst(int count) {
        List<Message> found = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            found.add(stored(i % 2 == 0 ? buyer : seller, "message " + (count - i), NOW.minusSeconds(i)));
        }
        return found;
    }

    @Test
    void theNewestMessagesComeOldestFirstAndSayWhetherThereAreOlderOnes() {
        when(conversationService.requireParticipant(buyer, conversationId)).thenReturn(conversation);
        when(messages.findLatest(conversationId, PageRequest.of(0, 4))).thenReturn(newestFirst(4));      // limit 3, plus the one that proves "more"

        MessageListResponse run = service.list(buyer, conversationId, null, null, 3);

        assertThat(run.content()).extracting(MessageResponse::body).containsExactly("message 2", "message 3", "message 4");
        assertThat(run.hasMore()).isTrue();
        assertThat(run.content()).extracting(MessageResponse::mine).containsExactly(true, false, true);
    }

    @Test
    void whenNothingIsLeftHasMoreIsFalse() {
        when(conversationService.requireParticipant(buyer, conversationId)).thenReturn(conversation);
        when(messages.findLatest(conversationId, PageRequest.of(0, 4))).thenReturn(newestFirst(3));

        MessageListResponse run = service.list(buyer, conversationId, null, null, 3);

        assertThat(run.content()).hasSize(3);
        assertThat(run.hasMore()).isFalse();
    }

    @Test
    void anEmptyConversationHasNoMessages() {
        when(conversationService.requireParticipant(buyer, conversationId)).thenReturn(conversation);
        when(messages.findLatest(any(), any())).thenReturn(List.of());

        MessageListResponse run = service.list(buyer, conversationId, null, null, 30);

        assertThat(run.content()).isEmpty();
        assertThat(run.hasMore()).isFalse();
    }

    @Test
    void olderMessagesStartJustBeforeTheCursorAndAreAlsoShownOldestFirst() {
        Message cursor = stored(seller, "cursor", NOW);
        when(conversationService.requireParticipant(buyer, conversationId)).thenReturn(conversation);
        when(messages.findByIdAndConversationId(cursor.getId(), conversationId)).thenReturn(Optional.of(cursor));
        when(messages.findBefore(conversationId, NOW, cursor.getId(), PageRequest.of(0, 3))).thenReturn(newestFirst(2));

        MessageListResponse run = service.list(buyer, conversationId, cursor.getId(), null, 2);

        assertThat(run.content()).extracting(MessageResponse::body).containsExactly("message 1", "message 2");
        assertThat(run.hasMore()).isFalse();
    }

    @Test
    void newerMessagesStartJustAfterTheCursorAndKeepTheirOrder() {
        Message cursor = stored(seller, "cursor", NOW);
        List<Message> newer = List.of(stored(buyer, "first", NOW.plusSeconds(1)), stored(seller, "second", NOW.plusSeconds(2)));
        when(conversationService.requireParticipant(buyer, conversationId)).thenReturn(conversation);
        when(messages.findByIdAndConversationId(cursor.getId(), conversationId)).thenReturn(Optional.of(cursor));
        when(messages.findAfter(conversationId, NOW, cursor.getId(), PageRequest.of(0, 2))).thenReturn(newer);

        MessageListResponse run = service.list(buyer, conversationId, null, cursor.getId(), 1);

        assertThat(run.content()).extracting(MessageResponse::body).containsExactly("first");
        assertThat(run.hasMore()).as("a second newer message exists").isTrue();
    }

    @Test
    void aCursorThatIsNotAMessageOfThisConversationIsNotFound() {
        UUID foreign = UUID.randomUUID();
        when(conversationService.requireParticipant(buyer, conversationId)).thenReturn(conversation);
        when(messages.findByIdAndConversationId(foreign, conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(buyer, conversationId, foreign, null, 30)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.list(buyer, conversationId, null, foreign, 30)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void askingForOlderAndNewerAtOnceIsRefused() {
        when(conversationService.requireParticipant(buyer, conversationId)).thenReturn(conversation);

        assertThatThrownBy(() -> service.list(buyer, conversationId, UUID.randomUUID(), UUID.randomUUID(), 30))
                .isInstanceOf(FieldValidationException.class);
        verifyNoInteractions(messages);
    }

    @Test
    void forAnybodyWhoIsNotInTheConversationThereAreNoMessages() {
        UUID outsider = UUID.randomUUID();
        when(conversationService.requireParticipant(outsider, conversationId)).thenThrow(new ResourceNotFoundException("Conversation", conversationId));

        assertThatThrownBy(() -> service.list(outsider, conversationId, null, null, 30)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(messages);
    }

    @Test
    void theSizeIsKeptBetweenOneAndOneHundred() {
        when(conversationService.requireParticipant(buyer, conversationId)).thenReturn(conversation);
        when(messages.findLatest(any(), any())).thenReturn(List.of());

        service.list(buyer, conversationId, null, null, 0);
        service.list(buyer, conversationId, null, null, -5);
        service.list(buyer, conversationId, null, null, 100_000);

        ArgumentCaptor<Pageable> limit = ArgumentCaptor.forClass(Pageable.class);
        verify(messages, org.mockito.Mockito.times(3)).findLatest(eq(conversationId), limit.capture());
        assertThat(limit.getAllValues()).extracting(Pageable::getPageSize).containsExactly(2, 2, MessageService.MAX_SIZE + 1);
    }
}
