package com.resalemarket.messaging;

import static com.resalemarket.listing.ListingRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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

/** Conversations end to end against PostgreSQL: who may start and see one, the inbox, ads that stop being for sale, and concurrency. */
@IntegrationTest
class ConversationApiIntegrationTest extends ListingIntegrationSupportAccess {

    private static final String CONVERSATIONS = "/api/v1/conversations";

    private ResultActions start(Member who, UUID listingId) throws Exception {
        return postJson(CONVERSATIONS, who, Map.of("listingId", listingId.toString()));
    }

    private String started(Member who, UUID listingId) throws Exception {
        String body = start(who, listingId).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private UUID liveListing(Member seller, String title) throws Exception {
        return rowsFor(seller).insert(row().title(title));
    }

    private int conversationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class);
    }

    /** A conversation on a listing that is not live (the API refuses to start those), as it exists after the ad was sold or taken down. */
    private String conversationOn(UUID listingId, Member buyer, Member seller) {
        return jdbc.queryForObject(
                "INSERT INTO conversations (listing_id, buyer_id, seller_id) VALUES (?::uuid, ?::uuid, ?::uuid) RETURNING id::text",
                String.class, listingId.toString(), buyer.id(), seller.id());
    }

    // --- who may use it ------------------------------------------------------------------------------------------------

    @Test
    void everythingNeedsASignIn() throws Exception {
        UUID id = liveListing(member("convseller0"), "A phone");

        call(get(CONVERSATIONS), null).andExpect(status().isUnauthorized());
        call(get(CONVERSATIONS + "/" + UUID.randomUUID()), null).andExpect(status().isUnauthorized());
        start(null, id).andExpect(status().isUnauthorized());
    }

    @Test
    void aSuspendedAccountWithAStillValidTokenCannotStartOne() throws Exception {
        UUID id = liveListing(member("convseller1"), "A phone");
        Member buyer = member("suspendedbuyer");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", buyer.id());

        start(buyer, id).andExpect(status().isUnauthorized());
        assertThat(conversationCount()).isZero();
    }

    // --- starting ------------------------------------------------------------------------------------------------------

    @Test
    void aBuyerStartsAConversationAboutALiveAd() throws Exception {
        Member seller = member("convseller2");
        Member buyer = member("convbuyer2");
        UUID id = liveListing(seller, "Phone for sale");

        start(buyer, id)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Location", containsString(CONVERSATIONS + "/")))
                .andExpect(jsonPath("$.listingId").value(id.toString()))
                .andExpect(jsonPath("$.listingAvailable").value(true))
                .andExpect(jsonPath("$.listing.title").value("Phone for sale"))
                .andExpect(jsonPath("$.myRole").value("BUYER"))
                .andExpect(jsonPath("$.otherMember.id").value(seller.id()))
                .andExpect(jsonPath("$.otherMember.displayName").value("convseller2"))
                .andExpect(jsonPath("$.otherMember.active").value(true))
                .andExpect(jsonPath("$.lastMessageAt").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void bothPeopleSeeTheConversationEachWithTheOtherAsTheirContact() throws Exception {
        Member seller = member("convseller3");
        Member buyer = member("convbuyer3");
        String conversationId = started(buyer, liveListing(seller, "Phone"));

        call(get(CONVERSATIONS), seller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(conversationId))
                .andExpect(jsonPath("$.content[0].myRole").value("SELLER"))
                .andExpect(jsonPath("$.content[0].otherMember.id").value(buyer.id()))
                .andExpect(jsonPath("$.content[0].otherMember.displayName").value("convbuyer3"));
        call(get(CONVERSATIONS + "/" + conversationId), buyer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("BUYER"))
                .andExpect(jsonPath("$.otherMember.displayName").value("convseller3"))
                // only a name is shared: never the other person's e-mail address
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("@"));
    }

    @Test
    void askingAgainReturnsTheSameConversationInsteadOfAnotherOne() throws Exception {
        Member seller = member("convseller4");
        Member buyer = member("convbuyer4");
        UUID id = liveListing(seller, "Phone");

        String first = started(buyer, id);
        start(buyer, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first));

        assertThat(conversationCount()).isEqualTo(1);
    }

    @Test
    void youCannotStartAConversationAboutYourOwnAd() throws Exception {
        Member seller = member("convseller5");
        UUID id = liveListing(seller, "My own phone");

        start(seller, id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.detail").value("You cannot start a conversation about your own ad."));
        assertThat(conversationCount()).isZero();
    }

    @Test
    void onlyALiveAdCanBeAskedAboutAndEverythingElseLooksNonExistent() throws Exception {
        Member seller = member("convseller6");
        Member buyer = member("convbuyer6");
        UUID draft = rowsFor(seller).insert(row().title("draft").status("DRAFT"));
        UUID sold = rowsFor(seller).insert(row().title("sold").status("SOLD"));
        UUID archived = rowsFor(seller).insert(row().title("archived").status("ARCHIVED"));
        Member suspendedSeller = member("suspendedseller");
        UUID fromSuspended = liveListing(suspendedSeller, "from a suspended member");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", suspendedSeller.id());

        for (UUID id : List.of(draft, sold, archived, fromSuspended, UUID.randomUUID())) {
            start(buyer, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(conversationCount()).isZero();
    }

    @Test
    void theRequestIsValidated() throws Exception {
        Member buyer = member("convbuyer7");

        postJson(CONVERSATIONS, buyer, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("listingId"));
        postJson(CONVERSATIONS, buyer, Map.of("listingId", "not-an-id")).andExpect(status().isBadRequest());
    }

    @Test
    void twoSimultaneousRequestsStillMakeOneConversation() throws Exception {
        Member seller = member("convseller8");
        Member buyer = member("convbuyer8");
        UUID id = liveListing(seller, "Phone");

        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> start(buyer, id).andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).as("nobody gets an error: one request creates it, the others open it").containsOnly(201, 200);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(conversationCount()).isEqualTo(1);
    }

    // --- who can see a conversation ------------------------------------------------------------------------------------

    @Test
    void forAnybodyElseTheConversationDoesNotExist() throws Exception {
        Member seller = member("convseller9");
        Member buyer = member("convbuyer9");
        Member outsider = member("outsider9");
        String conversationId = started(buyer, liveListing(seller, "Phone"));

        call(get(CONVERSATIONS + "/" + conversationId), outsider)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        call(get(CONVERSATIONS + "/" + UUID.randomUUID()), outsider)
                .andExpect(status().isNotFound());
        call(get(CONVERSATIONS), outsider).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    // --- the inbox -----------------------------------------------------------------------------------------------------

    @Test
    void theInboxIsOrderedByLatestActivityAndPaginated() throws Exception {
        Member seller = member("convseller10");
        Member buyer = member("convbuyer10");
        String oldest = started(buyer, liveListing(seller, "First"));
        String newest = started(buyer, liveListing(seller, "Second"));
        String middle = started(buyer, liveListing(seller, "Third"));
        jdbc.update("UPDATE conversations SET last_message_at = now() - interval '3 days' WHERE id = ?::uuid", oldest);
        jdbc.update("UPDATE conversations SET last_message_at = now() - interval '1 hour' WHERE id = ?::uuid", newest);
        jdbc.update("UPDATE conversations SET last_message_at = now() - interval '1 day' WHERE id = ?::uuid", middle);

        call(get(CONVERSATIONS + "?size=2"), buyer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(newest))
                .andExpect(jsonPath("$.content[1].id").value(middle));
        call(get(CONVERSATIONS + "?size=2&page=1"), buyer)
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(oldest))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void aMemberSeesConversationsAsBuyerAndAsSellerInOneInbox() throws Exception {
        Member me = member("convme11");
        Member other = member("convother11");
        started(me, liveListing(other, "Their ad"));
        started(other, liveListing(me, "My ad"));

        call(get(CONVERSATIONS), me)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.myRole == 'BUYER')]", hasSize(1)))
                .andExpect(jsonPath("$.content[?(@.myRole == 'SELLER')]", hasSize(1)));
    }

    // --- ads that stop being for sale ----------------------------------------------------------------------------------

    @Test
    void aSoldAdKeepsItsCardButIsNoLongerForSale() throws Exception {
        Member seller = member("convseller12");
        Member buyer = member("convbuyer12");
        UUID sold = rowsFor(seller).insert(row().title("Sold phone").status("SOLD"));
        String id = conversationOn(sold, buyer, seller);

        call(get(CONVERSATIONS + "/" + id), buyer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingAvailable").value(false))
                .andExpect(jsonPath("$.listing.title").value("Sold phone"))
                .andExpect(jsonPath("$.listing.status").value("SOLD"));
    }

    @Test
    void anAdThatWasTakenDownHasNoCardInTheConversation() throws Exception {
        Member seller = member("convseller13");
        Member buyer = member("convbuyer13");
        UUID archived = rowsFor(seller).insert(row().title("Gone").status("ARCHIVED"));
        String id = conversationOn(archived, buyer, seller);

        call(get(CONVERSATIONS + "/" + id), buyer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId").value(archived.toString()))
                .andExpect(jsonPath("$.listingAvailable").value(false))
                .andExpect(jsonPath("$.listing").value(nullValue()));
    }

    @Test
    void aSuspendedPersonStaysInTheConversationWithoutANameOrAnyDetails() throws Exception {
        Member seller = member("convseller14");
        Member buyer = member("convbuyer14");
        String id = started(buyer, liveListing(seller, "Phone"));
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", seller.id());

        call(get(CONVERSATIONS + "/" + id), buyer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherMember.id").value(seller.id()))
                .andExpect(jsonPath("$.otherMember.displayName").value(nullValue()))
                .andExpect(jsonPath("$.otherMember.active").value(false))
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).doesNotContain("convseller14"));
    }

    @Test
    void theInboxShowsTheOtherPersonAsANameAndNothingMore() throws Exception {
        Member seller = member("convseller15");
        Member buyer = member("convbuyer15");
        started(buyer, liveListing(seller, "Phone"));

        call(get(CONVERSATIONS), seller)
                .andExpect(jsonPath("$.content[0].otherMember.displayName").value("convbuyer15"))
                .andExpect(jsonPath("$.content[0].listing.title").value("Phone"))
                // exactly an id, a name and whether the account is in good standing: no e-mail address, no phone number
                .andExpect(jsonPath("$.content[0].otherMember.length()").value(3))
                .andExpect(jsonPath("$.content[0].otherMember.email").doesNotExist())
                .andExpect(jsonPath("$.content[0].otherMember.phone").doesNotExist());
    }
}
