package com.resalemarket.favorite;

import static com.resalemarket.listing.ListingRows.row;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.listing.ListingIntegrationSupportAccess;
import com.resalemarket.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The two limits on favourites, with small values (they are read at start-up, so this test has its own application context). */
@IntegrationTest
@TestPropertySource(properties = {"app.favorite.max-per-user=2", "app.favorite.rate-limits.write.attempts=6", "app.favorite.rate-limits.write.window=1h"})
class FavoriteLimitsIntegrationTest extends ListingIntegrationSupportAccess {

    @Test
    void aMemberCanSaveOnlyAsManyAdsAsTheLimitAllows() throws Exception {
        Member seller = member("limitseller");
        Member fan = member("limitfan");
        UUID a = rowsFor(seller).insert(row().title("listing a"));
        UUID b = rowsFor(seller).insert(row().title("listing b"));
        UUID c = rowsFor(seller).insert(row().title("listing c"));

        call(post("/api/v1/favorites/" + a), fan).andExpect(status().isCreated());
        call(post("/api/v1/favorites/" + b), fan).andExpect(status().isCreated());
        call(post("/api/v1/favorites/" + c), fan)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.detail").value("You can save at most 2 ads. Remove some you no longer need first."));

        call(delete("/api/v1/favorites/" + a), fan).andExpect(status().isNoContent());
        call(post("/api/v1/favorites/" + c), fan).andExpect(status().isCreated());     // room again
    }

    @Test
    void savingAndRemovingAreRateLimitedPerMember() throws Exception {
        Member seller = member("ratelimitseller");
        Member fan = member("ratelimitfan");
        Member other = member("ratelimitother");
        UUID id = rowsFor(seller).insert(row().title("listing a"));

        for (int i = 0; i < 3; i++) {
            call(post("/api/v1/favorites/" + id), fan);                      // the first saves, the rest conflict: all count
            call(delete("/api/v1/favorites/" + id), fan).andExpect(status().isNoContent());
        }
        call(post("/api/v1/favorites/" + id), fan)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(header().exists("Retry-After"));
        call(delete("/api/v1/favorites/" + id), fan).andExpect(status().isTooManyRequests());
        // Another member is not affected.
        call(post("/api/v1/favorites/" + id), other).andExpect(status().isCreated());
    }
}
