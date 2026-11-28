package com.resalemarket.listing;

import static com.resalemarket.listing.ListingRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.resalemarket.listing.ListingRows.Row;
import com.resalemarket.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Search end to end against PostgreSQL: the keyword rules, every filter on its own and all of them together, sorting, paging,
 * and what is never shown. Listings are inserted straight into the table so each test chooses every value; most tests also carry a
 * made-up keyword ({@link #token}) in the titles, so they only ever see their own rows.
 */
@IntegrationTest
class ListingSearchApiIntegrationTest extends ListingIntegrationSupport {

    private static final String SEARCH = "/api/v1/listings";

    private Member seller;
    private ListingRows rows;
    private String token;

    @BeforeEach
    void seller() throws Exception {
        seller = member("searchseller");
        rows = new ListingRows(jdbc, UUID.fromString(seller.id()));
        token = "zq" + Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L), 36);
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    private String title(String rest) {
        return token + " " + rest;
    }

    private UUID add(Row row) {
        return rows.insert(row);
    }

    /**
     * Sends the query string exactly as given. A plus means a space (as a browser sends it), so it is spelled {@code %20}; and the
     * address is passed as a {@link java.net.URI}, because {@code get(String)} would encode the text a second time.
     */
    private ResultActions search(String query) throws Exception {
        return mockMvc.perform(get(java.net.URI.create(SEARCH + "?" + query.replace("+", "%20"))));
    }

    /** The titles found, without the made-up keyword, in the order returned. */
    private List<String> found(String query) throws Exception {
        String body = search(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> titles = JsonPath.read(body, "$.content[*].title");
        return titles.stream().map(t -> t.replace(token + " ", "")).toList();
    }

    private List<String> foundWithToken(String query) throws Exception {
        return found("q=" + token + (query.isEmpty() ? "" : "&" + query));
    }

    // --- what is shown at all ------------------------------------------------------------------------------------------

    @Test
    void onlyLiveListingsAreFoundWhateverTheStatus() throws Exception {
        add(row().title(title("active")));
        add(row().title(title("reserved")).status("RESERVED"));
        for (String hidden : List.of("DRAFT", "PENDING_REVIEW", "SOLD", "REJECTED", "ARCHIVED")) {
            add(row().title(title("hidden " + hidden)).status(hidden));
        }

        assertThat(foundWithToken("")).containsExactlyInAnyOrder("active", "reserved");
    }

    @Test
    void theListingsOfASuspendedOrDeletedMemberAreLeftOut() throws Exception {
        Member other = member("othersearch");
        ListingRows othersRows = new ListingRows(jdbc, UUID.fromString(other.id()));
        add(row().title(title("good standing")));
        othersRows.insert(row().title(title("suspended seller")));
        UUID deletedSeller = UUID.fromString(member("deletedsearch").id());
        new ListingRows(jdbc, deletedSeller).insert(row().title(title("deleted seller")));

        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", other.id());
        jdbc.update("UPDATE users SET status = 'DELETED' WHERE id = ?", deletedSeller);

        assertThat(foundWithToken("")).containsExactly("good standing");
        assertThat(found("")).doesNotContain("suspended seller", "deleted seller");
    }

    @Test
    void searchIsPublicAndReturnsCards() throws Exception {
        add(row().title(title("card")).price("85000").negotiable().condition("LIKE_NEW").withMunicipality());

        search("q=" + token)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=30")))
                .andExpect(jsonPath("$.content[0].price").value(85000.0))
                .andExpect(jsonPath("$.content[0].priceType").value("NEGOTIABLE"))
                .andExpect(jsonPath("$.content[0].condition").value("LIKE_NEW"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].category.name").value("Mobiles & Tablets"))
                .andExpect(jsonPath("$.content[0].subcategory.name").value("Mobile Phones"))
                .andExpect(jsonPath("$.content[0].location.district").value("Kathmandu"))
                .andExpect(jsonPath("$.content[0].publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void aBrokenTokenIsStillRefusedOnThisPublicEndpoint() throws Exception {
        Member forged = new Member("x", seller.id(), "Bearer " + com.resalemarket.support.TestTokens.valid(
                com.resalemarket.support.TestTokens.encoderWithAnotherKey(), seller.id(), "ROLE_USER"));

        call(get(SEARCH), forged).andExpect(status().isUnauthorized());
    }

    // --- the keyword ---------------------------------------------------------------------------------------------------

    @Test
    void findsWholeWordsInTheTitleAndInTheDescriptionAnyCase() throws Exception {
        add(row().title(title("Samsung Galaxy")));
        add(row().title(title("Some phone")).description("Great GALAXY screen and battery."));
        add(row().title(title("Unrelated bike")));

        assertThat(found("q=" + token + "+galaxy")).containsExactlyInAnyOrder("Samsung Galaxy", "Some phone");
    }

    @Test
    void allTheWordsMustMatchAndMinusExcludesAndQuotesKeepAPhrase() throws Exception {
        add(row().title(title("iPhone 13 Pro")));
        add(row().title(title("iPhone 11")));
        add(row().title(title("Pro camera")));

        assertThat(found("q=" + token + "+iphone+pro")).containsExactly("iPhone 13 Pro");
        assertThat(found("q=" + token + "+iphone+-pro")).containsExactly("iPhone 11");
        assertThat(found("q=%22" + token + "+iphone+13%22")).containsExactly("iPhone 13 Pro");
    }

    @Test
    void aPartOfATitleWordStillFindsIt() throws Exception {
        add(row().title(title("iPhone 13")));
        add(row().title(title("Samsung")));

        // "iph" is no whole word, so only the trigram match on the title can find it.
        assertThat(found("q=iph")).contains("iPhone 13");
        assertThat(found("q=iph")).doesNotContain("Samsung");
    }

    @Test
    void aTitleMatchOutranksADescriptionMatchByDefault() throws Exception {
        add(row().title(title("Laptop bag")).description("Fits a big one, with a nice strap.").postedDaysAgo(1));
        add(row().title(title("Old thing")).description("Works as a laptop, bought long ago.").postedDaysAgo(0));
        add(row().title(title("Laptop")).description("Plain.").postedDaysAgo(3));

        // Newest would put "Old thing" first; relevance (the default with a keyword) ranks the title matches above it.
        List<String> byDefault = found("q=" + token + "+laptop");
        assertThat(byDefault).containsExactlyInAnyOrder("Laptop bag", "Old thing", "Laptop");
        assertThat(byDefault.get(byDefault.size() - 1)).isEqualTo("Old thing");
        assertThat(found("q=" + token + "+laptop&sort=newest").get(0)).isEqualTo("Old thing");
    }

    @Test
    void percentAndUnderscoreAreOrdinaryCharactersNotWildcards() throws Exception {
        add(row().title(title("ab_cd literal")));
        add(row().title(title("abxcd other")));
        add(row().title(title("xyz%abc literal")));
        add(row().title(title("xyzQabc other")));

        assertThat(found("q=ab_c")).containsExactly("ab_cd literal");
        assertThat(found("q=xyz%25a")).containsExactly("xyz%abc literal");
    }

    @Test
    void textMeantToBreakTheQueryIsJustText() throws Exception {
        add(row().title(title("harmless")));
        String[] attacks = {"'; DROP TABLE listings; --", "' OR '1'='1", "\\", "%", "&|!():*<->", "\"unclosed phrase", "a\u0000b", "%%%%"};

        for (String attack : attacks) {
            String encoded = java.net.URLEncoder.encode(attack, java.nio.charset.StandardCharsets.UTF_8);
            int httpStatus = search("q=" + encoded).andReturn().getResponse().getStatus();
            assertThat(httpStatus).as(attack).isIn(200, 400);          // never a 500
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listings", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(foundWithToken("")).containsExactly("harmless");
    }

    @Test
    void theKeywordMustBeBetweenTwoAndOneHundredCharacters() throws Exception {
        search("q=a").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("q"));
        search("q=" + "x".repeat(101)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("q"));
        search("q=ab").andExpect(status().isOk());
        search("q=" + "x".repeat(100)).andExpect(status().isOk());
    }

    @Test
    void blankParametersCountAsNotGiven() throws Exception {
        add(row().title(title("one")));

        assertThat(foundWithToken("categoryId=&subcategoryId=&minPrice=&maxPrice=&condition=&provinceId=&districtId=&negotiable=&sort="))
                .containsExactly("one");
        search("q=%20%20%20").andExpect(status().isOk());
    }

    // --- the filters ---------------------------------------------------------------------------------------------------

    @Test
    void filtersByCategoryAndSubcategory() throws Exception {
        add(row().title(title("phone")).category("mobiles-tablets", "mobile-phones"));
        add(row().title(title("tablet")).category("mobiles-tablets", "tablets"));
        add(row().title(title("laptop")).category("computers-laptops", "laptops"));
        Integer mobiles = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'mobiles-tablets'", Integer.class);
        Integer tablets = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'tablets'", Integer.class);

        assertThat(foundWithToken("categoryId=" + mobiles)).containsExactlyInAnyOrder("phone", "tablet");
        assertThat(foundWithToken("subcategoryId=" + tablets)).containsExactly("tablet");
        assertThat(foundWithToken("categoryId=" + mobiles + "&subcategoryId=" + tablets)).containsExactly("tablet");
    }

    @Test
    void filtersByPriceRangeInclusive() throws Exception {
        add(row().title(title("cheap")).price("500"));
        add(row().title(title("low edge")).price("1000"));
        add(row().title(title("middle")).price("1500.50"));
        add(row().title(title("high edge")).price("2000"));
        add(row().title(title("dear")).price("9000"));

        assertThat(foundWithToken("minPrice=1000&maxPrice=2000")).containsExactlyInAnyOrder("low edge", "middle", "high edge");
        assertThat(foundWithToken("minPrice=2000")).containsExactlyInAnyOrder("high edge", "dear");
        assertThat(foundWithToken("maxPrice=500")).containsExactly("cheap");
        assertThat(foundWithToken("minPrice=0&maxPrice=0")).isEmpty();
    }

    @Test
    void filtersByOneOrMoreConditions() throws Exception {
        add(row().title(title("new")).condition("NEW"));
        add(row().title(title("good")).condition("GOOD"));
        add(row().title(title("fair")).condition("FAIR"));
        add(row().title(title("parts")).condition("FOR_PARTS"));

        assertThat(foundWithToken("condition=NEW")).containsExactly("new");
        assertThat(foundWithToken("condition=NEW&condition=FAIR")).containsExactlyInAnyOrder("new", "fair");
        assertThat(foundWithToken("condition=good,for_parts")).containsExactlyInAnyOrder("good", "parts");
    }

    @Test
    void filtersByProvinceDistrictAndMunicipality() throws Exception {
        add(row().title(title("ktm")).place(3, "Kathmandu").withMunicipality());
        add(row().title(title("ktm plain")).place(3, "Kathmandu"));
        add(row().title(title("lalitpur")).place(3, "Lalitpur"));
        add(row().title(title("kaski")).place(4, "Kaski"));
        Integer kathmandu = jdbc.queryForObject("SELECT id FROM districts WHERE name = 'Kathmandu'", Integer.class);
        Integer municipality = jdbc.queryForObject("SELECT municipality_id FROM listings WHERE title = ?", Integer.class, title("ktm"));

        assertThat(foundWithToken("provinceId=3")).containsExactlyInAnyOrder("ktm", "ktm plain", "lalitpur");
        assertThat(foundWithToken("provinceId=4")).containsExactly("kaski");
        assertThat(foundWithToken("districtId=" + kathmandu)).containsExactlyInAnyOrder("ktm", "ktm plain");
        assertThat(foundWithToken("municipalityId=" + municipality)).containsExactly("ktm");
    }

    @Test
    void filtersByNegotiableOnly() throws Exception {
        add(row().title(title("firm")));
        add(row().title(title("haggle")).negotiable());

        assertThat(foundWithToken("negotiable=true")).containsExactly("haggle");
        assertThat(foundWithToken("negotiable=false")).containsExactlyInAnyOrder("firm", "haggle");
    }

    @Test
    void filtersByHowRecentlyItWentLive() throws Exception {
        add(row().title(title("today")).postedDaysAgo(0));
        add(row().title(title("this week")).postedDaysAgo(5));
        add(row().title(title("last month")).postedDaysAgo(25));
        add(row().title(title("old")).postedDaysAgo(200));

        assertThat(foundWithToken("postedWithinDays=1")).containsExactly("today");
        assertThat(foundWithToken("postedWithinDays=7")).containsExactlyInAnyOrder("today", "this week");
        assertThat(foundWithToken("postedWithinDays=30")).containsExactlyInAnyOrder("today", "this week", "last month");
        assertThat(foundWithToken("postedWithinDays=365")).hasSize(4);
    }

    @Test
    void everyFilterWorksTogetherWithEveryOther() throws Exception {
        add(row().title(title("the one")).description("hello").price("1200").negotiable().condition("LIKE_NEW").postedDaysAgo(2)
                .category("mobiles-tablets", "mobile-phones").place(3, "Kathmandu").withMunicipality());
        // Each of these misses exactly one of the requirements.
        add(row().title(title("wrong price")).price("5000").negotiable().condition("LIKE_NEW").postedDaysAgo(2).place(3, "Kathmandu"));
        add(row().title(title("wrong condition")).price("1200").negotiable().condition("FAIR").postedDaysAgo(2).place(3, "Kathmandu"));
        add(row().title(title("firm price")).price("1200").condition("LIKE_NEW").postedDaysAgo(2).place(3, "Kathmandu"));
        add(row().title(title("too old")).price("1200").negotiable().condition("LIKE_NEW").postedDaysAgo(40).place(3, "Kathmandu"));
        add(row().title(title("other district")).price("1200").negotiable().condition("LIKE_NEW").postedDaysAgo(2).place(3, "Lalitpur"));
        add(row().title(title("other category")).price("1200").negotiable().condition("LIKE_NEW").postedDaysAgo(2)
                .category("computers-laptops", "laptops").place(3, "Kathmandu"));
        Integer mobiles = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'mobiles-tablets'", Integer.class);
        Integer phones = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'mobile-phones'", Integer.class);
        Integer kathmandu = jdbc.queryForObject("SELECT id FROM districts WHERE name = 'Kathmandu'", Integer.class);

        assertThat(found("q=" + token + "&categoryId=" + mobiles + "&subcategoryId=" + phones + "&minPrice=1000&maxPrice=2000"
                + "&condition=LIKE_NEW&condition=NEW&provinceId=3&districtId=" + kathmandu + "&negotiable=true&postedWithinDays=7"
                + "&sort=priceAsc")).containsExactly("the one");
        // Dropping any one filter lets exactly the row that missed that requirement back in.
        assertThat(found("q=" + token + "&categoryId=" + mobiles + "&minPrice=1000&maxPrice=2000&condition=LIKE_NEW"
                + "&districtId=" + kathmandu + "&negotiable=true&postedWithinDays=7")).containsExactly("the one");
    }

    // --- sorting -------------------------------------------------------------------------------------------------------

    @Test
    void sortsByNewestPriceAscendingAndPriceDescending() throws Exception {
        add(row().title(title("a")).price("300").postedDaysAgo(3));
        add(row().title(title("b")).price("100").postedDaysAgo(1));
        add(row().title(title("c")).price("200").postedDaysAgo(2));

        assertThat(foundWithToken("sort=newest")).containsExactly("b", "c", "a");
        assertThat(foundWithToken("sort=priceAsc")).containsExactly("b", "c", "a");
        assertThat(foundWithToken("sort=priceDesc")).containsExactly("a", "c", "b");
    }

    @Test
    void relevanceIsTheDefaultWithAKeywordAndNewestWithout() throws Exception {
        add(row().title(title("description only")).description("filler words about a drone in the description").postedDaysAgo(0));
        add(row().title(title("drone in the title")).postedDaysAgo(5));

        assertThat(found("q=" + token + "+drone&sort=relevance").get(0)).isEqualTo("drone in the title");   // the title match, though older
        assertThat(found("q=" + token + "+drone&sort=newest").get(0)).isEqualTo("description only");
        assertThat(found("q=" + token + "+drone")).isEqualTo(found("q=" + token + "+drone&sort=relevance"));
        // Without a keyword there is nothing to rank by: relevance is newest.
        assertThat(found("sort=relevance")).isEqualTo(found("sort=newest"));
    }

    @Test
    void anUnknownSortIsAFieldProblem() throws Exception {
        search("sort=cheapest").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
    }

    @Test
    void thePricesOfEqualListingsDoNotMakePagesRepeatOrSkipRows() throws Exception {
        for (int i = 0; i < 7; i++) {
            add(row().title(title("same " + i)).price("500").postedDaysAgo(1));      // identical price and date
        }

        List<String> all = new java.util.ArrayList<>();
        for (int page = 0; page < 4; page++) {
            all.addAll(foundWithToken("sort=priceAsc&size=2&page=" + page));
        }

        assertThat(all).hasSize(7).doesNotHaveDuplicates();
    }

    // --- paging --------------------------------------------------------------------------------------------------------

    @Test
    void pagesAndTotals() throws Exception {
        for (int i = 0; i < 5; i++) {
            add(row().title(title("item " + i)).price(String.valueOf(100 + i)));
        }

        search("q=" + token + "&sort=priceAsc&size=2&page=0")
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));
        search("q=" + token + "&sort=priceAsc&size=2&page=2")
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.last").value(true));
        search("q=" + token + "&size=2&page=9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void aShortFirstPageKnowsItsTotalAndAnEmptySearchSaysZero() throws Exception {
        add(row().title(title("only")));

        search("q=" + token).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.totalPages").value(1));
        search("q=" + token + "nomatch").andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void thePageSizeIsCappedAndOnlyTheFirstTenThousandResultsCanBeBrowsed() throws Exception {
        search("size=100000").andExpect(jsonPath("$.size").value(100));
        search("page=100&size=100")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        search("page=99&size=100").andExpect(status().isOk());
    }

    @Test
    void theClientsSortExpressionNeverReachesTheDatabaseAsAColumnName() throws Exception {
        add(row().title(title("x")));

        search("q=" + token + "&sort=password_hash,asc").andExpect(status().isBadRequest());       // not one of the four sorts
        search("q=" + token + "&sort=priceAsc&sort=nonsense").andExpect(status().isBadRequest());   // repeated: not a single sort
    }

    // --- invalid input -------------------------------------------------------------------------------------------------

    @Test
    void badParametersAreReportedPerField() throws Exception {
        search("minPrice=500&maxPrice=100").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("minPrice"));
        search("minPrice=-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("minPrice"));
        search("categoryId=0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("categoryId"));
        search("districtId=-4").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("districtId"));
        search("condition=BROKEN").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("condition"));
        search("postedWithinDays=0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("postedWithinDays"));
        search("postedWithinDays=366").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("postedWithinDays"));
    }

    @Test
    void aValueOfTheWrongTypeIsABadRequest() throws Exception {
        search("minPrice=abc").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        search("categoryId=x").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // --- the home page lists -------------------------------------------------------------------------------------------

    @Test
    void recentIsTheNewestLiveListingsAndTheLimitIsKept() throws Exception {
        for (int i = 0; i < 4; i++) {
            add(row().title(title("recent " + i)).postedDaysAgo(i));
        }
        add(row().title(title("draft")).status("DRAFT"));

        mockMvc.perform(get(SEARCH + "/recent?limit=3"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=30")))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].title").value(title("recent 0")))
                .andExpect(jsonPath("$[1].title").value(title("recent 1")));
        mockMvc.perform(get(SEARCH + "/recent?limit=0")).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get(SEARCH + "/recent?limit=999")).andExpect(status().isOk());
    }

    @Test
    void featuredShowsOnlyListingsWhosePromotionIsRunning() throws Exception {
        add(row().title(title("not featured")));
        add(row().title(title("featured forever")).featured());
        add(row().title(title("featured for a week")).featuredFor(7));
        add(row().title(title("promotion ended")).featuredFor(-2));
        add(row().title(title("featured but sold")).featured().status("SOLD"));
        add(row().title(title("featured reserved")).featured().status("RESERVED"));

        List<String> titles = JsonPath.read(
                mockMvc.perform(get(SEARCH + "/featured?limit=24")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$[*].title");

        assertThat(titles).contains(title("featured forever"), title("featured for a week"));
        assertThat(titles).doesNotContain(title("not featured"), title("promotion ended"), title("featured but sold"), title("featured reserved"));
    }
}
