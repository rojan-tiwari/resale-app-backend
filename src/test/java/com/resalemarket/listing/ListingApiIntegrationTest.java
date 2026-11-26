package com.resalemarket.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.resalemarket.support.IntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Listings end to end against PostgreSQL: creating, the rules for who may see and change what, the status moves, the public reads
 * and view counting. Photos are in {@link ListingImageApiIntegrationTest}, the approval switch in {@link ListingReviewIntegrationTest}.
 */
@IntegrationTest
class ListingApiIntegrationTest extends ListingIntegrationSupport {

    // --- creating ----------------------------------------------------------------------------------------------------

    @Test
    void creatingNeedsASignIn() throws Exception {
        postJson(LISTINGS, null, listingBody()).andExpect(status().isUnauthorized());
    }

    @Test
    void createsADraftOwnedByTheSignedInPerson() throws Exception {
        Member sita = member("sita");

        postJson(LISTINGS, sita, listingBody())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/listings/")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.title").value("iPhone 13, 128 GB, with box"))
                .andExpect(jsonPath("$.price").value(85000.00))
                .andExpect(jsonPath("$.currency").value("NPR"))
                .andExpect(jsonPath("$.priceType").value("NEGOTIABLE"))
                .andExpect(jsonPath("$.condition").value("LIKE_NEW"))
                .andExpect(jsonPath("$.category.name").value("Mobiles & Tablets"))
                .andExpect(jsonPath("$.subcategory.name").value("Mobile Phones"))
                .andExpect(jsonPath("$.location.province").value("Bagmati"))
                .andExpect(jsonPath("$.location.district").value("Kathmandu"))
                .andExpect(jsonPath("$.location.municipality").value("Kathmandu"))
                .andExpect(jsonPath("$.locality").value("Baneshwor"))
                .andExpect(jsonPath("$.seller.id").value(sita.id()))
                .andExpect(jsonPath("$.seller.displayName").value("sita"))
                .andExpect(jsonPath("$.images", hasSize(0)))
                .andExpect(jsonPath("$.viewCount").value(0))
                .andExpect(jsonPath("$.publishedAt").doesNotExist())
                .andExpect(jsonPath("$.allowedStatuses", hasItem("ACTIVE")))
                .andExpect(jsonPath("$.allowedStatuses", hasItem("ARCHIVED")));
    }

    @Test
    void theSellerIsAlwaysTheTokenNeverTheBody() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        Map<String, Object> body = listingBody("sellerId", ram.id());
        body.put("seller", Map.of("id", ram.id()));

        String id = createDraft(sita, body);

        assertThat(jdbc.queryForObject("SELECT seller_id::text FROM listings WHERE id = ?::uuid", String.class, id)).isEqualTo(sita.id());
    }

    @Test
    void anOmittedPriceTypeMeansFixedAndAFreeItemIsAllowed() throws Exception {
        Member sita = member("sita");
        Map<String, Object> body = listingBody();
        body.remove("priceType");
        body.put("price", 0);
        body.remove("municipalityId");
        body.put("locality", "   ");

        postJson(LISTINGS, sita, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceType").value("FIXED"))
                .andExpect(jsonPath("$.price").value(0.0))
                .andExpect(jsonPath("$.municipalityId").doesNotExist())
                .andExpect(jsonPath("$.location.municipality").doesNotExist())
                .andExpect(jsonPath("$.locality").doesNotExist());
    }

    @Test
    void textIsTrimmed() throws Exception {
        Member sita = member("sita");

        postJson(LISTINGS, sita, listingBody("title", "   Old guitar   "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Old guitar"));
    }

    @Test
    void invalidFieldsAreReportedTogether() throws Exception {
        Member sita = member("sita");
        Map<String, Object> body = listingBody();
        body.put("title", "ab");
        body.put("description", "too short");
        body.put("price", -5);
        body.remove("condition");
        body.remove("districtId");

        postJson(LISTINGS, sita, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("title")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("description")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("price")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("condition")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("districtId")));
    }

    @Test
    void aPriceNeedsAtMostTwoDecimalsAndAReasonableSize() throws Exception {
        Member sita = member("sita");

        postJson(LISTINGS, sita, listingBody("price", 10.999))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));
        postJson(LISTINGS, sita, listingBody("price", "12345678901"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));
        postJson(LISTINGS, sita, listingBody("price", 1250.5)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(1250.5));
    }

    @Test
    void anUnknownConditionIsABadRequest() throws Exception {
        Member sita = member("sita");

        postJson(LISTINGS, sita, listingBody("condition", "BROKEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void theSubcategoryMustBelongToTheCategory() throws Exception {
        Member sita = member("sita");
        Integer laptops = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'laptops'", Integer.class);

        postJson(LISTINGS, sita, listingBody("subcategoryId", laptops))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("subcategoryId"));
    }

    @Test
    void aCategoryWithSubcategoriesNeedsOne() throws Exception {
        Member sita = member("sita");
        Map<String, Object> body = listingBody();
        body.remove("subcategoryId");

        postJson(LISTINGS, sita, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("subcategoryId"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose a subcategory."));
    }

    @Test
    void aSubcategoryCannotBeUsedAsTheCategory() throws Exception {
        Member sita = member("sita");
        Integer phones = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'mobile-phones'", Integer.class);
        Map<String, Object> body = listingBody("categoryId", phones);
        body.remove("subcategoryId");

        postJson(LISTINGS, sita, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
        postJson(LISTINGS, sita, listingBody("categoryId", 999_999))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
    }

    @Test
    void anInactiveCategoryCannotBeChosen() throws Exception {
        Member sita = member("sita");
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE slug = 'mobile-phones'");
        try {
            postJson(LISTINGS, sita, listingBody())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("subcategoryId"));
        } finally {
            jdbc.update("UPDATE categories SET is_active = TRUE WHERE slug = 'mobile-phones'");
        }
    }

    @Test
    void thePlaceMustFitTogether() throws Exception {
        Member sita = member("sita");
        Integer otherProvinceDistrict = jdbc.queryForObject("SELECT id FROM districts WHERE province_id = 1 LIMIT 1", Integer.class);

        postJson(LISTINGS, sita, listingBody("districtId", otherProvinceDistrict))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("districtId"));

        Integer foreignMunicipality = jdbc.queryForObject(
                "SELECT id FROM municipalities WHERE district_id <> (SELECT id FROM districts WHERE name = 'Kathmandu') LIMIT 1",
                Integer.class);
        postJson(LISTINGS, sita, listingBody("municipalityId", foreignMunicipality))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("municipalityId"));
    }

    @Test
    void aSuspendedAccountCannotCreateListingsWithAStillValidToken() throws Exception {
        Member sita = member("sita");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", sita.id());

        postJson(LISTINGS, sita, listingBody())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // --- who may see a listing ---------------------------------------------------------------------------------------

    @Test
    void aDraftIsInvisibleToEveryoneButItsSellerAndAdministrators() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String id = createDraft(sita);

        call(get(LISTINGS + "/" + id), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        call(get(LISTINGS + "/" + id), ram).andExpect(status().isNotFound());
        call(get(LISTINGS + "/" + id), sita).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        call(get(LISTINGS + "/" + id), admin()).andExpect(status().isOk());
    }

    @Test
    void anUnknownListingIsNotFound() throws Exception {
        call(get(LISTINGS + "/7c9e6679-7425-40de-944b-e07fc1f90ae7"), null).andExpect(status().isNotFound());
    }

    @Test
    void aLiveListingIsPublicWithoutEmailOrPrivateData() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);

        String body = call(get(LISTINGS + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seller.displayName").value("sita"))
                .andExpect(jsonPath("$.seller.phone").doesNotExist())
                .andExpect(jsonPath("$.allowedStatuses", hasSize(0)))
                .andExpect(jsonPath("$.rejectionReason").doesNotExist())
                .andExpect(jsonPath("$.images", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(sita.email()).doesNotContain("passwordHash").doesNotContain("sellerId");
    }

    @Test
    void theSellersPhoneIsOnlyShownWithTheirConsent() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        jdbc.update("UPDATE user_profiles SET phone = '+9779812345678', show_phone = FALSE WHERE user_id = ?::uuid", sita.id());

        call(get(LISTINGS + "/" + id), null).andExpect(jsonPath("$.seller.phone").doesNotExist());

        jdbc.update("UPDATE user_profiles SET show_phone = TRUE WHERE user_id = ?::uuid", sita.id());
        call(get(LISTINGS + "/" + id), null).andExpect(jsonPath("$.seller.phone").value("+9779812345678"));
    }

    @Test
    void theListingsOfASuspendedMemberAreNotShownToThePublic() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", sita.id());

        call(get(LISTINGS + "/" + id), null).andExpect(status().isNotFound());
        call(get(LISTINGS + "/" + id), admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.displayName").value("Former member"));
    }

    @Test
    void anIdThatIsNotAUuidIsNeverPublic() throws Exception {
        Member sita = member("sita");

        call(get(LISTINGS + "/not-a-uuid"), null).andExpect(status().isUnauthorized());
        call(get(LISTINGS + "/not-a-uuid"), sita).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        call(get(LISTINGS + "/mine"), null).andExpect(status().isUnauthorized());
    }

    // --- views -------------------------------------------------------------------------------------------------------

    @Test
    void everyVisitFromSomeoneElseCountsAsAViewButTheSellersOwnDoNot() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String id = publishedListing(sita);

        call(get(LISTINGS + "/" + id), null).andExpect(jsonPath("$.viewCount").value(1));
        call(get(LISTINGS + "/" + id), ram).andExpect(jsonPath("$.viewCount").value(2));
        call(get(LISTINGS + "/" + id), sita).andExpect(jsonPath("$.viewCount").value(2));
        call(get(LISTINGS + "/" + id), admin()).andExpect(jsonPath("$.viewCount").value(2));

        assertThat(views(id)).isEqualTo(2);
    }

    @Test
    void viewsThatArriveTogetherAreAllCounted() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        int visits = 40;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < visits; i++) {
                Callable<Integer> visit = () -> mockMvc.perform(get(LISTINGS + "/" + id)).andReturn().getResponse().getStatus();
                results.add(pool.submit(visit));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(views(id)).isEqualTo(visits);
    }

    @Test
    void editingNeverOverwritesTheViewCount() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        for (int i = 0; i < 3; i++) {
            call(get(LISTINGS + "/" + id), null).andExpect(status().isOk());
        }

        call(put(LISTINGS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(listingBody("title", "A better title"))), sita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(3));

        assertThat(views(id)).isEqualTo(3);
    }

    // --- editing -----------------------------------------------------------------------------------------------------

    @Test
    void theSellerReplacesTheContent() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        Map<String, Object> changed = listingBody();
        changed.put("title", "iPhone 13 (price dropped)");
        changed.put("price", 79000);
        changed.put("priceType", "FIXED");
        changed.put("condition", "GOOD");
        changed.remove("locality");

        call(put(LISTINGS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed)), sita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("iPhone 13 (price dropped)"))
                .andExpect(jsonPath("$.price").value(79000.0))
                .andExpect(jsonPath("$.priceType").value("FIXED"))
                .andExpect(jsonPath("$.condition").value("GOOD"))
                .andExpect(jsonPath("$.locality").doesNotExist())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void editingNeedsASignInAndOwnership() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String live = publishedListing(sita);
        String draft = createDraft(sita);
        String changed = json.writeValueAsString(listingBody("title", "Hijacked title"));

        call(put(LISTINGS + "/" + live).contentType(MediaType.APPLICATION_JSON).content(changed), null)
                .andExpect(status().isUnauthorized());
        // A live listing is visible to Ram, so he is told he may not change it ...
        call(put(LISTINGS + "/" + live).contentType(MediaType.APPLICATION_JSON).content(changed), ram)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // ... but a draft is invisible to him, so it does not even exist as far as he can tell.
        call(put(LISTINGS + "/" + draft).contentType(MediaType.APPLICATION_JSON).content(changed), ram)
                .andExpect(status().isNotFound());
        // An administrator moderates through the status, not by rewriting the seller's text.
        call(put(LISTINGS + "/" + live).contentType(MediaType.APPLICATION_JSON).content(changed), admin())
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT title FROM listings WHERE id = ?::uuid", String.class, live))
                .isEqualTo("iPhone 13, 128 GB, with box");
    }

    @Test
    void editingValidatesLikeCreating() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        call(put(LISTINGS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(listingBody("title", "x"))), sita)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void aSoldListingCannotBeEdited() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        setStatus(sita, id, "SOLD").andExpect(status().isOk());

        call(put(LISTINGS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(listingBody())), sita)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.detail").value("A listing that is sold cannot be edited."));
    }

    @Test
    void aRejectedListingMustGoBackToDraftBeforeItCanBeEdited() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        setStatus(admin(), id, "REJECTED", "Photos show a different item").andExpect(status().isOk());

        call(put(LISTINGS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(listingBody())), sita)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("A listing that is rejected cannot be edited. Move it back to draft first."));
    }

    // --- status moves ------------------------------------------------------------------------------------------------

    @Test
    void publishingNeedsAPhoto() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        setStatus(sita, id, "ACTIVE")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.detail").value("Add at least one photo before publishing the listing."));
        assertThat(statusInDatabase(id)).isEqualTo("DRAFT");

        addPhotos(sita, id, 1);
        setStatus(sita, id, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.allowedStatuses", hasItem("SOLD")));
    }

    @Test
    void aListingGoesThroughItsWholeLife() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);

        setStatus(sita, id, "RESERVED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESERVED"));
        // A reserved listing is still public.
        call(get(LISTINGS + "/" + id), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESERVED"));
        String publishedAt = JsonPath.read(
                call(get(LISTINGS + "/" + id), sita).andReturn().getResponse().getContentAsString(), "$.publishedAt");

        setStatus(sita, id, "ACTIVE").andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedAt").value(publishedAt));      // reserving and un-reserving keeps the posting date
        setStatus(sita, id, "SOLD").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"))
                .andExpect(jsonPath("$.soldAt").isNotEmpty());
        // A sold listing is no longer shown to the public.
        call(get(LISTINGS + "/" + id), null).andExpect(status().isNotFound());
        setStatus(sita, id, "ARCHIVED").andExpect(status().isOk());
        setStatus(sita, id, "DRAFT").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        setStatus(sita, id, "ACTIVE").andExpect(status().isOk());      // the photo is still there
    }

    @Test
    void movesTheStateMachineDoesNotAllowAreRefused() throws Exception {
        Member sita = member("sita");
        String draft = createDraft(sita);
        String live = publishedListing(sita);

        setStatus(sita, draft, "SOLD").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("A listing that is draft cannot be moved to sold."));
        setStatus(sita, draft, "RESERVED").andExpect(status().isUnprocessableContent());
        setStatus(sita, live, "ACTIVE").andExpect(status().isUnprocessableContent());          // to itself
        setStatus(sita, live, "DRAFT").andExpect(status().isUnprocessableContent());           // a live ad cannot silently go back
        setStatus(sita, live, "PENDING_REVIEW").andExpect(status().isUnprocessableContent());  // review is switched off
        setStatus(sita, live, "REJECTED", "I reject my own ad").andExpect(status().isUnprocessableContent());   // only administrators do that

        assertThat(statusInDatabase(draft)).isEqualTo("DRAFT");
        assertThat(statusInDatabase(live)).isEqualTo("ACTIVE");
    }

    @Test
    void aMissingOrUnknownStatusIsRejectedAsInput() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(LISTINGS + "/" + id + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), sita)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        setStatus(sita, id, "FLYING").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void changingStatusNeedsASignInAndOwnership() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String live = publishedListing(sita);
        String draft = createDraft(sita);

        setStatus(null, live, "SOLD").andExpect(status().isUnauthorized());
        setStatus(ram, live, "SOLD").andExpect(status().isForbidden());
        setStatus(ram, draft, "ACTIVE").andExpect(status().isNotFound());
        assertThat(statusInDatabase(live)).isEqualTo("ACTIVE");
    }

    @Test
    void anAdministratorRejectsWithAReasonTheSellerCanSeeAndFix() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String id = publishedListing(sita);

        setStatus(admin(), id, "REJECTED", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        setStatus(admin(), id, "REJECTED", "Photos show a different item")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Photos show a different item"));

        // Nobody else sees it, the seller sees why.
        call(get(LISTINGS + "/" + id), null).andExpect(status().isNotFound());
        call(get(LISTINGS + "/" + id), ram).andExpect(status().isNotFound());
        call(get(LISTINGS + "/" + id), sita).andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").value("Photos show a different item"))
                .andExpect(jsonPath("$.allowedStatuses", hasItem("DRAFT")));

        setStatus(sita, id, "DRAFT").andExpect(status().isOk()).andExpect(jsonPath("$.rejectionReason").doesNotExist());
        setStatus(sita, id, "ACTIVE").andExpect(status().isOk());
    }

    @Test
    void aStatusRequestFromAStrangerLeaksNothingAboutPrivateListings() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String draft = createDraft(sita);
        String unknown = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

        // The same answer for "exists but is private" and "does not exist".
        String privateAnswer = setStatus(ram, draft, "ARCHIVED").andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missingAnswer = setStatus(ram, unknown, "ARCHIVED").andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(privateAnswer, "$.code")).isEqualTo(JsonPath.<String>read(missingAnswer, "$.code"));
    }

    // --- taking down ---------------------------------------------------------------------------------------------------

    @Test
    void deletingArchivesTheListing() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);

        call(delete(LISTINGS + "/" + id), sita).andExpect(status().isNoContent());

        assertThat(statusInDatabase(id)).isEqualTo("ARCHIVED");
        call(get(LISTINGS + "/" + id), null).andExpect(status().isNotFound());
        call(get(LISTINGS + "/" + id), sita).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        // Repeating it is fine.
        call(delete(LISTINGS + "/" + id), sita).andExpect(status().isNoContent());
        // And it can be brought back.
        setStatus(sita, id, "DRAFT").andExpect(status().isOk());
    }

    @Test
    void deletingNeedsASignInAndOwnershipButAdministratorsMayRemoveAnything() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String live = publishedListing(sita);
        String draft = createDraft(sita);

        call(delete(LISTINGS + "/" + live), null).andExpect(status().isUnauthorized());
        call(delete(LISTINGS + "/" + live), ram).andExpect(status().isForbidden());
        call(delete(LISTINGS + "/" + draft), ram).andExpect(status().isNotFound());
        assertThat(statusInDatabase(live)).isEqualTo("ACTIVE");

        call(delete(LISTINGS + "/" + live), admin()).andExpect(status().isNoContent());
        assertThat(statusInDatabase(live)).isEqualTo("ARCHIVED");
        call(delete(LISTINGS + "/7c9e6679-7425-40de-944b-e07fc1f90ae7"), sita).andExpect(status().isNotFound());
    }

    // --- lists -------------------------------------------------------------------------------------------------------

    @Test
    void myListingsAreMyOwnNewestFirstWithoutArchivedOnes() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String first = createDraft(sita, listingBody("title", "First ad of Sita"));
        String second = publishedListingTitled(sita, "Second ad of Sita");
        String archived = createDraft(sita, listingBody("title", "Archived ad of Sita"));
        call(delete(LISTINGS + "/" + archived), sita).andExpect(status().isNoContent());
        createDraft(ram, listingBody("title", "Ad of Ram"));

        call(get(LISTINGS + "/mine"), sita)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first))
                .andExpect(jsonPath("$.content[0].primaryImage.url").isNotEmpty())
                .andExpect(jsonPath("$.content[1].primaryImage").doesNotExist())
                .andExpect(jsonPath("$.content[0].category.name").value("Mobiles & Tablets"))
                .andExpect(jsonPath("$.content[0].location.district").value("Kathmandu"));
    }

    @Test
    void myListingsCanBeFilteredByStatus() throws Exception {
        Member sita = member("sita");
        String draft = createDraft(sita);
        publishedListing(sita);
        String archived = createDraft(sita);
        call(delete(LISTINGS + "/" + archived), sita).andExpect(status().isNoContent());

        call(get(LISTINGS + "/mine?status=DRAFT"), sita)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(draft));
        call(get(LISTINGS + "/mine?status=ACTIVE"), sita).andExpect(jsonPath("$.totalElements").value(1));
        call(get(LISTINGS + "/mine?status=ARCHIVED"), sita)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(archived));
        call(get(LISTINGS + "/mine?status=SOLD"), sita).andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.content", hasSize(0)));
        call(get(LISTINGS + "/mine?status=NONSENSE"), sita).andExpect(status().isBadRequest());
    }

    @Test
    void myListingsArePaginatedAndTheClientCannotChooseTheSortOrOversizedPages() throws Exception {
        Member sita = member("sita");
        for (int i = 0; i < 3; i++) {
            createDraft(sita, listingBody("title", "Draft number " + i));
        }

        call(get(LISTINGS + "/mine?size=2"), sita)
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.content[0].title").value("Draft number 2"));
        call(get(LISTINGS + "/mine?size=2&page=1"), sita)
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.last").value(true));
        // An arbitrary sort expression is ignored instead of reaching the database as a column name.
        call(get(LISTINGS + "/mine?sort=password_hash,asc&sort=nonsense"), sita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Draft number 2"));
        call(get(LISTINGS + "/mine?size=100000"), sita).andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void myListingsNeedASignIn() throws Exception {
        call(get(LISTINGS + "/mine"), null).andExpect(status().isUnauthorized());
    }

    @Test
    void aSellersPageShowsOnlyTheirLiveListings() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String live = publishedListingTitled(sita, "Live ad of Sita");
        String reserved = publishedListingTitled(sita, "Reserved ad of Sita");
        setStatus(sita, reserved, "RESERVED").andExpect(status().isOk());
        String sold = publishedListingTitled(sita, "Sold ad of Sita");
        setStatus(sita, sold, "SOLD").andExpect(status().isOk());
        createDraft(sita, listingBody("title", "Draft of Sita"));
        publishedListingTitled(ram, "Live ad of Ram");

        call(get("/api/v1/users/" + sita.id() + "/listings"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id", hasItem(live)))
                .andExpect(jsonPath("$.content[*].id", hasItem(reserved)))
                .andExpect(jsonPath("$.content[*].status", not(hasItem("SOLD"))))
                .andExpect(jsonPath("$.content[*].status", not(hasItem("DRAFT"))));
    }

    @Test
    void aSellersPageIsNotFoundForAnUnknownOrSuspendedMember() throws Exception {
        Member sita = member("sita");
        publishedListing(sita);

        call(get("/api/v1/users/7c9e6679-7425-40de-944b-e07fc1f90ae7/listings"), null).andExpect(status().isNotFound());
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?::uuid", sita.id());
        call(get("/api/v1/users/" + sita.id() + "/listings"), null).andExpect(status().isNotFound());
    }

    @Test
    void similarListingsAreTheLiveOnesOfTheSameCategoryBestMatchFirst() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String base = publishedListingTitled(sita, "The listing itself");
        String sameSubcategory = publishedListingTitled(ram, "Same subcategory");
        Integer tablets = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'tablets'", Integer.class);
        String otherSubcategory = publishedListing(ram, listingBody("title", "Other subcategory", "subcategoryId", tablets));
        Integer laptops = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'computers-laptops'", Integer.class);
        Integer laptopType = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'laptops'", Integer.class);
        publishedListing(ram, listingBody("title", "Other category", "categoryId", laptops, "subcategoryId", laptopType));
        createDraft(ram, listingBody("title", "A draft"));

        call(get(LISTINGS + "/" + base + "/similar"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(sameSubcategory))
                .andExpect(jsonPath("$[1].id").value(otherSubcategory))
                .andExpect(jsonPath("$[*].id", not(hasItem(base))));
        call(get(LISTINGS + "/" + base + "/similar?limit=1"), null).andExpect(jsonPath("$", hasSize(1)));
        call(get(LISTINGS + "/" + base + "/similar?limit=0"), null).andExpect(jsonPath("$", hasSize(1)));        // kept at 1 or more
        call(get(LISTINGS + "/" + base + "/similar?limit=999"), null).andExpect(status().isOk());              // kept at 24 or less
    }

    @Test
    void similarListingsOfAPrivateListingAreNotFound() throws Exception {
        Member sita = member("sita");
        String draft = createDraft(sita);

        call(get(LISTINGS + "/" + draft + "/similar"), null).andExpect(status().isNotFound());
        call(get(LISTINGS + "/" + draft + "/similar"), sita).andExpect(status().isOk());
    }

    // --- tokens ------------------------------------------------------------------------------------------------------

    @Test
    void aBrokenTokenIsRefusedEvenOnAPublicRead() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        Member forged = new Member("x", sita.id(), "Bearer " + com.resalemarket.support.TestTokens.valid(
                com.resalemarket.support.TestTokens.encoderWithAnotherKey(), sita.id(), "ROLE_USER"));

        call(get(LISTINGS + "/" + id), forged).andExpect(status().isUnauthorized());
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private String publishedListingTitled(Member seller, String title) throws Exception {
        return publishedListing(seller, listingBody("title", title));
    }

    private String publishedListing(Member seller, Map<String, Object> body) throws Exception {
        String id = createDraft(seller, body);
        addPhotos(seller, id, 1);
        setStatus(seller, id, "ACTIVE").andExpect(status().isOk());
        return id;
    }

    private Map<String, Object> listingBody(String key, Object value, String key2, Object value2) {
        Map<String, Object> body = new LinkedHashMap<>(listingBody(key, value));
        body.put(key2, value2);
        return body;
    }

    private Map<String, Object> listingBody(String key, Object value, String key2, Object value2, String key3, Object value3) {
        Map<String, Object> body = listingBody(key, value, key2, value2);
        body.put(key3, value3);
        return body;
    }

    private long views(String listingId) {
        return jdbc.queryForObject("SELECT view_count FROM listings WHERE id = ?::uuid", Long.class, listingId);
    }
}
