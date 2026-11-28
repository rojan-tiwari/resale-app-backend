package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.resalemarket.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Login, refresh and logout end to end: HTTP -> security -> services -> JPA -> real PostgreSQL. The requests commit,
 * so every account lives in a test-only domain and is removed afterwards (its refresh tokens go with it, ON DELETE CASCADE).
 */
@IntegrationTest
class LoginApiIntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String LOGOUT_ALL = "/api/v1/auth/logout-all";
    private static final String WHOAMI = "/api/v1/test-support/security/whoami";
    private static final String DOMAIN = "login-itest.example";
    private static final String PASSWORD = "correct horse battery";
    private static final String COOKIE = "refresh_token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JwtDecoder jwtDecoder;

    @AfterEach
    void removeTestUsers() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%@" + DOMAIN);
    }

    // --- login ------------------------------------------------------------------------------------------------------

    @Test
    void loginReturnsTheAccessTokenTheAccountAndTheRefreshCookie() throws Exception {
        String email = "sita@" + DOMAIN;
        register(email);

        MvcResult result = login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.displayName").value("Sita Sharma"))
                .andExpect(jsonPath("$.user.roles").value(hasItem("ROLE_USER")))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith(COOKIE + "=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=1209600");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(refreshTokenOf(result));
    }

    @Test
    void theAccessTokenIsASignedJwtForTheUserWithTheirRoles() throws Exception {
        String email = "jwt@" + DOMAIN;
        register(email);

        MvcResult result = login(email, PASSWORD).andExpect(status().isOk()).andReturn();

        Jwt jwt = jwtDecoder.decode(accessTokenOf(result));
        assertThat(jwt.getSubject()).isEqualTo(JsonPath.read(result.getResponse().getContentAsString(), "$.user.id"));
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_USER");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void theRefreshTokenIsStoredOnlyAsAHash() throws Exception {
        String email = "hash@" + DOMAIN;
        register(email);
        MvcResult result = login(email, PASSWORD)
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = refreshTokenOf(result);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rt.*, u.email FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE u.email = ?", email);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("token_hash")).isEqualTo(RefreshTokenService.hash(refreshToken)).isNotEqualTo(refreshToken);
        assertThat(row.values()).doesNotContain(refreshToken);
        assertThat(row.get("revoked_at")).isNull();
        assertThat(row.get("family_id")).isNotNull();
        assertThat(row.get("expires_at")).isNotNull();
    }

    @Test
    void theAddressIsMatchedCaseInsensitivelyAndTrimmed() throws Exception {
        register("mixed.case@" + DOMAIN);

        login("  Mixed.CASE@" + DOMAIN + " ", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("mixed.case@" + DOMAIN));
    }

    @Test
    void aWrongPasswordAndAnUnknownAddressAreAnsweredIdentically() throws Exception {
        register("known@" + DOMAIN);

        MvcResult wrongPassword = login("known@" + DOMAIN, "not the password")
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password."))
                .andExpect(jsonPath("$.instance").value(LOGIN))
                .andReturn();
        MvcResult unknown = login("nobody@" + DOMAIN, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn();

        for (String field : List.of("status", "title", "detail", "code", "instance")) {
            assertThat((Object) JsonPath.read(wrongPassword.getResponse().getContentAsString(), "$." + field))
                    .as(field)
                    .isEqualTo(JsonPath.read(unknown.getResponse().getContentAsString(), "$." + field));
        }
        assertThat(wrongPassword.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(unknown.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void aFailedLoginCreatesNoSession() throws Exception {
        register("nosession@" + DOMAIN);

        login("nosession@" + DOMAIN, "not the password").andExpect(status().isUnauthorized());

        assertThat(sessionCount("nosession@" + DOMAIN)).isZero();
    }

    @Test
    void missingFieldsAreValidationErrors() throws Exception {
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("email", "password")));
    }

    @Test
    void aPasswordBeyondBcryptsByteLimitIsAValidationErrorNotAServerError() throws Exception {
        login("someone@" + DOMAIN, "क".repeat(25))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("password")));
    }

    @Test
    void errorsNeverEchoThePassword() throws Exception {
        register("echo@" + DOMAIN);

        login("echo@" + DOMAIN, "Zq9-wrong-guess")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("Zq9-wrong-guess"))));
    }

    @Test
    void aSuspendedAccountIsToldSoOnlyAfterTheCorrectPassword() throws Exception {
        String email = "suspended@" + DOMAIN;
        register(email);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        login(email, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("This account has been suspended."));
        // A stranger guessing passwords learns nothing about the account.
        login(email, "not the password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password."));

        assertThat(sessionCount(email)).isZero();
    }

    @Test
    void aLockedAccountCannotSignIn() throws Exception {
        String email = "locked@" + DOMAIN;
        register(email);
        jdbc.update("UPDATE users SET locked_until = now() + interval '1 hour' WHERE email = ?", email);

        login(email, PASSWORD).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(sessionCount(email)).isZero();
    }

    @Test
    void aDeletedAccountLooksLikeAnUnknownOne() throws Exception {
        String email = "deleted@" + DOMAIN;
        register(email);
        jdbc.update("UPDATE users SET status = 'DELETED' WHERE email = ?", email);

        login(email, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password."));
    }

    @Test
    void theAccessTokenFromLoginOpensProtectedEndpoints() throws Exception {
        String email = "whoami@" + DOMAIN;
        register(email);
        MvcResult result = login(email, PASSWORD).andExpect(status().isOk()).andReturn();
        String userId = JsonPath.read(result.getResponse().getContentAsString(), "$.user.id");

        mockMvc.perform(get(WHOAMI)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(WHOAMI).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenOf(result)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(userId))
                .andExpect(jsonPath("$.authorities").value(hasItem("ROLE_USER")));
    }

    // --- refresh ----------------------------------------------------------------------------------------------------

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() throws Exception {
        String email = "rotate@" + DOMAIN;
        register(email);
        MvcResult loginResult = login(email, PASSWORD).andExpect(status().isOk()).andReturn();
        String first = refreshTokenOf(loginResult);

        MvcResult refreshed = refresh(first)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.roles").value(hasItem("ROLE_USER")))
                .andReturn();
        String second = refreshTokenOf(refreshed);

        assertThat(second).isNotEqualTo(first);
        assertThat(refreshed.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");
        jwtDecoder.decode(accessTokenOf(refreshed));

        Map<String, Object> oldRow = rowOf(first);
        Map<String, Object> newRow = rowOf(second);
        assertThat(oldRow.get("revoked_reason")).isEqualTo("ROTATED");
        assertThat(oldRow.get("revoked_at")).isNotNull();
        assertThat(oldRow.get("replaced_by_id")).isEqualTo(newRow.get("id"));
        assertThat(newRow.get("revoked_at")).isNull();
        assertThat(newRow.get("family_id")).isEqualTo(oldRow.get("family_id"));
    }

    @Test
    void theNewRefreshTokenKeepsTheSessionGoingForever() throws Exception {
        String email = "chain@" + DOMAIN;
        register(email);
        String token = refreshTokenOf(login(email, PASSWORD).andReturn());

        for (int i = 0; i < 3; i++) {
            MvcResult refreshed = refresh(token).andExpect(status().isOk()).andReturn();
            token = refreshTokenOf(refreshed);
        }

        assertThat(sessionCount(email)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT family_id) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE u.email = ?",
                        Integer.class, email))
                .isEqualTo(1);
    }

    @Test
    void refreshNeedsTheRequestedWithHeader() throws Exception {
        String email = "header@" + DOMAIN;
        register(email);
        String token = refreshTokenOf(login(email, PASSWORD).andReturn());

        mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // The refused request did not use up the token.
        refresh(token).andExpect(status().isOk());
    }

    @Test
    void refreshWithoutACookieIsRefused() throws Exception {
        mockMvc.perform(post(REFRESH).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("Your session has expired. Please sign in again."));
    }

    @Test
    void refreshWithAnUnknownTokenIsRefused() throws Exception {
        refresh("this-token-was-never-issued")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void usingAnOldRefreshTokenAgainEndsTheWholeSession() throws Exception {
        String email = "theft@" + DOMAIN;
        register(email);
        String stolen = refreshTokenOf(login(email, PASSWORD).andReturn());
        // The legitimate browser uses the token first and receives the next one.
        String current = refreshTokenOf(refresh(stolen).andExpect(status().isOk()).andReturn());

        // The thief presents the token that was already used.
        refresh(stolen).andExpect(status().isUnauthorized());

        // Both parties are now signed out: the legitimate browser's newest token no longer works either.
        refresh(current).andExpect(status().isUnauthorized());
        assertThat(rowOf(stolen).get("revoked_reason")).isEqualTo("ROTATED");
        assertThat(rowOf(current).get("revoked_reason")).isEqualTo("REUSE_DETECTED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE u.email = ? AND rt.revoked_at IS NULL",
                        Integer.class, email))
                .isZero();
    }

    @Test
    void refreshIsRefusedOnceTheAccountIsSuspended() throws Exception {
        String email = "suspend-later@" + DOMAIN;
        register(email);
        String token = refreshTokenOf(login(email, PASSWORD).andReturn());
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        refresh(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Your session has expired. Please sign in again."));
    }

    @Test
    void sessionsOnDifferentDevicesAreIndependent() throws Exception {
        String email = "devices@" + DOMAIN;
        register(email);
        String phone = refreshTokenOf(login(email, PASSWORD).andReturn());
        String laptop = refreshTokenOf(login(email, PASSWORD).andReturn());

        logout(phone).andExpect(status().isNoContent());

        refresh(phone).andExpect(status().isUnauthorized());
        refresh(laptop).andExpect(status().isOk());
    }

    // --- logout -----------------------------------------------------------------------------------------------------

    @Test
    void logoutEndsTheSessionAndClearsTheCookie() throws Exception {
        String email = "logout@" + DOMAIN;
        register(email);
        String token = refreshTokenOf(login(email, PASSWORD).andReturn());

        MvcResult result = logout(token)
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(COOKIE + "=;")
                .contains("Max-Age=0", "Path=/api/v1/auth", "HttpOnly");
        assertThat(rowOf(token).get("revoked_reason")).isEqualTo("LOGOUT");
        refresh(token).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutACookieStillSucceedsAndClearsIt() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGOUT).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    void signingOutTwiceIsFine() throws Exception {
        String email = "twice@" + DOMAIN;
        register(email);
        String token = refreshTokenOf(login(email, PASSWORD).andReturn());

        logout(token).andExpect(status().isNoContent());
        logout(token).andExpect(status().isNoContent());
    }

    @Test
    void logoutNeedsTheRequestedWithHeader() throws Exception {
        String email = "logout-header@" + DOMAIN;
        register(email);
        String token = refreshTokenOf(login(email, PASSWORD).andReturn());

        mockMvc.perform(post(LOGOUT).cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(rowOf(token).get("revoked_at")).as("a request without the header must not sign anybody out").isNull();
    }

    // --- reuse detection touches only the stolen session -------------------------------------------------------------

    @Test
    void reuseDetectionEndsOnlyTheSessionThatWasReused() throws Exception {
        String email = "reuse-scope@" + DOMAIN;
        register(email);
        String phone = refreshTokenOf(login(email, PASSWORD).andReturn());
        String laptop = refreshTokenOf(login(email, PASSWORD).andReturn());

        // The phone session is rotated once, and then its old token is replayed.
        refresh(phone).andExpect(status().isOk());
        refresh(phone).andExpect(status().isUnauthorized());

        // The laptop is a different login session and carries on.
        refresh(laptop).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE u.email = ? AND rt.revoked_reason = 'REUSE_DETECTED'",
                        Integer.class, email))
                .as("only the phone session's newest token is revoked as reused")
                .isEqualTo(1);
    }

    // --- logout of all devices ----------------------------------------------------------------------------------------

    @Test
    void logoutAllEndsEverySessionOfTheUserOnEveryDevice() throws Exception {
        String email = "all-devices@" + DOMAIN;
        register(email);
        MvcResult phone = login(email, PASSWORD).andExpect(status().isOk()).andReturn();
        MvcResult laptop = login(email, PASSWORD).andExpect(status().isOk()).andReturn();
        MvcResult tablet = login(email, PASSWORD).andExpect(status().isOk()).andReturn();

        MvcResult result = logoutAll(accessTokenOf(laptop)).andExpect(status().isNoContent()).andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith(COOKIE + "=;")
                .contains("Max-Age=0", "Path=/api/v1/auth", "HttpOnly");
        for (MvcResult device : List.of(phone, laptop, tablet)) {
            refresh(refreshTokenOf(device)).andExpect(status().isUnauthorized());
        }
        assertThat(sessionCount(email)).isEqualTo(3);
        assertThat(activeSessionCount(email)).isZero();
        assertThat(rowOf(refreshTokenOf(phone)).get("revoked_reason")).isEqualTo("LOGOUT");
    }

    @Test
    void logoutAllLeavesOtherPeoplesSessionsAlone() throws Exception {
        register("mine@" + DOMAIN);
        register("theirs@" + DOMAIN);
        MvcResult mine = login("mine@" + DOMAIN, PASSWORD).andReturn();
        String theirs = refreshTokenOf(login("theirs@" + DOMAIN, PASSWORD).andReturn());

        logoutAll(accessTokenOf(mine)).andExpect(status().isNoContent());

        refresh(theirs).andExpect(status().isOk());
        assertThat(activeSessionCount("mine@" + DOMAIN)).isZero();
    }

    @Test
    void logoutAllDoesNotRewriteHowEarlierSessionsEnded() throws Exception {
        String email = "history@" + DOMAIN;
        register(email);
        MvcResult signedIn = login(email, PASSWORD).andReturn();
        String first = refreshTokenOf(signedIn);
        String second = refreshTokenOf(refresh(first).andExpect(status().isOk()).andReturn());

        logoutAll(accessTokenOf(signedIn)).andExpect(status().isNoContent());

        assertThat(rowOf(first).get("revoked_reason")).as("already rotated before").isEqualTo("ROTATED");
        assertThat(rowOf(second).get("revoked_reason")).isEqualTo("LOGOUT");
    }

    @Test
    void logoutAllNeedsAnAccessToken() throws Exception {
        String email = "needs-token@" + DOMAIN;
        register(email);
        String refreshToken = refreshTokenOf(login(email, PASSWORD).andReturn());

        mockMvc.perform(post(LOGOUT_ALL))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post(LOGOUT_ALL).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        // The refresh cookie alone is not enough for this endpoint: it is a bigger step than signing out one device.
        mockMvc.perform(post(LOGOUT_ALL).cookie(new Cookie(COOKIE, refreshToken)).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());

        assertThat(activeSessionCount(email)).as("nothing was revoked by the refused calls").isEqualTo(1);
    }

    @Test
    void logoutAllIsSafeToRepeatAndNeedsNoSessionToBeOpen() throws Exception {
        String email = "repeat@" + DOMAIN;
        register(email);
        String accessToken = accessTokenOf(login(email, PASSWORD).andReturn());

        logoutAll(accessToken).andExpect(status().isNoContent());
        logoutAll(accessToken).andExpect(status().isNoContent());
    }

    // --- browser access -----------------------------------------------------------------------------------------------

    @Test
    void theFrontendOriginMayAddTheRequestedWithHeaderAndSendCookies() throws Exception {
        mockMvc.perform(options(REFRESH)
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-requested-with"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void anotherOriginMayNotCallTheCookieEndpoints() throws Exception {
        mockMvc.perform(options(REFRESH)
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-requested-with"))
                .andExpect(status().isForbidden());
    }

    // --- helpers ------------------------------------------------------------------------------------------------------

    private void register(String email) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("displayName", "Sita Sharma");
        mockMvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private ResultActions login(String email, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post(REFRESH).cookie(new Cookie(COOKIE, refreshToken)).header("X-Requested-With", "XMLHttpRequest"));
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mockMvc.perform(post(LOGOUT).cookie(new Cookie(COOKIE, refreshToken)).header("X-Requested-With", "XMLHttpRequest"));
    }

    private ResultActions logoutAll(String accessToken) throws Exception {
        return mockMvc.perform(post(LOGOUT_ALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private static String accessTokenOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    /** The refresh token the server put into the {@code Set-Cookie} header. */
    private static String refreshTokenOf(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("Set-Cookie header").isNotNull().startsWith(COOKIE + "=");
        return setCookie.substring((COOKIE + "=").length(), setCookie.indexOf(';'));
    }

    private Map<String, Object> rowOf(String refreshToken) {
        return jdbc.queryForMap("SELECT * FROM refresh_tokens WHERE token_hash = ?", RefreshTokenService.hash(refreshToken));
    }

    private int activeSessionCount(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE u.email = ? AND rt.revoked_at IS NULL",
                Integer.class, email);
    }

    private int sessionCount(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE u.email = ?", Integer.class, email);
    }
}
