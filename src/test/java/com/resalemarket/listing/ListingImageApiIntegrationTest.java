package com.resalemarket.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.resalemarket.support.IntegrationTest;
import com.resalemarket.support.TestImages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/** Photos end to end: what is accepted, what is refused and why, who may change them, and that files and rows never drift apart. */
@IntegrationTest
class ListingImageApiIntegrationTest extends ListingIntegrationSupport {

    @Value("${app.storage.root}")
    private Path storageRoot;

    // --- uploading ---------------------------------------------------------------------------------------------------

    @Test
    void uploadingNeedsASignIn() throws Exception {
        String id = createDraft(member("sita"));

        uploadImages(null, id, jpeg("a.jpg")).andExpect(status().isUnauthorized());
    }

    @Test
    void severalPhotosOfDifferentTypesAreStoredInOrderAndTheFirstIsTheMainPhoto() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        uploadImages(sita, id,
                        part("front.jpg", "image/jpeg", TestImages.jpeg(640, 480)),
                        part("back.png", "image/png", TestImages.png(300, 200)),
                        part("side.webp", "image/webp", TestImages.webp(800, 600)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[0].primary").value(true))
                .andExpect(jsonPath("$[0].width").value(640))
                .andExpect(jsonPath("$[0].height").value(480))
                .andExpect(jsonPath("$[1].position").value(1))
                .andExpect(jsonPath("$[1].primary").value(false))
                .andExpect(jsonPath("$[1].width").value(300))
                .andExpect(jsonPath("$[2].width").value(800))
                .andExpect(jsonPath("$[2].height").value(600));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT content_type, storage_key, size_bytes, sort_order, is_primary FROM listing_images WHERE listing_id = ?::uuid ORDER BY sort_order", id);
        assertThat(rows).extracting(row -> row.get("content_type")).containsExactly("image/jpeg", "image/png", "image/webp");
        assertThat(rows).extracting(row -> row.get("is_primary")).containsExactly(true, false, false);
        for (Map<String, Object> row : rows) {
            assertThat(storageRoot.resolve((String) row.get("storage_key"))).exists().hasSize(((Number) row.get("size_bytes")).longValue());
        }
    }

    @Test
    void laterUploadsAreAppendedAndTheMainPhotoStaysTheFirst() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 2);

        uploadImages(sita, id, jpeg("third.jpg"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[2].position").value(2))
                .andExpect(jsonPath("$[2].primary").value(false))
                .andExpect(jsonPath("$[0].primary").value(true));
    }

    @Test
    void storedNamesAreMadeUpByTheServerWhateverTheClientCallsTheFile() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        uploadImages(sita, id, part("../../etc/passwd.jpg", "image/jpeg", TestImages.jpeg(10, 10)),
                part("My Photo (1).JPG", "image/jpeg", TestImages.jpeg(10, 10)))
                .andExpect(status().isCreated());

        List<String> keys = jdbc.queryForList("SELECT storage_key FROM listing_images WHERE listing_id = ?::uuid", String.class, id);
        assertThat(keys).hasSize(2).allSatisfy(key -> assertThat(key).matches("listing-images/[0-9a-f-]{36}\\.jpg"));
    }

    @Test
    void theContentDecidesNotTheNameOrTheClaimedType() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        // A web page that calls itself a JPEG is refused ...
        uploadImages(sita, id, part("evil.jpg", "image/jpeg", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("images"))
                .andExpect(jsonPath("$.errors[0].message").value("evil.jpg is not a JPEG, PNG or WebP image."));
        // ... and a real PNG is accepted whatever it is called, and kept as a PNG.
        uploadImages(sita, id, part("holiday.exe", "application/octet-stream", TestImages.png(20, 20)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].width").value(20));
        assertThat(jdbc.queryForObject("SELECT content_type FROM listing_images WHERE listing_id = ?::uuid", String.class, id))
                .isEqualTo("image/png");
    }

    @Test
    void aRequestIsAllOrNothing() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        long filesBefore = filesInStorage();

        uploadImages(sita, id, jpeg("good.jpg"), part("bad.gif", "image/gif", "GIF89a...".getBytes(StandardCharsets.US_ASCII)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message", containsString("bad.gif")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images WHERE listing_id = ?::uuid", Integer.class, id)).isZero();
        assertThat(filesInStorage()).isEqualTo(filesBefore);
    }

    @Test
    void emptyOversizedAndMissingFilesAreRefusedWithAMessage() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        uploadImages(sita, id, part("empty.jpg", "image/jpeg", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("empty.jpg is empty."));
        uploadImages(sita, id, part("huge.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("huge.jpg is larger than 5 MB."));
        uploadImages(sita, id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("images"))
                .andExpect(jsonPath("$.errors[0].message").value("Choose at least one photo."));
    }

    @Test
    void anImageWithTooManyPixelsIsRefusedFromItsHeader() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        uploadImages(sita, id, part("giant.webp", "image/webp", TestImages.webp(12_000, 12_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message", containsString("too many pixels")));
    }

    @Test
    void aFileNameThatIsAnAttackIsShownBackHarmlessly() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        String nasty = "C:\\fakepath\\" + "x".repeat(100) + "\n<script>.txt";

        String message = uploadImages(sita, id, part(nasty, "text/plain", "plain text".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        String shown = JsonPath.read(message, "$.errors[0].message");
        assertThat(shown).doesNotContain("fakepath").doesNotContain("\n").hasSizeLessThan(140);
    }

    @Test
    void aListingHoldsAtMostEightPhotos() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);

        addPhotos(sita, id, 8);
        uploadImages(sita, id, jpeg("ninth.jpg"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.detail").value("A listing can have at most 8 photos; this one has 8."));

        String other = createDraft(sita);
        addPhotos(sita, other, 3);
        MockMultipartFile[] six = new MockMultipartFile[6];
        for (int i = 0; i < six.length; i++) {
            six[i] = jpeg("p" + i + ".jpg");
        }
        uploadImages(sita, other, six).andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images WHERE listing_id = ?::uuid", Integer.class, other)).isEqualTo(3);
    }

    @Test
    void parallelUploadsCannotExceedEight() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 6);

        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int n = i;
                results.add(pool.submit(() -> uploadImages(sita, id, jpeg("race-" + n + ".jpg"), jpeg("race-" + n + "b.jpg"))
                        .andReturn().getResponse().getStatus()));
            }
            int created = 0;
            for (var result : results) {
                int httpStatus = result.get();
                assertThat(httpStatus).isIn(201, 422);
                if (httpStatus == 201) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);     // 6 + 2 = 8; every other request would make it 10
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images WHERE listing_id = ?::uuid", Integer.class, id)).isEqualTo(8);
        assertThat(jdbc.queryForList("SELECT sort_order FROM listing_images WHERE listing_id = ?::uuid ORDER BY sort_order", Integer.class, id))
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void onlyTheSellerMayUpload() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String live = publishedListing(sita);
        String draft = createDraft(sita);

        uploadImages(ram, live, jpeg("a.jpg")).andExpect(status().isForbidden());
        uploadImages(ram, draft, jpeg("a.jpg")).andExpect(status().isNotFound());
        uploadImages(admin(), live, jpeg("a.jpg")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images WHERE listing_id = ?::uuid", Integer.class, live)).isEqualTo(1);
    }

    @Test
    void photosOfASoldOrRemovedListingCannotBeChanged() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        setStatus(sita, id, "SOLD").andExpect(status().isOk());

        uploadImages(sita, id, jpeg("late.jpg"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("The photos of a listing that is sold cannot be changed."));
        String imageId = firstImageId(id);
        call(delete("/api/v1/listings/" + id + "/images/" + imageId), sita).andExpect(status().isUnprocessableContent());
    }

    // --- showing -----------------------------------------------------------------------------------------------------

    @Test
    void thePictureIsPublicAndServedWithItsRealTypeAndSafeHeaders() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        byte[] original = TestImages.png(30, 20);
        uploadImages(sita, id, part("p.png", "image/png", original)).andExpect(status().isCreated());
        String imageId = firstImageId(id);

        // Even a draft's picture loads without a token: an img tag cannot send one.
        mockMvc.perform(get("/api/v1/listings/" + id + "/images/" + imageId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(original))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("sandbox")))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("Cache-Control", containsString("max-age=86400")));
    }

    @Test
    void theUrlInTheGalleryLoadsThePicture() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        String response = uploadImages(sita, id, jpeg("a.jpg")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String url = JsonPath.read(response, "$[0].url");

        assertThat(url).startsWith("/api/v1/listings/" + id + "/images/");
        mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    void aPictureIsOnlyFoundUnderItsOwnListingAndWhenItExists() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        String other = createDraft(sita);
        addPhotos(sita, id, 1);
        String imageId = firstImageId(id);

        mockMvc.perform(get("/api/v1/listings/" + other + "/images/" + imageId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/listings/" + id + "/images/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/listings/" + id + "/images/not-a-uuid")).andExpect(status().isUnauthorized());
    }

    @Test
    void aPictureWhoseFileHasGoneMissingIsNotFoundNotAServerError() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 1);
        String imageId = firstImageId(id);
        String key = jdbc.queryForObject("SELECT storage_key FROM listing_images WHERE id = ?::uuid", String.class, imageId);
        storage.delete(key);

        mockMvc.perform(get("/api/v1/listings/" + id + "/images/" + imageId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- removing ----------------------------------------------------------------------------------------------------

    @Test
    void removingAPhotoDeletesItsFileAndClosesTheGap() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 3);
        List<String> ids = imageIds(id);
        String middleKey = keyOf(ids.get(1));

        call(delete("/api/v1/listings/" + id + "/images/" + ids.get(1)), sita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(ids.get(0)))
                .andExpect(jsonPath("$[1].id").value(ids.get(2)))
                .andExpect(jsonPath("$[1].position").value(1));

        assertThat(storageRoot.resolve(middleKey)).doesNotExist();
        assertThat(jdbc.queryForList("SELECT sort_order FROM listing_images WHERE listing_id = ?::uuid ORDER BY sort_order", Integer.class, id))
                .containsExactly(0, 1);
    }

    @Test
    void removingTheMainPhotoPromotesTheNextOne() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 3);
        List<String> ids = imageIds(id);

        call(delete("/api/v1/listings/" + id + "/images/" + ids.get(0)), sita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ids.get(1)))
                .andExpect(jsonPath("$[0].primary").value(true))
                .andExpect(jsonPath("$[1].primary").value(false));
        assertThat(primaryCount(id)).isEqualTo(1);
    }

    @Test
    void aLiveListingKeepsAtLeastOnePhoto() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        String imageId = firstImageId(id);

        call(delete("/api/v1/listings/" + id + "/images/" + imageId), sita)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images WHERE listing_id = ?::uuid", Integer.class, id)).isEqualTo(1);
        assertThat(storageRoot.resolve(keyOf(imageId))).exists();
    }

    @Test
    void aDraftMayLoseItsLastPhoto() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 1);

        call(delete("/api/v1/listings/" + id + "/images/" + firstImageId(id)), sita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void removingNeedsOwnershipAndAnExistingPhoto() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String live = publishedListing(sita);
        String draft = createDraft(sita);
        addPhotos(sita, draft, 1);

        call(delete("/api/v1/listings/" + live + "/images/" + firstImageId(live)), null).andExpect(status().isUnauthorized());
        call(delete("/api/v1/listings/" + live + "/images/" + firstImageId(live)), ram).andExpect(status().isForbidden());
        call(delete("/api/v1/listings/" + draft + "/images/" + firstImageId(draft)), ram).andExpect(status().isNotFound());
        call(delete("/api/v1/listings/" + draft + "/images/" + UUID.randomUUID()), sita).andExpect(status().isNotFound());
        // A photo of another listing is not found under this one, even for its real owner.
        call(delete("/api/v1/listings/" + draft + "/images/" + firstImageId(live)), sita).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images", Integer.class)).isGreaterThanOrEqualTo(2);
    }

    // --- ordering ----------------------------------------------------------------------------------------------------

    @Test
    void reorderingPutsTheFirstPhotoInFrontAsTheMainPhoto() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 4);
        List<String> ids = imageIds(id);

        call(put("/api/v1/listings/" + id + "/images/order").contentType(MediaType.APPLICATION_JSON)
                .content(order(ids.get(2), ids.get(0), ids.get(3), ids.get(1))), sita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ids.get(2)))
                .andExpect(jsonPath("$[0].primary").value(true))
                .andExpect(jsonPath("$[1].id").value(ids.get(0)))
                .andExpect(jsonPath("$[1].primary").value(false))
                .andExpect(jsonPath("$[2].id").value(ids.get(3)))
                .andExpect(jsonPath("$[3].id").value(ids.get(1)))
                .andExpect(jsonPath("$[3].position").value(3));

        assertThat(imageIds(id)).containsExactly(ids.get(2), ids.get(0), ids.get(3), ids.get(1));
        assertThat(primaryCount(id)).isEqualTo(1);
        // The card shows the new main photo.
        call(get(LISTINGS + "/mine"), sita)
                .andExpect(jsonPath("$.content[0].primaryImage.id").value(ids.get(2)));
    }

    @Test
    void reorderingWithoutChangingTheFirstPhotoKeepsOneMainPhoto() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 3);
        List<String> ids = imageIds(id);

        call(put("/api/v1/listings/" + id + "/images/order").contentType(MediaType.APPLICATION_JSON)
                .content(order(ids.get(0), ids.get(2), ids.get(1))), sita)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ids.get(0)))
                .andExpect(jsonPath("$[0].primary").value(true));
        assertThat(primaryCount(id)).isEqualTo(1);
    }

    @Test
    void theNewOrderMustListEveryPhotoExactlyOnce() throws Exception {
        Member sita = member("sita");
        String id = createDraft(sita);
        addPhotos(sita, id, 3);
        List<String> ids = imageIds(id);
        String foreign = UUID.randomUUID().toString();

        for (String body : List.of(
                order(ids.get(0), ids.get(1)),                              // one missing
                order(ids.get(0), ids.get(1), ids.get(1)),                  // one twice
                order(ids.get(0), ids.get(1), foreign),                     // a photo that is not this listing's
                order(ids.get(0), ids.get(1), ids.get(2), foreign),         // too many
                "{\"imageIds\":[]}",
                "{}")) {
            call(put("/api/v1/listings/" + id + "/images/order").contentType(MediaType.APPLICATION_JSON).content(body), sita)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("imageIds"));
        }
        assertThat(imageIds(id)).containsExactlyElementsOf(ids);
    }

    @Test
    void reorderingNeedsOwnership() throws Exception {
        Member sita = member("sita");
        Member ram = member("ram");
        String live = publishedListing(sita);
        String draft = createDraft(sita);
        addPhotos(sita, draft, 2);
        String liveBody = order(firstImageId(live));
        List<String> draftIds = imageIds(draft);

        call(put("/api/v1/listings/" + live + "/images/order").contentType(MediaType.APPLICATION_JSON).content(liveBody), null)
                .andExpect(status().isUnauthorized());
        call(put("/api/v1/listings/" + live + "/images/order").contentType(MediaType.APPLICATION_JSON).content(liveBody), ram)
                .andExpect(status().isForbidden());
        call(put("/api/v1/listings/" + draft + "/images/order").contentType(MediaType.APPLICATION_JSON)
                .content(order(draftIds.get(1), draftIds.get(0))), ram)
                .andExpect(status().isNotFound());
        assertThat(imageIds(draft)).containsExactlyElementsOf(draftIds);
    }

    // --- the card ----------------------------------------------------------------------------------------------------

    @Test
    void theDetailListsTheGalleryInOrder() throws Exception {
        Member sita = member("sita");
        String id = publishedListing(sita);
        addPhotos(sita, id, 2);

        call(get(LISTINGS + "/" + id), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images", hasSize(3)))
                .andExpect(jsonPath("$.images[0].primary").value(true))
                .andExpect(jsonPath("$.images[2].position").value(2));
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private String order(String... ids) throws Exception {
        return json.writeValueAsString(Map.of("imageIds", List.of(ids)));
    }

    private List<String> imageIds(String listingId) {
        return jdbc.queryForList("SELECT id::text FROM listing_images WHERE listing_id = ?::uuid ORDER BY sort_order", String.class, listingId);
    }

    private String firstImageId(String listingId) {
        return imageIds(listingId).get(0);
    }

    private String keyOf(String imageId) {
        return jdbc.queryForObject("SELECT storage_key FROM listing_images WHERE id = ?::uuid", String.class, imageId);
    }

    private int primaryCount(String listingId) {
        return jdbc.queryForObject("SELECT count(*) FROM listing_images WHERE listing_id = ?::uuid AND is_primary", Integer.class, listingId);
    }

    private long filesInStorage() throws IOException {
        if (!Files.exists(storageRoot)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(storageRoot)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
