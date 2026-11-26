package com.resalemarket.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The approval switch ({@code app.listing.require-review=true}): a seller's listing waits for an administrator instead of going
 * live. It needs its own application context, because the setting is read at startup.
 */
@IntegrationTest
@TestPropertySource(properties = "app.listing.require-review=true")
class ListingReviewIntegrationTest extends ListingIntegrationSupport {

    @Test
    void aDraftCanGoToReviewButNotStraightToLive() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 1);

        call(get(LISTINGS + "/" + id), sita)
                .andExpect(jsonPath("$.allowedStatuses", hasItem("PENDING_REVIEW")))
                .andExpect(jsonPath("$.allowedStatuses", not(hasItem("ACTIVE"))));
        setStatus(sita, id, "ACTIVE").andExpect(status().isUnprocessableContent());
        setStatus(sita, id, "PENDING_REVIEW")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.publishedAt").doesNotExist());
    }

    @Test
    void reviewNeedsAPhotoToo() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        setStatus(sita, id, "PENDING_REVIEW")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Add at least one photo before publishing the listing."));
    }

    @Test
    void aListingUnderReviewIsHiddenFromThePublicAndFrozen() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String id = inReview(sita);

        call(get(LISTINGS + "/" + id), null).andExpect(status().isNotFound());
        call(get(LISTINGS + "/" + id), ram).andExpect(status().isNotFound());
        call(put(LISTINGS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(listingBody())), sita)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("A listing that is pending review cannot be edited. Move it back to draft first."));
        uploadImages(sita, id, jpeg("late.jpg")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void theSellerCannotApproveTheirOwnListing() throws Exception {
        Member sita = member("sita");
        String id = inReview(sita);

        setStatus(sita, id, "ACTIVE").andExpect(status().isUnprocessableContent());
        setStatus(sita, id, "REJECTED", "Looks fine to me").andExpect(status().isUnprocessableContent());
        assertThat(statusInDatabase(id)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void anAdministratorApprovesAndTheListingGoesLive() throws Exception {
        Member sita = member("sita");
        String id = inReview(sita);

        setStatus(admin(), id, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());
        call(get(LISTINGS + "/" + id), null).andExpect(status().isOk());
    }

    @Test
    void anAdministratorRejectsAndTheSellerFixesAndResubmits() throws Exception {
        Member sita = member("sita");
        String id = inReview(sita);

        setStatus(admin(), id, "REJECTED", "The photo is not of the item")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").value("The photo is not of the item"));
        setStatus(sita, id, "DRAFT").andExpect(status().isOk());
        call(put(LISTINGS + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(listingBody("title", "Fixed title"))), sita)
                .andExpect(status().isOk());
        setStatus(sita, id, "PENDING_REVIEW").andExpect(status().isOk());
    }

    @Test
    void theSellerCanWithdrawFromReview() throws Exception {
        Member sita = member("sita");
        String id = inReview(sita);

        setStatus(sita, id, "DRAFT").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    private String inReview(Member seller) throws Exception {
        String id = createDraft(seller);
        addPhotos(seller, id, 1);
        setStatus(seller, id, "PENDING_REVIEW").andExpect(status().isOk());
        return id;
    }
}
