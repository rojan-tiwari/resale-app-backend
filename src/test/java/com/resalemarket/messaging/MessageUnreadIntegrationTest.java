package com.resalemarket.messaging;

import static com.resalemarket.listing.ListingRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.resalemarket.listing.ListingIntegrationSupportAccess;
import com.resalemarket.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** What has not been read, end to end against PostgreSQL: the numbers, marking as read, and who may do it. */
@IntegrationTest
class MessageUnreadIntegrationTest extends ListingIntegrationSupportAccess {

    private static final String CONVERSATIONS = "/api/v1/conversations";

    private record Chat(Member buyer, Member seller, String id) {
    }

    private Chat chat(String name) throws Exception {
        Member seller = member(name + "seller");
        Member buyer = member(name + "buyer");
        return chatBetween(buyer, seller, "Phone for sale");
    }

    private Chat chatBetween(Member buyer, Member seller, String title) throws Exception {
        UUID listing = rowsFor(seller).insert(row().title(title));
        String body = postJson(CONVERSATIONS, buyer, Map.of("listingId", listing.toString())).andReturn().getResponse().getContentAsString();
        return new Chat(buyer, seller, JsonPath.read(body, "$.id"));
    }

    private void say(Member who, String conversationId, String text) throws Exception {
        postJson(CONVERSATIONS + "/" + conversationId + "/messages", who, Map.of("body", text)).andExpect(status().isCreated());
    }

    private ResultActions unreadCount(Member who) throws Exception {
        return call(get(CONVERSATIONS + "/unread-count"), who);
    }

    private ResultActions markRead(Member who, String conversationId) throws Exception {
        return call(patch(CONVERSATIONS + "/" + conversationId + "/read"), who);
    }

    // --- who may use it ------------------------------------------------------------------------------------------------

    @Test
    void everythingNeedsASignIn() throws Exception {
        Chat chat = chat("unreadauth");

        unreadCount(null).andExpect(status().isUnauthorized());
        markRead(null, chat.id()).andExpect(status().isUnauthorized());
    }

    @Test
    void aSuspendedAccountWithAStillValidTokenCannotMarkAnythingAsRead() throws Exception {
        Chat chat = chat("unreadsusp");
        say(chat.buyer(), chat.id(), "Hello");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", chat.seller().id());

        markRead(chat.seller(), chat.id()).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE read_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void anybodyWhoIsNotInTheConversationCannotMarkAnythingAsRead() throws Exception {
        Chat chat = chat("unreadoutsider");
        Member outsider = member("unreadoutsiderx");
        say(chat.buyer(), chat.id(), "Hello");

        markRead(outsider, chat.id()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        markRead(outsider, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        unreadCount(chat.seller()).andExpect(jsonPath("$.unreadMessages").value(1));
    }

    // --- the numbers ---------------------------------------------------------------------------------------------------

    @Test
    void whatTheOtherPersonSentIsUnreadForYouAndNothingElse() throws Exception {
        Chat chat = chat("unreadcount");
        say(chat.buyer(), chat.id(), "Is it available?");
        say(chat.buyer(), chat.id(), "What is your lowest price?");

        unreadCount(chat.seller())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.unreadMessages").value(2))
                .andExpect(jsonPath("$.unreadConversations").value(1));
        // the sender has nothing to read: their own messages never count against them
        unreadCount(chat.buyer()).andExpect(jsonPath("$.unreadMessages").value(0)).andExpect(jsonPath("$.unreadConversations").value(0));
    }

    @Test
    void theInboxShowsHowManyMessagesAreUnreadInEachConversation() throws Exception {
        Chat chat = chat("unreadinbox");
        say(chat.buyer(), chat.id(), "One");
        say(chat.buyer(), chat.id(), "Two");
        say(chat.seller(), chat.id(), "Three");

        call(get(CONVERSATIONS), chat.seller()).andExpect(jsonPath("$.content[0].unreadCount").value(2));
        call(get(CONVERSATIONS), chat.buyer()).andExpect(jsonPath("$.content[0].unreadCount").value(1));
        call(get(CONVERSATIONS + "/" + chat.id()), chat.seller()).andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void theTotalsCoverEveryConversationOfThePerson() throws Exception {
        Member seller = member("unreadmanyseller");
        Chat first = chatBetween(member("unreadmanya"), seller, "First ad");
        Chat second = chatBetween(member("unreadmanyb"), seller, "Second ad");
        Chat quiet = chatBetween(member("unreadmanyc"), seller, "Third ad");
        say(first.buyer(), first.id(), "a1");
        say(first.buyer(), first.id(), "a2");
        say(second.buyer(), second.id(), "b1");
        say(seller, quiet.id(), "I wrote first");                 // the seller's own message is not unread for the seller

        unreadCount(seller).andExpect(jsonPath("$.unreadMessages").value(3)).andExpect(jsonPath("$.unreadConversations").value(2));
        unreadCount(quiet.buyer()).andExpect(jsonPath("$.unreadMessages").value(1)).andExpect(jsonPath("$.unreadConversations").value(1));
    }

    // --- marking as read -----------------------------------------------------------------------------------------------

    @Test
    void openingAConversationMarksTheOtherPersonsMessagesAsReadAndShowsThemToThem() throws Exception {
        Chat chat = chat("unreadmark");
        say(chat.buyer(), chat.id(), "Is it available?");
        say(chat.seller(), chat.id(), "Yes.");

        // the seller opens the chat: the buyer's message is read, the seller's own is not (the buyer has not opened it yet)
        markRead(chat.seller(), chat.id())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.unreadMessages").value(0))
                .andExpect(jsonPath("$.unreadConversations").value(0));

        call(get(CONVERSATIONS + "/" + chat.id() + "/messages"), chat.buyer())
                .andExpect(jsonPath("$.content[0].body").value("Is it available?"))
                .andExpect(jsonPath("$.content[0].readAt").value(notNullValue()))      // the buyer sees that it was read
                .andExpect(jsonPath("$.content[1].body").value("Yes."))
                .andExpect(jsonPath("$.content[1].readAt").value(nullValue()));
        unreadCount(chat.buyer()).andExpect(jsonPath("$.unreadMessages").value(1));
        call(get(CONVERSATIONS), chat.seller()).andExpect(jsonPath("$.content[0].unreadCount").value(0));
    }

    @Test
    void markingAsReadOnlyAffectsThatConversationAndAnswersWithTheTotalsThatRemain() throws Exception {
        Member seller = member("unreadonlyseller");
        Chat first = chatBetween(member("unreadonlya"), seller, "First ad");
        Chat second = chatBetween(member("unreadonlyb"), seller, "Second ad");
        say(first.buyer(), first.id(), "a1");
        say(second.buyer(), second.id(), "b1");
        say(second.buyer(), second.id(), "b2");

        markRead(seller, first.id())
                .andExpect(jsonPath("$.unreadMessages").value(2))
                .andExpect(jsonPath("$.unreadConversations").value(1));
        call(get(CONVERSATIONS + "/" + second.id()), seller).andExpect(jsonPath("$.unreadCount").value(2));
    }

    @Test
    void markingAsReadAgainChangesNothing() throws Exception {
        Chat chat = chat("unreadtwice");
        say(chat.buyer(), chat.id(), "Hello");

        markRead(chat.seller(), chat.id()).andExpect(status().isOk());
        Object firstReadAt = jdbc.queryForObject("SELECT read_at FROM messages WHERE conversation_id = ?::uuid", Object.class, chat.id());
        markRead(chat.seller(), chat.id()).andExpect(status().isOk()).andExpect(jsonPath("$.unreadMessages").value(0));

        assertThat(jdbc.queryForObject("SELECT read_at FROM messages WHERE conversation_id = ?::uuid", Object.class, chat.id()))
                .as("a message is marked once; the time it was first read is kept").isEqualTo(firstReadAt);
    }

    @Test
    void aMessageThatArrivesAfterOpeningIsUnreadAgain() throws Exception {
        Chat chat = chat("unreadagain");
        say(chat.buyer(), chat.id(), "First");
        markRead(chat.seller(), chat.id()).andExpect(jsonPath("$.unreadMessages").value(0));

        say(chat.buyer(), chat.id(), "Second");

        unreadCount(chat.seller()).andExpect(jsonPath("$.unreadMessages").value(1));
    }

    @Test
    void readingTheMessagesDoesNotMarkThemAsRead() throws Exception {
        Chat chat = chat("unreadpassive");
        say(chat.buyer(), chat.id(), "Hello");

        call(get(CONVERSATIONS + "/" + chat.id() + "/messages"), chat.seller()).andExpect(status().isOk());

        unreadCount(chat.seller()).andExpect(jsonPath("$.unreadMessages").value(1));
    }
}
