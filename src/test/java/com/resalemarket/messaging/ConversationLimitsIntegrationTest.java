package com.resalemarket.messaging;

import static com.resalemarket.listing.ListingRows.row;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.listing.ListingIntegrationSupportAccess;
import com.resalemarket.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The limit on starting conversations, with a small value (it is read at start-up, so this test has its own application context). */
@IntegrationTest
@TestPropertySource(properties = {"app.messaging.rate-limits.start.attempts=2", "app.messaging.rate-limits.start.window=1h"})
class ConversationLimitsIntegrationTest extends ListingIntegrationSupportAccess {

    private static final String CONVERSATIONS = "/api/v1/conversations";

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
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE buyer_id = ?::uuid", Integer.class, buyer.id())).isEqualTo(2);
    }
}
