package com.resalemarket.messaging;

import static com.resalemarket.listing.ListingRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.resalemarket.listing.ListingIntegrationSupportAccess;
import com.resalemarket.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Messages end to end against PostgreSQL: sending, reading in runs, who may do either, and the protections against abuse. */
@IntegrationTest
class MessageApiIntegrationTest extends ListingIntegrationSupportAccess {

    private static final String CONVERSATIONS = "/api/v1/conversations";

    /** A buyer and a seller with a conversation about a live ad. */
    private record Chat(Member buyer, Member seller, String id) {
    }

    private Chat chat(String name) throws Exception {
        Member seller = member(name + "seller");
        Member buyer = member(name + "buyer");
        UUID listing = rowsFor(seller).insert(row().title("Phone for sale"));
        String body = postJson(CONVERSATIONS, buyer, Map.of("listingId", listing.toString())).andReturn().getResponse().getContentAsString();
        return new Chat(buyer, seller, JsonPath.read(body, "$.id"));
    }

    private ResultActions send(Member who, String conversationId, String text) throws Exception {
        return postJson(CONVERSATIONS + "/" + conversationId + "/messages", who, Map.of("body", text));
    }

    private String sent(Member who, String conversationId, String text) throws Exception {
        String body = send(who, conversationId, text).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions read(Member who, String conversationId, String query) throws Exception {
        return call(get(CONVERSATIONS + "/" + conversationId + "/messages" + query), who);
    }

    private int messageCount() {
        return jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class);
    }

    // --- who may use it ------------------------------------------------------------------------------------------------

    @Test
    void everythingNeedsASignIn() throws Exception {
        Chat chat = chat("msgauth");

        read(null, chat.id(), "").andExpect(status().isUnauthorized());
        send(null, chat.id(), "hello").andExpect(status().isUnauthorized());
    }

    @Test
    void aSuspendedAccountWithAStillValidTokenCannotSend() throws Exception {
        Chat chat = chat("msgsusp");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", chat.buyer().id());

        send(chat.buyer(), chat.id(), "hello").andExpect(status().isUnauthorized());
        assertThat(messageCount()).isZero();
    }

    @Test
    void anybodyWhoIsNotInTheConversationCanNeitherReadNorWriteInIt() throws Exception {
        Chat chat = chat("msgoutsider");
        Member outsider = member("msgoutsider");
        sent(chat.buyer(), chat.id(), "Private question");

        read(outsider, chat.id(), "").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        send(outsider, chat.id(), "Let me in").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        read(outsider, UUID.randomUUID().toString(), "").andExpect(status().isNotFound());
        send(outsider, UUID.randomUUID().toString(), "hello").andExpect(status().isNotFound());
        assertThat(messageCount()).isEqualTo(1);
    }

    // --- sending -------------------------------------------------------------------------------------------------------

    @Test
    void aMessageIsDeliveredToTheOtherPersonWhoSeesItAsTheirs() throws Exception {
        Chat chat = chat("msgsend");

        send(chat.buyer(), chat.id(), "Is it still available?")
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.conversationId").value(chat.id()))
                .andExpect(jsonPath("$.senderId").value(chat.buyer().id()))
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.body").value("Is it still available?"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.readAt").value(nullValue()));

        read(chat.seller(), chat.id(), "")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].body").value("Is it still available?"))
                .andExpect(jsonPath("$.content[0].mine").value(false))
                .andExpect(jsonPath("$.content[0].senderId").value(chat.buyer().id()))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void theSellerCanAnswerAndTheConversationMovesUpBothInboxes() throws Exception {
        Chat chat = chat("msganswer");
        sent(chat.buyer(), chat.id(), "Is it available?");
        sent(chat.seller(), chat.id(), "Yes, come and see it.");

        read(chat.buyer(), chat.id(), "")
                .andExpect(jsonPath("$.content[*].body", contains("Is it available?", "Yes, come and see it.")))
                .andExpect(jsonPath("$.content[*].mine", contains(true, false)));
        call(get(CONVERSATIONS), chat.buyer())
                .andExpect(jsonPath("$.content[0].lastMessage.text").value("Yes, come and see it."))
                .andExpect(jsonPath("$.content[0].lastMessage.mine").value(false));
        call(get(CONVERSATIONS), chat.seller())
                .andExpect(jsonPath("$.content[0].lastMessage.text").value("Yes, come and see it."))
                .andExpect(jsonPath("$.content[0].lastMessage.mine").value(true));
        // the time that orders the inbox is exactly the time of the newest message
        assertThat(jdbc.queryForObject(
                "SELECT c.last_message_at = (SELECT max(m.created_at) FROM messages m WHERE m.conversation_id = c.id) FROM conversations c WHERE c.id = ?::uuid",
                Boolean.class, chat.id())).isTrue();
    }

    @Test
    void theTextIsKeptAsWrittenExceptForSurroundingSpaceAndLineEndings() throws Exception {
        Chat chat = chat("msgtext");

        send(chat.buyer(), chat.id(), "  Hello,\r\nis it available?  \n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Hello,\nis it available?"));
        send(chat.seller(), chat.id(), "नमस्ते 🙂 — रु 85,000 <b>ok</b>")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("नमस्ते 🙂 — रु 85,000 <b>ok</b>"));
    }

    @Test
    void aMessageOfExactlyTheLimitIsAcceptedAndOneCharacterMoreIsNot() throws Exception {
        Chat chat = chat("msglimit");

        send(chat.buyer(), chat.id(), "a".repeat(2000)).andExpect(status().isCreated());
        send(chat.seller(), chat.id(), "b".repeat(2001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("body"))
                .andExpect(jsonPath("$.errors[0].message").value("A message can be at most 2000 characters long."));
        assertThat(messageCount()).isEqualTo(1);
    }

    @Test
    void anEmptyMessageOrOneWithCharactersThatCannotBeStoredIsRefused() throws Exception {
        Chat chat = chat("msgbad");

        for (String text : new String[] {"", "   ", "\n\t\n"}) {
            send(chat.buyer(), chat.id(), text)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("body"));
        }
        postJson(CONVERSATIONS + "/" + chat.id() + "/messages", chat.buyer(), Map.of()).andExpect(status().isBadRequest());
        // a NUL cannot be stored in PostgreSQL text: it must be a clear 400, never a 500
        send(chat.buyer(), chat.id(), "hello\u0000world")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        assertThat(messageCount()).isZero();
    }

    @Test
    void nothingCanBeSentToAnAccountThatWasSuspended() throws Exception {
        Chat chat = chat("msgrecipient");
        sent(chat.buyer(), chat.id(), "Hello");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", chat.seller().id());

        send(chat.buyer(), chat.id(), "Are you there?")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.detail").value("This member can no longer receive messages."));
        read(chat.buyer(), chat.id(), "").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void aConversationAboutASoldAdCanContinue() throws Exception {
        Chat chat = chat("msgsold");
        sent(chat.buyer(), chat.id(), "Is it available?");
        jdbc.update("UPDATE listings SET status = 'SOLD', sold_at = now() WHERE seller_id = ?::uuid", chat.seller().id());

        send(chat.seller(), chat.id(), "Sorry, sold an hour ago.").andExpect(status().isCreated());
    }

    // --- the same text twice in a row ----------------------------------------------------------------------------------

    @Test
    void theSameTextTwiceInARowFromTheSamePersonIsRefusedButAnotherPersonMaySayIt() throws Exception {
        Chat chat = chat("msgdup");

        sent(chat.buyer(), chat.id(), "Is it available?");
        send(chat.buyer(), chat.id(), "Is it available?")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        sent(chat.seller(), chat.id(), "Is it available?");           // somebody else saying the same words is not a repeat
        sent(chat.buyer(), chat.id(), "What is your lowest price?");
        sent(chat.buyer(), chat.id(), "Is it available?");            // not the very next message of the same person

        assertThat(messageCount()).isEqualTo(4);
    }

    @Test
    void simultaneousIdenticalMessagesAreStoredOnce() throws Exception {
        Chat chat = chat("msgrace");

        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> send(chat.buyer(), chat.id(), "Is it available?").andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).as("the conversation is locked while one is stored").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(messageCount()).isEqualTo(1);
    }

    // --- reading in runs -----------------------------------------------------------------------------------------------

    /** Five messages, alternating between the two people: "m1" (oldest) to "m5" (newest). Returns their ids in that order. */
    private List<String> fiveMessages(Chat chat) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(sent(i % 2 == 1 ? chat.buyer() : chat.seller(), chat.id(), "m" + i));
        }
        return ids;
    }

    @Test
    void theNewestMessagesComeFirstAsARunAndOlderOnesAreLoadedBeforeIt() throws Exception {
        Chat chat = chat("msgpage");
        List<String> ids = fiveMessages(chat);

        read(chat.buyer(), chat.id(), "?size=2")
                .andExpect(jsonPath("$.content[*].body", contains("m4", "m5")))
                .andExpect(jsonPath("$.hasMore").value(true));
        read(chat.buyer(), chat.id(), "?size=2&before=" + ids.get(3))
                .andExpect(jsonPath("$.content[*].body", contains("m2", "m3")))
                .andExpect(jsonPath("$.hasMore").value(true));
        read(chat.buyer(), chat.id(), "?size=2&before=" + ids.get(1))
                .andExpect(jsonPath("$.content[*].body", contains("m1")))
                .andExpect(jsonPath("$.hasMore").value(false));
        read(chat.buyer(), chat.id(), "?before=" + ids.get(0))
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.hasMore").value(false));
        read(chat.buyer(), chat.id(), "").andExpect(jsonPath("$.content", hasSize(5))).andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void anOpenChatAsksForWhatCameAfterTheLastMessageItHas() throws Exception {
        Chat chat = chat("msgpoll");
        List<String> ids = fiveMessages(chat);

        read(chat.seller(), chat.id(), "?after=" + ids.get(1))
                .andExpect(jsonPath("$.content[*].body", contains("m3", "m4", "m5")))
                .andExpect(jsonPath("$.hasMore").value(false));
        read(chat.seller(), chat.id(), "?size=2&after=" + ids.get(1))
                .andExpect(jsonPath("$.content[*].body", contains("m3", "m4")))
                .andExpect(jsonPath("$.hasMore").value(true));
        read(chat.seller(), chat.id(), "?after=" + ids.get(4))
                .andExpect(jsonPath("$.content", empty()))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void aCursorMustBeAMessageOfThisConversation() throws Exception {
        Chat chat = chat("msgcursor");
        Chat other = chat("msgcursor2");
        String foreign = sent(other.buyer(), other.id(), "Somebody else's message");
        sent(chat.buyer(), chat.id(), "Mine");

        read(chat.buyer(), chat.id(), "?before=" + foreign).andExpect(status().isNotFound());
        read(chat.buyer(), chat.id(), "?after=" + foreign).andExpect(status().isNotFound());
        read(chat.buyer(), chat.id(), "?before=" + UUID.randomUUID()).andExpect(status().isNotFound());
        read(chat.buyer(), chat.id(), "?before=not-an-id").andExpect(status().isBadRequest());
    }

    @Test
    void olderAndNewerCannotBeAskedForTogether() throws Exception {
        Chat chat = chat("msgboth");
        String id = sent(chat.buyer(), chat.id(), "Hello");

        read(chat.buyer(), chat.id(), "?before=" + id + "&after=" + id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void theSizeIsKeptWithinBoundsInsteadOfFailing() throws Exception {
        Chat chat = chat("msgsize");
        fiveMessages(chat);

        read(chat.buyer(), chat.id(), "?size=0").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)));
        read(chat.buyer(), chat.id(), "?size=100000").andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(5)));
        read(chat.buyer(), chat.id(), "?size=abc").andExpect(status().isBadRequest());
    }

    @Test
    void readingDoesNotChangeAnything() throws Exception {
        Chat chat = chat("msgreadonly");
        sent(chat.buyer(), chat.id(), "Hello");

        read(chat.seller(), chat.id(), "").andExpect(jsonPath("$.content[0].readAt").value(nullValue()));
        read(chat.seller(), chat.id(), "").andExpect(jsonPath("$.content[0].readAt").value(nullValue()));
    }
}
