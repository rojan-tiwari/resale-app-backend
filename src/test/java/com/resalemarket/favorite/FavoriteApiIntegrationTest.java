package com.resalemarket.favorite;

import static com.resalemarket.listing.ListingRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.listing.ListingIntegrationSupportAccess;
import com.resalemarket.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Saved listings end to end against PostgreSQL: who may do what, duplicates, ads that stop being available, and concurrency. */
@IntegrationTest
class FavoriteApiIntegrationTest extends ListingIntegrationSupportAccess {

    private static final String FAVORITES = "/api/v1/favorites";

    private ResultActions save(Member who, UUID listingId) throws Exception {
        return call(post(FAVORITES + "/" + listingId), who);
    }

    private UUID liveListing(Member seller, String title) throws Exception {
        return rowsFor(seller).insert(row().title(title));
    }

    // --- who may use it ------------------------------------------------------------------------------------------------

    @Test
    void everythingNeedsASignIn() throws Exception {
        UUID id = liveListing(member("favseller"), "A phone");

        call(get(FAVORITES), null).andExpect(status().isUnauthorized());
        call(get(FAVORITES + "/ids"), null).andExpect(status().isUnauthorized());
        save(null, id).andExpect(status().isUnauthorized());
        call(delete(FAVORITES + "/" + id), null).andExpect(status().isUnauthorized());
    }

    @Test
    void aSuspendedAccountWithAStillValidTokenCannotSave() throws Exception {
        Member seller = member("favseller2");
        Member fan = member("suspendedfan");
        UUID id = liveListing(seller, "A phone");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", fan.id());

        save(fan, id).andExpect(status().isUnauthorized());
    }

    // --- saving --------------------------------------------------------------------------------------------------------

    @Test
    void savingReturnsTheCardAndTheIdShowsUpEverywhere() throws Exception {
        Member seller = member("favseller3");
        Member fan = member("fan");
        UUID id = liveListing(seller, "Saved phone");

        save(fan, id)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.listingId").value(id.toString()))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.savedAt").isNotEmpty())
                .andExpect(jsonPath("$.listing.title").value("Saved phone"));
        call(get(FAVORITES + "/ids"), fan).andExpect(status().isOk()).andExpect(jsonPath("$[0]").value(id.toString()));
        call(get(FAVORITES), fan)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].listing.category.name").value("Mobiles & Tablets"))
                .andExpect(jsonPath("$.content[0].available").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites WHERE user_id = ?::uuid", Integer.class, fan.id())).isEqualTo(1);
    }

    @Test
    void savingTwiceIsAConflict() throws Exception {
        Member fan = member("fan2");
        UUID id = liveListing(member("favseller4"), "Phone");

        save(fan, id).andExpect(status().isCreated());
        save(fan, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites WHERE user_id = ?::uuid", Integer.class, fan.id())).isEqualTo(1);
    }

    @Test
    void twoSimultaneousSavesOfTheSameAdMakeOneRowAndOneConflict() throws Exception {
        Member fan = member("fan3");
        UUID id = liveListing(member("favseller5"), "Phone");

        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> save(fan, id).andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites WHERE user_id = ?::uuid", Integer.class, fan.id())).isEqualTo(1);
    }

    @Test
    void onlyALiveAdCanBeSavedAndEverythingElseLooksNonExistent() throws Exception {
        Member seller = member("favseller6");
        Member fan = member("fan4");
        UUID draft = rowsFor(seller).insert(row().title("draft").status("DRAFT"));
        UUID sold = rowsFor(seller).insert(row().title("sold").status("SOLD"));
        UUID archived = rowsFor(seller).insert(row().title("archived").status("ARCHIVED"));

        for (UUID id : List.of(draft, sold, archived, UUID.randomUUID())) {
            save(fan, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites WHERE user_id = ?::uuid", Integer.class, fan.id())).isZero();
    }

    @Test
    void anAdOfASuspendedMemberCannotBeSaved() throws Exception {
        Member seller = member("favseller7");
        Member fan = member("fan5");
        UUID id = liveListing(seller, "Phone");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", seller.id());

        save(fan, id).andExpect(status().isNotFound());
    }

    @Test
    void aMemberMaySaveTheirOwnAd() throws Exception {
        Member seller = member("favseller8");
        UUID id = liveListing(seller, "Mine");

        save(seller, id).andExpect(status().isCreated());
    }

    @Test
    void anIdThatIsNotAUuidIsABadRequest() throws Exception {
        Member fan = member("fan6");

        call(post(FAVORITES + "/not-a-uuid"), fan).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // --- removing ------------------------------------------------------------------------------------------------------

    @Test
    void removingIsIdempotent() throws Exception {
        Member fan = member("fan7");
        UUID id = liveListing(member("favseller9"), "Phone");
        save(fan, id).andExpect(status().isCreated());

        call(delete(FAVORITES + "/" + id), fan).andExpect(status().isNoContent());
        call(delete(FAVORITES + "/" + id), fan).andExpect(status().isNoContent());
        call(delete(FAVORITES + "/" + UUID.randomUUID()), fan).andExpect(status().isNoContent());

        call(get(FAVORITES + "/ids"), fan).andExpect(jsonPath("$", hasSize(0)));
        save(fan, id).andExpect(status().isCreated());     // and it can be saved again
    }

    @Test
    void nobodyCanSeeOrRemoveAnotherMembersFavourites() throws Exception {
        Member seller = member("favseller10");
        Member sita = member("sita2");
        Member ram = member("ram2");
        UUID id = liveListing(seller, "Phone");
        save(sita, id).andExpect(status().isCreated());

        call(get(FAVORITES), ram).andExpect(jsonPath("$.totalElements").value(0));
        call(get(FAVORITES + "/ids"), ram).andExpect(jsonPath("$", hasSize(0)));
        call(delete(FAVORITES + "/" + id), ram).andExpect(status().isNoContent());       // nothing of Ram's to remove
        call(get(FAVORITES + "/ids"), sita).andExpect(jsonPath("$", hasSize(1)));
    }

    // --- the list ------------------------------------------------------------------------------------------------------

    @Test
    void theListIsNewestSavedFirstAndPaginated() throws Exception {
        Member seller = member("favseller11");
        Member fan = member("fan8");
        UUID first = liveListing(seller, "first saved");
        UUID second = liveListing(seller, "second saved");
        UUID third = liveListing(seller, "third saved");
        for (UUID id : List.of(first, second, third)) {
            save(fan, id).andExpect(status().isCreated());
            Thread.sleep(15);                                  // distinct save times
        }

        call(get(FAVORITES + "?size=2"), fan)
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].listing.title").value("third saved"))
                .andExpect(jsonPath("$.content[1].listing.title").value("second saved"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        call(get(FAVORITES + "?size=2&page=1"), fan).andExpect(jsonPath("$.content[0].listing.title").value("first saved"));
        call(get(FAVORITES + "/ids"), fan).andExpect(jsonPath("$[0]").value(third.toString()));
        call(get(FAVORITES + "?sort=listing_id,desc&size=100000"), fan).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    // --- ads that stop being available ---------------------------------------------------------------------------------

    @Test
    void anAdThatSoldIsFlaggedAndKeepsItsCard() throws Exception {
        Member fan = member("fan9");
        UUID id = liveListing(member("favseller12"), "Will sell");
        save(fan, id).andExpect(status().isCreated());

        jdbc.update("UPDATE listings SET status = 'SOLD', sold_at = now() WHERE id = ?", id);

        call(get(FAVORITES), fan)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].available").value(false))
                .andExpect(jsonPath("$.content[0].listing.title").value("Will sell"))
                .andExpect(jsonPath("$.content[0].listing.status").value("SOLD"));
    }

    @Test
    void anAdThatWasTakenDownIsFlaggedAndNothingOfItIsHandedOut() throws Exception {
        Member fan = member("fan10");
        Member seller = member("favseller13");
        UUID archived = liveListing(seller, "Secret draft text");
        UUID rejected = liveListing(seller, "Rejected for a reason");
        save(fan, archived).andExpect(status().isCreated());
        save(fan, rejected).andExpect(status().isCreated());

        jdbc.update("UPDATE listings SET status = 'ARCHIVED' WHERE id = ?", archived);
        jdbc.update("UPDATE listings SET status = 'REJECTED', rejection_reason = 'no' WHERE id = ?", rejected);

        String body = call(get(FAVORITES), fan)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].available").value(false))
                .andExpect(jsonPath("$.content[0].listing").doesNotExist())
                .andExpect(jsonPath("$.content[1].available").value(false))
                .andExpect(jsonPath("$.content[1].listing").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Secret draft text").doesNotContain("Rejected for a reason");
        // The ids still include them, so the app can offer to remove them.
        call(get(FAVORITES + "/ids"), fan).andExpect(jsonPath("$", hasSize(2)));
        call(delete(FAVORITES + "/" + archived), fan).andExpect(status().isNoContent());
    }

    @Test
    void anAdComesBackWhenItIsLiveAgainAndHidesWhenItsSellerIsSuspended() throws Exception {
        Member fan = member("fan11");
        Member seller = member("favseller14");
        UUID id = liveListing(seller, "Flaky ad");
        save(fan, id).andExpect(status().isCreated());

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", seller.id());
        call(get(FAVORITES), fan).andExpect(jsonPath("$.content[0].available").value(false)).andExpect(jsonPath("$.content[0].listing").doesNotExist());

        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?::uuid", seller.id());
        call(get(FAVORITES), fan).andExpect(jsonPath("$.content[0].available").value(true)).andExpect(jsonPath("$.content[0].listing.title").value("Flaky ad"));
    }

    @Test
    void savedAdsDisappearWithTheirMemberAndTheirListing() throws Exception {
        Member fan = member("fan12");
        Member seller = member("favseller15");
        UUID id = liveListing(seller, "Gone soon");
        save(fan, id).andExpect(status().isCreated());

        jdbc.update("DELETE FROM listings WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorites WHERE listing_id = ?", Integer.class, id)).isZero();
        call(get(FAVORITES + "/ids"), fan).andExpect(jsonPath("$", not(hasItem(id.toString()))));
    }
}
