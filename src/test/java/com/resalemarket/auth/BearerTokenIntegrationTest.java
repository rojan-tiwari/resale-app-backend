package com.resalemarket.auth;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.support.IntegrationTest;
import com.resalemarket.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The access-token half of the security contract, through the whole filter chain: who gets in, who is kept out, and
 * that every refusal looks like every other error (problem+json with a stable code).
 */
@IntegrationTest
class BearerTokenIntegrationTest {

    private static final String WHOAMI = "/api/v1/test-support/security/whoami";
    private static final String ADMIN = "/api/v1/test-support/security/admin";
    private static final String USER_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder encoder;

    private ResultActions getWith(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static void assertUnauthorized(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void aProtectedEndpointWithoutATokenIsRefused() throws Exception {
        assertUnauthorized(mockMvc.perform(get(WHOAMI)));
    }

    @Test
    void aValidTokenIdentifiesTheCallerAndGrantsTheirRoles() throws Exception {
        getWith(WHOAMI, TestTokens.valid(encoder, USER_ID, "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(USER_ID))
                .andExpect(jsonPath("$.authorities").value(hasItem("ROLE_USER")));
    }

    @Test
    void aUserCannotEnterTheAdminAreaButAnAdminCan() throws Exception {
        getWith(ADMIN, TestTokens.valid(encoder, USER_ID, "ROLE_USER"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        getWith(ADMIN, TestTokens.valid(encoder, USER_ID, "ROLE_USER", "ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area").value("admin"));
    }

    @Test
    void anAdminAuthorityIsOnlyGrantedByTheRolesClaim() throws Exception {
        getWith(WHOAMI, TestTokens.valid(encoder, USER_ID, "ROLE_USER", "ROLE_ADMIN"))
                .andExpect(jsonPath("$.authorities").value(hasItem("ROLE_ADMIN")))
                .andExpect(jsonPath("$.authorities", containsInAnyOrder("FACTOR_BEARER", "ROLE_ADMIN", "ROLE_USER")));
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        Instant now = Instant.now();
        String expired = TestTokens.sign(
                encoder, TestTokens.ISSUER, USER_ID, now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(10)), "ROLE_USER");

        assertUnauthorized(getWith(WHOAMI, expired));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
        Instant now = Instant.now();
        String forged = TestTokens.sign(
                TestTokens.encoderWithAnotherKey(), TestTokens.ISSUER, USER_ID, now, now.plus(Duration.ofMinutes(15)), "ROLE_ADMIN");

        assertUnauthorized(getWith(ADMIN, forged));
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() throws Exception {
        Instant now = Instant.now();
        String foreign = TestTokens.sign(encoder, "someone-else", USER_ID, now, now.plus(Duration.ofMinutes(15)), "ROLE_USER");

        assertUnauthorized(getWith(WHOAMI, foreign));
    }

    @Test
    void aTokenWithEditedRolesIsRefused() throws Exception {
        String[] parts = TestTokens.valid(encoder, USER_ID, "ROLE_USER").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("ROLE_USER", "ROLE_ADMIN");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertUnauthorized(getWith(ADMIN, tampered));
    }

    @Test
    void anUnsignedTokenIsRefused() throws Exception {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        long exp = Instant.now().plus(Duration.ofMinutes(15)).getEpochSecond();
        String unsigned = base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(("{\"iss\":\"resale-market-api\",\"sub\":\"x\",\"exp\":" + exp + ",\"roles\":[\"ROLE_ADMIN\"]}")
                        .getBytes(StandardCharsets.UTF_8))
                + ".";

        assertUnauthorized(getWith(ADMIN, unsigned));
    }

    @Test
    void garbageInTheAuthorizationHeaderIsRefused() throws Exception {
        assertUnauthorized(getWith(WHOAMI, "not-a-token"));
    }

    @Test
    void aBrokenTokenIsRefusedEvenOnAPublicEndpoint() throws Exception {
        // An expired token must not be silently ignored: the client has to learn that it should refresh.
        assertUnauthorized(getWith("/api/v1/system/ping", "not-a-token"));
        mockMvc.perform(get("/api/v1/system/ping")).andExpect(status().isOk());
    }

    @Test
    void aValidTokenOnAPublicEndpointStillWorks() throws Exception {
        getWith("/api/v1/system/ping", TestTokens.valid(encoder, USER_ID, "ROLE_USER")).andExpect(status().isOk());
    }
}
