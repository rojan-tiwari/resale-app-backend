package com.resalemarket.discovery;

import static com.resalemarket.listing.ListingRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.resalemarket.listing.ListingIntegrationSupportAccess;
import com.resalemarket.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rule-based recommendations end to end: what a visitor gets, what a member with favourites gets, and what is never suggested. */
@IntegrationTest
class RecommendationApiIntegrationTest extends ListingIntegrationSupportAccess {

    private static final String RECOMMENDED = "/api/v1/listings/recommended";

    private List<String> titles(Member who, String query) throws Exception {
        String body = call(get(RECOMMENDED + query), who).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.listings[*].title");
    }

    private void save(Member fan, UUID id) throws Exception {
        call(post("/api/v1/favorites/" + id), fan).andExpect(status().isCreated());
    }

    // --- visitors ------------------------------------------------------------------------------------------------------

    @Test
    void aVisitorGetsTheMostViewedRecentAdsAndIsToldSo() throws Exception {
        Member seller = member("recseller");
        var rows = rowsFor(seller);
        rows.insert(row().title("quiet").views(3));
        rows.insert(row().title("popular").views(500));
        rows.insert(row().title("fairly popular").views(50));
        rows.insert(row().title("popular but old").views(9999).postedDaysAgo(60));
        rows.insert(row().title("popular but sold").views(9999).status("SOLD"));
        rows.insert(row().title("popular draft").views(9999).status("DRAFT"));

        call(get(RECOMMENDED), null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.basis").value("POPULAR"))
                .andExpect(jsonPath("$.listings[0].title").value("popular"))
                .andExpect(jsonPath("$.listings[1].title").value("fairly popular"))
                .andExpect(jsonPath("$.listings[2].title").value("quiet"))
                .andExpect(jsonPath("$.listings", hasSize(3)));
    }

    @Test
    void theLimitIsKeptBetweenOneAndTwentyFour() throws Exception {
        var rows = rowsFor(member("recseller2"));
        for (int i = 0; i < 5; i++) {
            rows.insert(row().title("ad " + i).views(i));
        }

        assertThat(titles(null, "?limit=2")).hasSize(2);
        assertThat(titles(null, "?limit=0")).hasSize(1);
        assertThat(titles(null, "?limit=999")).hasSize(5);
    }

    @Test
    void theAdsOfASuspendedMemberAreNeverSuggested() throws Exception {
        Member seller = member("recseller3");
        rowsFor(seller).insert(row().title("from a suspended seller").views(100));
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", seller.id());

        assertThat(titles(null, "")).doesNotContain("from a suspended seller");
    }

    // --- members -------------------------------------------------------------------------------------------------------

    @Test
    void aMemberWhoSavedNothingGetsWhatIsPopular() throws Exception {
        Member fan = member("recfan");
        rowsFor(member("recseller4")).insert(row().title("popular").views(10));

        call(get(RECOMMENDED), fan)
                .andExpect(jsonPath("$.basis").value("POPULAR"))
                .andExpect(jsonPath("$.listings[0].title").value("popular"));
    }

    @Test
    void aMemberIsNeverSuggestedTheirOwnAdsEvenAsPopularOnes() throws Exception {
        Member seller = member("recselfseller");
        rowsFor(seller).insert(row().title("my very popular ad").views(10_000));
        rowsFor(member("recotherseller")).insert(row().title("someone else's ad").views(5));

        assertThat(titles(seller, "")).containsExactly("someone else's ad");           // POPULAR, without their own
        assertThat(titles(null, "")).contains("my very popular ad", "someone else's ad");   // a visitor sees both
    }

    @Test
    void aMemberWithFavouritesGetsNewAdsFromThoseCategoriesButNeverTheirOwnOrSavedOnes() throws Exception {
        Member seller = member("recseller5");
        Member fan = member("recfan2");
        var sellerRows = rowsFor(seller);
        var fanRows = rowsFor(fan);
        UUID saved = sellerRows.insert(row().title("the saved phone").category("mobiles-tablets", "mobile-phones").postedDaysAgo(9));
        sellerRows.insert(row().title("newer phone").category("mobiles-tablets", "mobile-phones").postedDaysAgo(1));
        sellerRows.insert(row().title("a tablet").category("mobiles-tablets", "tablets").postedDaysAgo(2));
        sellerRows.insert(row().title("a laptop").category("computers-laptops", "laptops").postedDaysAgo(0).views(0));
        fanRows.insert(row().title("the fan's own phone").category("mobiles-tablets", "mobile-phones").postedDaysAgo(0));
        sellerRows.insert(row().title("old phone ad").category("mobiles-tablets", "mobile-phones").postedDaysAgo(8).status("ARCHIVED"));
        save(fan, saved);

        call(get(RECOMMENDED + "?limit=2"), fan)
                .andExpect(jsonPath("$.basis").value("FAVOURITE_CATEGORIES"))
                .andExpect(jsonPath("$.listings", hasSize(2)))
                .andExpect(jsonPath("$.listings[0].title").value("newer phone"))
                .andExpect(jsonPath("$.listings[1].title").value("a tablet"));
        // Asked for more, the rest is filled with popular ads, never with the member's own, saved or duplicate ones.
        List<String> all = titles(fan, "?limit=24");
        assertThat(all).startsWith("newer phone", "a tablet");
        assertThat(all).contains("a laptop");
        assertThat(all).doesNotContain("the saved phone", "the fan's own phone", "old phone ad");
        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    void theMostSavedCategoriesComeFirstAndAtMostThreeAreUsed() throws Exception {
        Member seller = member("recseller6");
        Member fan = member("recfan3");
        var rows = rowsFor(seller);
        UUID phone1 = rows.insert(row().title("saved phone 1").category("mobiles-tablets", "mobile-phones"));
        UUID phone2 = rows.insert(row().title("saved phone 2").category("mobiles-tablets", "mobile-phones"));
        UUID laptop = rows.insert(row().title("saved laptop").category("computers-laptops", "laptops"));
        UUID fashion = rows.insert(row().title("saved shirt").category("fashion", "mens-clothing"));
        UUID book = rows.insert(row().title("saved book").category("books-education", "textbooks-study-guides"));
        for (UUID id : List.of(phone1, phone2, laptop, fashion, book)) {
            save(fan, id);
        }
        rows.insert(row().title("fresh phone").category("mobiles-tablets", "mobile-phones").postedDaysAgo(3));
        rows.insert(row().title("fresh laptop").category("computers-laptops", "laptops").postedDaysAgo(2));
        rows.insert(row().title("fresh shirt").category("fashion", "mens-clothing").postedDaysAgo(1));
        rows.insert(row().title("fresh book").category("books-education", "textbooks-study-guides").postedDaysAgo(0));

        List<String> picked = JsonPath.read(
                call(get(RECOMMENDED + "?limit=3"), fan).andExpect(jsonPath("$.basis").value("FAVOURITE_CATEGORIES")).andReturn()
                        .getResponse().getContentAsString(),
                "$.listings[*].title");

        // Phones (saved twice) and the two lowest-id categories among the ties are used; the book category is the fourth and is not.
        assertThat(picked).contains("fresh phone").doesNotContain("fresh book");
        assertThat(picked).hasSize(3);
    }

    @Test
    void theListIsAlwaysAnswerEvenWhenThereIsNothingToRecommend() throws Exception {
        call(get(RECOMMENDED), null).andExpect(status().isOk()).andExpect(jsonPath("$.listings", hasSize(0))).andExpect(jsonPath("$.basis").value("POPULAR"));
    }

    @Test
    void aBrokenTokenIsStillRefusedOnThisPublicEndpoint() throws Exception {
        Member forged = callerWith("Bearer " + com.resalemarket.support.TestTokens.valid(
                com.resalemarket.support.TestTokens.encoderWithAnotherKey(), UUID.randomUUID().toString(), "ROLE_USER"));

        call(get(RECOMMENDED), forged).andExpect(status().isUnauthorized());
    }
}
