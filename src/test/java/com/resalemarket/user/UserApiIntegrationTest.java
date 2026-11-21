package com.resalemarket.user;

import static com.resalemarket.support.TestAccounts.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.support.IntegrationTest;
import com.resalemarket.support.TestAccounts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Own profile, public profiles and changing the password end to end against PostgreSQL. The requests commit, so every
 * account lives in a test-only domain and is removed afterwards.
 */
@IntegrationTest
class UserApiIntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String MY_PASSWORD = "/api/v1/users/me/password";
    private static final String DOMAIN = "user-itest.example";
    private static final String NEW_PASSWORD = "a brand new password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @AfterEach
    void removeTestUsers() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%@" + DOMAIN);
    }

    // --- own profile ------------------------------------------------------------------------------------------------

    @Test
    void ownProfileNeedsASignIn() throws Exception {
        mockMvc.perform(get(ME)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(ME).contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Sita\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownProfileIsTheTokensAccountAndNobodyElses() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");
        MvcResult ram = signedIn("ram@" + DOMAIN, "Ram Thapa");

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(sita)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").value(TestAccounts.userIdOf(sita)))
                .andExpect(jsonPath("$.email").value("sita@" + DOMAIN))
                .andExpect(jsonPath("$.displayName").value("Sita Sharma"))
                .andExpect(jsonPath("$.showPhone").value(false))
                .andExpect(jsonPath("$.bio").isEmpty())
                .andExpect(jsonPath("$.provinceId").isEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(ram)))
                .andExpect(jsonPath("$.email").value("ram@" + DOMAIN));
    }

    @Test
    void savingTheProfileReplacesItAndTouchesOnlyTheCallersRows() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");
        signedIn("ram@" + DOMAIN, "Ram Thapa");
        Place place = aPlace();

        saveProfile(sita, profile("  Sita S. Sharma ", "9812345678", true, "I sell books", place.provinceId, place.districtId, place.municipalityId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.displayName").value("Sita S. Sharma"))
                .andExpect(jsonPath("$.phone").value("+9779812345678"))
                .andExpect(jsonPath("$.showPhone").value(true))
                .andExpect(jsonPath("$.bio").value("I sell books"))
                .andExpect(jsonPath("$.provinceId").value(place.provinceId))
                .andExpect(jsonPath("$.districtId").value(place.districtId))
                .andExpect(jsonPath("$.municipalityId").value(place.municipalityId));

        Map<String, Object> row = profileRow("sita@" + DOMAIN);
        assertThat(row.get("display_name")).isEqualTo("Sita S. Sharma");
        assertThat(row.get("phone")).isEqualTo("+9779812345678");
        assertThat(row.get("show_phone")).isEqualTo(true);
        assertThat(row.get("bio")).isEqualTo("I sell books");
        assertThat(row.get("province_id")).isEqualTo(place.provinceId);
        assertThat(row.get("municipality_id")).isEqualTo(place.municipalityId);
        // The other account is untouched.
        Map<String, Object> other = profileRow("ram@" + DOMAIN);
        assertThat(other.get("display_name")).isEqualTo("Ram Thapa");
        assertThat(other.get("phone")).isNull();
        assertThat(other.get("bio")).isNull();
    }

    @Test
    void anOptionalFieldThatIsLeftOutIsCleared() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");
        Place place = aPlace();
        saveProfile(sita, profile("Sita Sharma", "9812345678", true, "bio", place.provinceId, place.districtId, place.municipalityId))
                .andExpect(status().isOk());

        // No phone, no consent flag, no bio, no place at all.
        saveProfile(sita, "{\"displayName\":\"Sita Sharma\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").isEmpty())
                .andExpect(jsonPath("$.showPhone").value(false))
                .andExpect(jsonPath("$.bio").isEmpty())
                .andExpect(jsonPath("$.provinceId").isEmpty());

        Map<String, Object> row = profileRow("sita@" + DOMAIN);
        assertThat(row.get("phone")).isNull();
        assertThat(row.get("show_phone")).isEqualTo(false);
        assertThat(row.get("bio")).isNull();
        assertThat(row.get("province_id")).isNull();
        assertThat(row.get("district_id")).isNull();
        assertThat(row.get("municipality_id")).isNull();
    }

    @Test
    void aDistrictOutsideTheChosenProvinceIsRefusedAndNothingChanges() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");
        Place place = aPlace();
        int anotherProvince = place.provinceId % 7 + 1;

        saveProfile(sita, profile("New Name", null, false, null, anotherProvince, place.districtId, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("districtId"));

        assertThat(profileRow("sita@" + DOMAIN).get("display_name")).isEqualTo("Sita Sharma");
    }

    @Test
    void aMunicipalityNeedsADistrictAndADistrictNeedsAProvince() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");
        Place place = aPlace();

        saveProfile(sita, profile("Sita", null, false, null, null, place.districtId, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("provinceId"));
        saveProfile(sita, profile("Sita", null, false, null, place.provinceId, null, place.municipalityId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("districtId"));
    }

    @Test
    void thePhoneCannotBeShownWhenThereIsNone() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");

        saveProfile(sita, profile("Sita", null, true, null, null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("showPhone"));
    }

    @Test
    void invalidInputIsAValidationErrorListingEveryField() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");

        saveProfile(sita, profile("A", "12345", false, "b".repeat(501), null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void aSuspendedAccountWithAStillValidTokenIsTreatedAsSignedOut() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", "sita@" + DOMAIN);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(sita)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        saveProfile(sita, "{\"displayName\":\"Still Me\"}").andExpect(status().isUnauthorized());
        assertThat(profileRow("sita@" + DOMAIN).get("display_name")).isEqualTo("Sita Sharma");
    }

    // --- public profile ---------------------------------------------------------------------------------------------

    @Test
    void thePublicProfileNeedsNoSignInAndNeverShowsTheEmail() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");
        Place place = aPlace();
        saveProfile(sita, profile("Sita Sharma", "9812345678", false, "I sell books", place.provinceId, place.districtId, place.municipalityId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/" + TestAccounts.userIdOf(sita)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TestAccounts.userIdOf(sita)))
                .andExpect(jsonPath("$.displayName").value("Sita Sharma"))
                .andExpect(jsonPath("$.bio").value("I sell books"))
                .andExpect(jsonPath("$.memberSince").isNotEmpty())
                .andExpect(jsonPath("$.location.province").value(place.provinceName))
                .andExpect(jsonPath("$.location.district").value(place.districtName))
                .andExpect(jsonPath("$.location.municipality").value(place.municipalityName))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void thePhoneNumberIsPublicOnlyWhileItsOwnerAllowsIt() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");
        String publicUrl = "/api/v1/users/" + TestAccounts.userIdOf(sita);

        // A number on file, but no consent.
        saveProfile(sita, profile("Sita Sharma", "9812345678", false, null, null, null, null)).andExpect(status().isOk());
        mockMvc.perform(get(publicUrl)).andExpect(jsonPath("$.phone").isEmpty());

        saveProfile(sita, profile("Sita Sharma", "9812345678", true, null, null, null, null)).andExpect(status().isOk());
        mockMvc.perform(get(publicUrl)).andExpect(jsonPath("$.phone").value("+9779812345678"));

        saveProfile(sita, profile("Sita Sharma", "9812345678", false, null, null, null, null)).andExpect(status().isOk());
        mockMvc.perform(get(publicUrl)).andExpect(jsonPath("$.phone").isEmpty());
    }

    @Test
    void anUnknownSuspendedOrDeletedMemberLooksTheSame() throws Exception {
        MvcResult suspended = signedIn("suspended@" + DOMAIN, "Suspended Person");
        MvcResult deleted = signedIn("deleted@" + DOMAIN, "Deleted Person");
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", "suspended@" + DOMAIN);
        jdbc.update("UPDATE users SET status = 'DELETED' WHERE email = ?", "deleted@" + DOMAIN);

        for (String id : new String[] {TestAccounts.userIdOf(suspended), TestAccounts.userIdOf(deleted), UUID.randomUUID().toString()}) {
            mockMvc.perform(get("/api/v1/users/" + id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").value("User with id '" + id + "' was not found."));
        }
    }

    @Test
    void aMalformedIdIsNotAPublicProfile() throws Exception {
        mockMvc.perform(get("/api/v1/users/not-a-uuid")).andExpect(status().isUnauthorized());
    }

    // --- changing the password --------------------------------------------------------------------------------------

    @Test
    void changingThePasswordNeedsASignIn() throws Exception {
        mockMvc.perform(put(MY_PASSWORD).contentType(MediaType.APPLICATION_JSON)
                        .content(passwords(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theNewPasswordWorksTheOldOneDoesNotAndEverySessionEnds() throws Exception {
        String email = "change@" + DOMAIN;
        MvcResult phone = signedIn(email, "Change Test");
        MvcResult laptop = TestAccounts.signIn(mockMvc, json, email, PASSWORD);

        changePassword(phone, PASSWORD, NEW_PASSWORD)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=;")));

        for (MvcResult device : new MvcResult[] {phone, laptop}) {
            String refreshToken = TestAccounts.refreshTokenOf(device);
            TestAccounts.refresh(mockMvc, refreshToken).andExpect(status().isUnauthorized());
            assertThat(jdbc.queryForObject("SELECT revoked_reason FROM refresh_tokens WHERE token_hash = ?", String.class,
                            sha256Hex(refreshToken)))
                    .isEqualTo("PASSWORD_CHANGED");
        }
        TestAccounts.login(mockMvc, json, email, NEW_PASSWORD).andExpect(status().isOk());
        TestAccounts.login(mockMvc, json, email, PASSWORD).andExpect(status().isUnauthorized());
        assertThat(passwordHash(email)).startsWith("{bcrypt}$2").isNotEqualTo(NEW_PASSWORD);
    }

    @Test
    void aWrongCurrentPasswordIsAFieldErrorAndChangesNothing() throws Exception {
        String email = "wrong@" + DOMAIN;
        MvcResult sita = signedIn(email, "Wrong Current");
        String before = passwordHash(email);

        changePassword(sita, "not my password", NEW_PASSWORD)
                .andExpect(status().isBadRequest())   // never a 401: that would make the web app try to renew its token
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"));

        assertThat(passwordHash(email)).isEqualTo(before);
        // The session is still there.
        TestAccounts.refresh(mockMvc, TestAccounts.refreshTokenOf(sita)).andExpect(status().isOk());
    }

    @Test
    void theNewPasswordMustDifferFromTheCurrentOne() throws Exception {
        MvcResult sita = signedIn("same@" + DOMAIN, "Same Password");

        changePassword(sita, PASSWORD, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
    }

    @Test
    void aWeakNewPasswordIsRefused() throws Exception {
        MvcResult sita = signedIn("weak@" + DOMAIN, "Weak Password");

        changePassword(sita, PASSWORD, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
    }

    @Test
    void changingOnePasswordLeavesOtherAccountsAlone() throws Exception {
        MvcResult sita = signedIn("sita@" + DOMAIN, "Sita Sharma");
        MvcResult ram = signedIn("ram@" + DOMAIN, "Ram Thapa");
        String ramsHash = passwordHash("ram@" + DOMAIN);

        changePassword(sita, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        assertThat(passwordHash("ram@" + DOMAIN)).isEqualTo(ramsHash);
        TestAccounts.refresh(mockMvc, TestAccounts.refreshTokenOf(ram)).andExpect(status().isOk());
    }

    @Test
    void aLockedAccountCannotChangeItsPassword() throws Exception {
        String email = "locked@" + DOMAIN;
        MvcResult sita = signedIn(email, "Locked Account");
        jdbc.update("UPDATE users SET locked_until = now() + interval '1 hour' WHERE email = ?", email);

        changePassword(sita, PASSWORD, NEW_PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- steps ------------------------------------------------------------------------------------------------------

    private MvcResult signedIn(String email, String displayName) throws Exception {
        TestAccounts.register(mockMvc, json, email, PASSWORD, displayName);
        return TestAccounts.signIn(mockMvc, json, email, PASSWORD);
    }

    private ResultActions saveProfile(MvcResult signedIn, String body) throws Exception {
        return mockMvc.perform(put(ME)
                .header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(signedIn))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String profile(String name, String phone, boolean showPhone, String bio, Integer province, Integer district, Integer municipality)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", name);
        body.put("phone", phone);
        body.put("showPhone", showPhone);
        body.put("bio", bio);
        body.put("provinceId", province);
        body.put("districtId", district);
        body.put("municipalityId", municipality);
        return json.writeValueAsString(body);
    }

    private String passwords(String current, String next) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentPassword", current);
        body.put("newPassword", next);
        return json.writeValueAsString(body);
    }

    private ResultActions changePassword(MvcResult signedIn, String current, String next) throws Exception {
        return mockMvc.perform(put(MY_PASSWORD)
                .header(HttpHeaders.AUTHORIZATION, TestAccounts.bearer(signedIn))
                .contentType(MediaType.APPLICATION_JSON)
                .content(passwords(current, next)));
    }

    /** How the server stores refresh tokens (only their hash); computed here independently of the main code. */
    private static String sha256Hex(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> profileRow(String email) {
        return jdbc.queryForMap("SELECT p.* FROM user_profiles p JOIN users u ON u.id = p.user_id WHERE u.email = ?", email);
    }

    private String passwordHash(String email) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
    }

    /** A real province / district / municipality chain from the seed data, whatever its ids are. */
    private Place aPlace() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT d.province_id, d.id AS district_id, m.id AS municipality_id, "
                        + "p.name AS province_name, d.name AS district_name, m.name AS municipality_name "
                        + "FROM municipalities m JOIN districts d ON d.id = m.district_id JOIN provinces p ON p.id = d.province_id "
                        + "WHERE m.is_major ORDER BY m.id LIMIT 1");
        return new Place(
                (Integer) row.get("province_id"), (Integer) row.get("district_id"), (Integer) row.get("municipality_id"),
                (String) row.get("province_name"), (String) row.get("district_name"), (String) row.get("municipality_name"));
    }

    private record Place(
            int provinceId, int districtId, int municipalityId, String provinceName, String districtName, String municipalityName) {
    }
}
