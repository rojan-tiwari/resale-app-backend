package com.resalemarket.system;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 1 security contract: public ping/health, deny-by-default everywhere else, strict CORS, uniform errors. */
@IntegrationTest
class SecurityBaselineIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:4200";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pingIsPublicAndProvesTheDatabaseConnection() throws Exception {
        mockMvc.perform(get("/api/v1/system/ping"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.application").value("resale-market-api"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.migrationsApplied", greaterThanOrEqualTo(10)))
                .andExpect(jsonPath("$.serverTime").exists());
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void everythingNotExplicitlyPublicRequiresAuthentication() throws Exception {
        // "mine" sits next to the public listing paths and must never be mistaken for one of them
        mockMvc.perform(get("/api/v1/listings/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void discoveryIsPublicForReadingOnlyAndFavouritesAreNot() throws Exception {
        for (String path : new String[] {"/api/v1/listings", "/api/v1/listings/featured", "/api/v1/listings/recent", "/api/v1/listings/recommended"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/listings")).andExpect(status().isUnauthorized());
        for (String path : new String[] {"/api/v1/favorites", "/api/v1/favorites/ids"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mockMvc.perform(post("/api/v1/favorites/0b8f0a54-6a52-4d3c-9f43-0a3d4a8d6c11")).andExpect(status().isUnauthorized());
    }

    @Test
    void conversationsAreNeverPublic() throws Exception {
        String conversation = "0b8f0a54-6a52-4d3c-9f43-0a3d4a8d6c11";
        for (String path : new String[] {"/api/v1/conversations", "/api/v1/conversations/" + conversation}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mockMvc.perform(post("/api/v1/conversations")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointsAreReadOnly() throws Exception {
        mockMvc.perform(post("/api/v1/locations/provinces"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsTheConfiguredFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/locations/provinces")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void corsRejectsUnknownOrigins() throws Exception {
        mockMvc.perform(options("/api/v1/locations/provinces")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

}
