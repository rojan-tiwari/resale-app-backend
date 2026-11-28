package com.resalemarket.auth;

import static com.resalemarket.support.TestAccounts.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.support.IntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rate limits on the public authentication endpoints through the whole stack. The other integration tests raise the
 * limits sky-high (they sign in hundreds of times from one address); this one lowers them, which makes it a context of its
 * own (and therefore a database container of its own). Each test uses a different client address, because the counters live as
 * long as the context does.
 */
@IntegrationTest
@TestPropertySource(properties = {
    "app.auth.rate-limits.login.attempts=3",
    "app.auth.rate-limits.login.window=10m",
    "app.auth.rate-limits.register.attempts=2",
    "app.auth.rate-limits.register.window=1h",
})
class RateLimitIntegrationTest {

    private static final String DOMAIN = "ratelimit-itest.example";

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

    @Test
    void signInIsRefusedWith429AfterTheLimitAndSaysWhenToComeBack() throws Exception {
        String ip = "198.51.100.10";
        for (int attempt = 0; attempt < 3; attempt++) {
            login(ip, "someone@" + DOMAIN, "wrong password").andExpect(status().isUnauthorized());
        }

        String body = login(ip, "someone@" + DOMAIN, "wrong password")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.detail").value(containsString("Too many attempts. Please try again in")))
                .andExpect(header().exists("Retry-After"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("wrong password");
    }

    @Test
    void theRetryAfterIsWithinTheWindow() throws Exception {
        String ip = "198.51.100.11";
        for (int attempt = 0; attempt < 3; attempt++) {
            login(ip, "someone@" + DOMAIN, "wrong password");
        }

        String retryAfter = login(ip, "someone@" + DOMAIN, "wrong password")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");

        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 600L);
    }

    @Test
    void anotherAddressIsNotAffected() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            login("198.51.100.12", "someone@" + DOMAIN, "wrong password");
        }

        login("198.51.100.13", "someone@" + DOMAIN, "wrong password").andExpect(status().isUnauthorized());
    }

    @Test
    void aRefusedAttemptIsNotEvenChecked() throws Exception {
        String ip = "198.51.100.14";
        register(ip, "first@" + DOMAIN).andExpect(status().isCreated());
        register(ip, "second@" + DOMAIN).andExpect(status().isCreated());

        register(ip, "third@" + DOMAIN).andExpect(status().isTooManyRequests());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, "third@" + DOMAIN)).isZero();
    }

    // --- steps ------------------------------------------------------------------------------------------------------

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private ResultActions login(String ip, String email, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return mockMvc.perform(post("/api/v1/auth/login").with(from(ip))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions register(String ip, String email) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("displayName", "Rate Limit");
        return mockMvc.perform(post("/api/v1/auth/register").with(from(ip))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
}
