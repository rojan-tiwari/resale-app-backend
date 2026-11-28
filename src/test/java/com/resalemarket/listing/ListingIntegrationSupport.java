package com.resalemarket.listing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.resalemarket.common.storage.StorageService;
import com.resalemarket.support.TestAccounts;
import com.resalemarket.support.TestImages;
import com.resalemarket.support.TestTokens;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every listing integration test needs: real accounts that sign in, valid listing bodies built from the seeded categories and
 * places, calls with or without a token, and cleanup. The requests commit, so every account lives in a test-only domain and is removed
 * (with its listings, photos and stored files) after each test.
 */
abstract class ListingIntegrationSupport {

    static final String DOMAIN = "listing-itest.example";
    static final String LISTINGS = "/api/v1/listings";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected JwtEncoder encoder;

    @Autowired
    protected StorageService storage;

    /** A signed-in person: the {@code Authorization} header value and the id the server knows them by. */
    protected record Member(String email, String id, String authorization) {
    }

    @AfterEach
    void removeTestData() {
        String pattern = "%@" + DOMAIN;
        List<String> keys = jdbc.queryForList(
                "SELECT i.storage_key FROM listing_images i JOIN listings l ON l.id = i.listing_id JOIN users u ON u.id = l.seller_id "
                        + "WHERE u.email LIKE ?",
                String.class, pattern);
        keys.forEach(storage::delete);
        jdbc.update("DELETE FROM listings WHERE seller_id IN (SELECT id FROM users WHERE email LIKE ?)", pattern);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", pattern);
    }

    // --- people ------------------------------------------------------------------------------------------------------

    protected Member member(String name) throws Exception {
        String email = name + "@" + DOMAIN;
        TestAccounts.register(mockMvc, json, email, TestAccounts.PASSWORD, name);
        var login = TestAccounts.signIn(mockMvc, json, email, TestAccounts.PASSWORD);
        return new Member(email, TestAccounts.userIdOf(login), TestAccounts.bearer(login));
    }

    /** An administrator. Phase 6 creates the first real one; until then a correctly signed token with the role is what the API sees. */
    protected Member admin() {
        String id = UUID.randomUUID().toString();
        return new Member("admin", id, "Bearer " + TestTokens.valid(encoder, id, "ROLE_USER", "ROLE_ADMIN"));
    }

    // --- bodies ------------------------------------------------------------------------------------------------------

    /** A valid listing: a phone in Mobiles & Tablets, in Kathmandu. */
    protected Map<String, Object> listingBody() {
        Integer category = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'mobiles-tablets'", Integer.class);
        Integer subcategory = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'mobile-phones'", Integer.class);
        Integer district = jdbc.queryForObject("SELECT id FROM districts WHERE name = 'Kathmandu'", Integer.class);
        Integer municipality = jdbc.queryForObject(
                "SELECT id FROM municipalities WHERE name = 'Kathmandu' AND district_id = ?", Integer.class, district);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "iPhone 13, 128 GB, with box");
        body.put("description", "Bought last year, battery health 91%. No scratches, original charger included.");
        body.put("price", 85000);
        body.put("priceType", "NEGOTIABLE");
        body.put("condition", "LIKE_NEW");
        body.put("categoryId", category);
        body.put("subcategoryId", subcategory);
        body.put("provinceId", 3);
        body.put("districtId", district);
        body.put("municipalityId", municipality);
        body.put("locality", "Baneshwor");
        return body;
    }

    protected Map<String, Object> listingBody(String key, Object value) {
        Map<String, Object> body = listingBody();
        body.put(key, value);
        return body;
    }

    // --- calls -------------------------------------------------------------------------------------------------------

    protected ResultActions call(AbstractMockHttpServletRequestBuilder<?> request, Member who) throws Exception {
        if (who != null) {
            request.header(HttpHeaders.AUTHORIZATION, who.authorization());
        }
        return mockMvc.perform(request);
    }

    protected ResultActions postJson(String url, Member who, Object body) throws Exception {
        return call(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), who);
    }

    protected ResultActions setStatus(Member who, String listingId, String status) throws Exception {
        return setStatus(who, listingId, status, null);
    }

    protected ResultActions setStatus(Member who, String listingId, String status, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("reason", reason);
        return call(patch(LISTINGS + "/" + listingId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)), who);
    }

    protected ResultActions uploadImages(Member who, String listingId, MockMultipartFile... files) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(LISTINGS + "/" + listingId + "/images");
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return call(request, who);
    }

    /** A form part named like the real field; the content type is what a browser would claim, which the server must not trust. */
    protected static MockMultipartFile part(String fileName, String claimedType, byte[] content) {
        return new MockMultipartFile("images", fileName, claimedType, content);
    }

    protected static MockMultipartFile jpeg(String fileName) {
        return part(fileName, "image/jpeg", TestImages.jpeg(40, 30));
    }

    // --- shortcuts ---------------------------------------------------------------------------------------------------

    protected String createDraft(Member seller) throws Exception {
        return createDraft(seller, listingBody());
    }

    protected String createDraft(Member seller, Map<String, Object> body) throws Exception {
        String response = postJson(LISTINGS, seller, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    protected void addPhotos(Member seller, String listingId, int count) throws Exception {
        MockMultipartFile[] files = new MockMultipartFile[count];
        for (int i = 0; i < count; i++) {
            files[i] = jpeg("photo-" + i + ".jpg");
        }
        uploadImages(seller, listingId, files).andExpect(status().isCreated());
    }

    /** A live listing: a draft with one photo, published. */
    protected String publishedListing(Member seller) throws Exception {
        String id = createDraft(seller);
        addPhotos(seller, id, 1);
        setStatus(seller, id, "ACTIVE").andExpect(status().isOk());
        return id;
    }

    protected String statusInDatabase(String listingId) {
        return jdbc.queryForObject("SELECT status FROM listings WHERE id = ?::uuid", String.class, listingId);
    }
}
