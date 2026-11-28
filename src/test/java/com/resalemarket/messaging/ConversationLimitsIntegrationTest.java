package com.resalemarket.messaging;

import static com.resalemarket.listing.ListingRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.resalemarket.listing.ListingIntegrationSupportAccess;
import com.resalemarket.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The limits on messaging, with small values (they are read at start-up, so this test has its own application context). */
@IntegrationTest
@TestPropertySource(properties = {
        "app.messaging.rate-limits.start.attempts=2", "app.messaging.rate-limits.start.window=1h",
        "app.messaging.rate-limits.send.attempts=3", "app.messaging.rate-limits.send.window=1h",
        "app.messaging.max-message-length=20"})
class ConversationLimitsIntegrationTest extends ListingIntegrationSupportAccess {

    private static final String CONVERSATIONS = "/api/v1/conversations";

    private String start(Member who, UUID listing) throws Exception {
        String body = postJson(CONVERSATIONS, who, Map.of("listingId", listing.toString())).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void aMemberCanStartOnlyAsManyNewConversationsAsTheLimitAllows() throws Exception {
        Member seller = member("limitconvseller");
        Member buyer = member("limitconvbuyer");
        Member other = member("limitconvother");
        UUID a = rowsFor(seller).insert(row().title("listing a"));
        UUID b = rowsFor(seller).insert(row().title("listing b"));
        UUID c = rowsFor(seller).insert(row().title("listing c"));

        postJson(CONVERSATIONS, buyer, Map.of("listingId", a.toString())).andExpect(status().isCreated());
        postJson(CONVERSATIONS, buyer, Map.of("listingId", b.toString())).andExpect(status().isCreated());
        postJson(CONVERSATIONS, buyer, Map.of("listingId", c.toString()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        // The refused one was not kept, and opening a conversation that already exists is never refused.
        postJson(CONVERSATIONS, buyer, Map.of("listingId", a.toString())).andExpect(status().isOk());
        postJson(CONVERSATIONS, other, Map.of("listingId", c.toString())).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE buyer_id = ?::uuid", Integer.class, buyer.id())).isEqualTo(2);
    }

    @Test
    void aMemberCanSendOnlyAsManyMessagesAsTheLimitAllows() throws Exception {
        Member seller = member("limitmsgseller");
        Member buyer = member("limitmsgbuyer");
        String id = start(buyer, rowsFor(seller).insert(row().title("listing a")));
        String messages = CONVERSATIONS + "/" + id + "/messages";

        for (int i = 1; i <= 3; i++) {
            postJson(messages, buyer, Map.of("body", "message " + i)).andExpect(status().isCreated());
        }
        postJson(messages, buyer, Map.of("body", "message 4"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        // the limit is per person: the seller can still answer, and the refused message was not stored
        postJson(messages, seller, Map.of("body", "Yes, it is.")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE conversation_id = ?::uuid", Integer.class, id)).isEqualTo(4);
    }

    @Test
    void theLongestMessageIsTheOneConfigured() throws Exception {
        Member seller = member("limitlenseller");
        Member buyer = member("limitlenbuyer");
        String id = start(buyer, rowsFor(seller).insert(row().title("listing a")));
        String messages = CONVERSATIONS + "/" + id + "/messages";

        postJson(messages, buyer, Map.of("body", "x".repeat(20))).andExpect(status().isCreated());
        postJson(messages, seller, Map.of("body", "y".repeat(21)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("A message can be at most 20 characters long."));
    }
}
