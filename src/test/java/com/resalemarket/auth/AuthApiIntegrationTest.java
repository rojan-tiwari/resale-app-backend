package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.support.IntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registration end to end: HTTP -> security -> validation -> services -> JPA -> real PostgreSQL.
 * These requests commit (unlike the rolled-back migration tests), so every test uses an address in its own
 * test-only domain and removes it afterwards; the profile and role rows go with the user (ON DELETE CASCADE).
 */
@IntegrationTest
class AuthApiIntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String DOMAIN = "auth-itest.example";
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserDetailsService userDetailsService;

    @AfterEach
    void removeTestUsers() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%@" + DOMAIN);
    }

    @Test
    void registersAnAccountAndNeverReturnsTheHash() throws Exception {
        String response = register("Sita.Sharma@" + DOMAIN, PASSWORD, "Sita Sharma", "9812345678")
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("sita.sharma@" + DOMAIN))
                .andExpect(jsonPath("$.displayName").value("Sita Sharma"))
                .andExpect(jsonPath("$.phone").value("+9779812345678"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, "sita.sharma@" + DOMAIN);
        assertThat(response).doesNotContain(PASSWORD).doesNotContain(storedHash);
    }

    @Test
    void storesABcryptHashThatMatchesThePasswordAndNeverThePasswordItself() {
        String email = "hash@" + DOMAIN;
        register(email, PASSWORD, "Hash Check", null);

        String stored = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);

        assertThat(stored).startsWith("{bcrypt}$2").isNotEqualTo(PASSWORD).hasSizeLessThanOrEqualTo(100);
        assertThat(passwordEncoder.matches(PASSWORD, stored)).isTrue();
        assertThat(passwordEncoder.matches("wrong password", stored)).isFalse();
    }

    @Test
    void createsTheUserWithTheUserRoleAndAProfile() {
        String email = "rows@" + DOMAIN;
        register(email, PASSWORD, "  Row Check  ", "+9779812345678");

        Map<String, Object> user = jdbc.queryForMap(
                "SELECT id, status, email_verified, created_at FROM users WHERE email = ?", email);
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat(user.get("email_verified")).isEqualTo(false);
        assertThat(user.get("created_at")).isNotNull();

        List<String> roles = jdbc.queryForList(
                "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?",
                String.class, user.get("id"));
        assertThat(roles).containsExactly("ROLE_USER");

        Map<String, Object> profile = jdbc.queryForMap(
                "SELECT display_name, phone, show_phone FROM user_profiles WHERE user_id = ?", user.get("id"));
        assertThat(profile.get("display_name")).isEqualTo("Row Check");
        assertThat(profile.get("phone")).isEqualTo("+9779812345678");
        assertThat(profile.get("show_phone")).isEqualTo(false);
    }

    @Test
    void thePhoneNumberIsOptional() throws Exception {
        register("nophone@" + DOMAIN, PASSWORD, "No Phone", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").isEmpty());
    }

    @Test
    void aDuplicateEmailIsRejectedWith409AndCreatesNothing() throws Exception {
        String email = "duplicate@" + DOMAIN;
        register(email, PASSWORD, "First", null).andExpect(status().isCreated());

        register(email, "another password", "Second", null)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("An account with this email address already exists."))
                .andExpect(jsonPath("$.instance").value(REGISTER));

        assertThat(countUsers(email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT display_name FROM user_profiles WHERE user_id = "
                + "(SELECT id FROM users WHERE email = ?)", String.class, email)).isEqualTo("First");
    }

    @Test
    void emailAddressesAreComparedCaseInsensitively() throws Exception {
        register("Mixed.Case@" + DOMAIN, PASSWORD, "First", null).andExpect(status().isCreated());

        register("MIXED.CASE@" + DOMAIN, PASSWORD, "Second", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(countUsers("mixed.case@" + DOMAIN)).isEqualTo(1);
    }

    @Test
    void invalidInputIsReportedFieldByField() throws Exception {
        register("not-an-email", "short", " ", "123")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("email", "password", "displayName", "phone")))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')].message").value(hasItems("must be at least 8 characters")));

        assertThat(countUsers("not-an-email")).isZero();
    }

    @Test
    void aMissingBodyFieldIsAValidationError() throws Exception {
        mockMvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("email", "password", "displayName")));
    }

    @Test
    void aPasswordBeyondBcryptsByteLimitIsAValidationErrorNotAServerError() throws Exception {
        register("long@" + DOMAIN, "क".repeat(25), "Long Password", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("password")));

        register("nepali@" + DOMAIN, "क".repeat(24), "Nepali Password", null).andExpect(status().isCreated());
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mockMvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content("{\"email\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void validationErrorsDoNotEchoThePassword() throws Exception {
        register("echo@" + DOMAIN, "Zq9-sho", "Echo", null)
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("Zq9-sho"))));
    }

    @Test
    void registrationIsPostOnlyAndOtherMethodsStayProtected() throws Exception {
        mockMvc.perform(get(REGISTER)).andExpect(status().isUnauthorized());
    }

    @Test
    void theDatabaseBackedUserDetailsServiceFindsTheNewAccount() {
        register("lookup@" + DOMAIN, PASSWORD, "Lookup", null);

        UserDetails details = userDetailsService.loadUserByUsername("LOOKUP@" + DOMAIN);

        assertThat(details.getUsername()).isEqualTo("lookup@" + DOMAIN);
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, details.getPassword())).isTrue();
    }

    private ResultActions register(String email, String password, String displayName, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("displayName", displayName);
        if (phone != null) {
            body.put("phone", phone);
        }
        try {
            return mockMvc.perform(post(REGISTER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int countUsers(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
    }
}
