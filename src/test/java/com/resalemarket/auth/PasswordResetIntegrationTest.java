package com.resalemarket.auth;

import static com.resalemarket.support.TestAccounts.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.common.mail.MailMessage;
import com.resalemarket.support.IntegrationTest;
import com.resalemarket.support.RecordingEmailSender;
import com.resalemarket.support.TestAccounts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Forgot and reset password end to end: HTTP -> security -> background mail -> services -> JPA -> real PostgreSQL. The
 * requests commit, so every account lives in a test-only domain and is removed afterwards (its tokens go with it,
 * ON DELETE CASCADE).
 */
@IntegrationTest
class PasswordResetIntegrationTest {

    private static final String FORGOT = "/api/v1/auth/forgot-password";
    private static final String RESET = "/api/v1/auth/reset-password";
    private static final String DOMAIN = "reset-itest.example";
    private static final String NEW_PASSWORD = "a brand new password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private RecordingEmailSender mail;

    @BeforeEach
    void forgetEarlierMail() {
        mail.clear();
    }

    @AfterEach
    void removeTestUsers() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%@" + DOMAIN);
    }

    // --- asking for a link ------------------------------------------------------------------------------------------

    @Test
    void anExistingAccountGetsAOneTimeLinkAndTheDatabaseKeepsOnlyItsHash() throws Exception {
        String email = "link@" + DOMAIN;
        register(email);

        forgot(email).andExpect(status().isAccepted());

        MailMessage message = mail.awaitMessageTo(email);
        String token = RecordingEmailSender.tokenIn(message);
        assertThat(message.subject()).isEqualTo("Reset your ResaleMarket password");
        assertThat(message.text()).contains("http://localhost:4200/auth/reset-password?token=" + token).contains("expires in 1 hour");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT token_hash, used_at, expires_at - created_at = interval '1 hour' AS one_hour "
                        + "FROM password_reset_tokens WHERE user_id = (SELECT id FROM users WHERE email = ?)", email);
        assertThat(row.get("token_hash")).isEqualTo(OpaqueTokens.sha256Hex(token)).isNotEqualTo(token);
        assertThat(row.get("used_at")).isNull();
        assertThat(row.get("one_hour")).isEqualTo(true);
    }

    @Test
    void anUnknownAddressIsAnsweredExactlyLikeAKnownOneAndNothingIsSent() throws Exception {
        String known = "known@" + DOMAIN;
        register(known);

        MvcResult forKnown = forgot(known).andExpect(status().isAccepted()).andReturn();
        MvcResult forUnknown = forgot("nobody@" + DOMAIN).andExpect(status().isAccepted()).andReturn();

        assertThat(forUnknown.getResponse().getStatus()).isEqualTo(forKnown.getResponse().getStatus());
        assertThat(forUnknown.getResponse().getContentAsString()).isEqualTo(forKnown.getResponse().getContentAsString()).isEmpty();
        assertThat(forUnknown.getResponse().getHeaderNames()).isEqualTo(forKnown.getResponse().getHeaderNames());
        assertThat(forUnknown.getResponse().getHeader("Cache-Control")).isEqualTo(forKnown.getResponse().getHeader("Cache-Control"));

        mail.awaitMessageTo(known);
        Thread.sleep(300);   // give a (wrongly) sent mail to the unknown address time to show up
        assertThat(mail.sentTo("nobody@" + DOMAIN)).isEmpty();
    }

    @Test
    void theAddressIsMatchedCaseInsensitivelyAndTrimmed() throws Exception {
        String email = "mixed.case@" + DOMAIN;
        register(email);

        forgot("  Mixed.Case@" + DOMAIN.toUpperCase() + " ").andExpect(status().isAccepted());

        assertThat(mail.awaitMessageTo(email)).isNotNull();
    }

    @Test
    void aSuspendedAccountGetsNoLink() throws Exception {
        String email = "suspended@" + DOMAIN;
        register(email);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        forgot(email).andExpect(status().isAccepted());

        Thread.sleep(300);
        assertThat(mail.sentTo(email)).isEmpty();
        assertThat(tokenRows(email)).isZero();
    }

    @Test
    void aMalformedAddressIsAValidationErrorAndSendsNothing() throws Exception {
        forgot("not-an-address")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    void askingAgainCancelsTheEarlierLink() throws Exception {
        String email = "again@" + DOMAIN;
        register(email);

        forgot(email).andExpect(status().isAccepted());
        mail.awaitMessagesTo(email, 1);
        forgot(email).andExpect(status().isAccepted());
        List<MailMessage> both = mail.awaitMessagesTo(email, 2);
        String first = RecordingEmailSender.tokenIn(both.get(0));
        String second = RecordingEmailSender.tokenIn(both.get(1));

        assertThat(first).isNotEqualTo(second);
        reset(first, NEW_PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        reset(second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    // --- using the link ---------------------------------------------------------------------------------------------

    @Test
    void theLinkSetsTheNewPasswordEndsEverySessionAndWorksOnlyOnce() throws Exception {
        String email = "flow@" + DOMAIN;
        register(email);
        MvcResult phone = TestAccounts.signIn(mockMvc, json, email, PASSWORD);
        MvcResult laptop = TestAccounts.signIn(mockMvc, json, email, PASSWORD);
        String token = requestLink(email);

        reset(token, NEW_PASSWORD)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        // Every session that existed is over, and the history says why.
        for (MvcResult device : List.of(phone, laptop)) {
            String refreshToken = TestAccounts.refreshTokenOf(device);
            TestAccounts.refresh(mockMvc, refreshToken).andExpect(status().isUnauthorized());
            assertThat(jdbc.queryForObject("SELECT revoked_reason FROM refresh_tokens WHERE token_hash = ?", String.class,
                            RefreshTokenService.hash(refreshToken)))
                    .isEqualTo("PASSWORD_CHANGED");
        }
        // The new password works, the old one does not.
        TestAccounts.login(mockMvc, json, email, NEW_PASSWORD).andExpect(status().isOk());
        TestAccounts.login(mockMvc, json, email, PASSWORD).andExpect(status().isUnauthorized());
        // The link is spent.
        assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM password_reset_tokens WHERE token_hash = ?", Boolean.class,
                OpaqueTokens.sha256Hex(token))).isTrue();
        reset(token, "yet another password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        TestAccounts.login(mockMvc, json, email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theStoredPasswordIsAFreshBcryptHash() throws Exception {
        String email = "hash@" + DOMAIN;
        register(email);
        String before = passwordHash(email);

        reset(requestLink(email), NEW_PASSWORD).andExpect(status().isNoContent());

        String after = passwordHash(email);
        assertThat(after).startsWith("{bcrypt}$2").isNotEqualTo(before).isNotEqualTo(NEW_PASSWORD);
    }

    @Test
    void anUnknownLinkIsRefusedWithTheSameWordsAsAnExpiredOne() throws Exception {
        String email = "expired@" + DOMAIN;
        register(email);
        String token = requestLink(email);
        jdbc.update("UPDATE password_reset_tokens SET created_at = now() - interval '2 hours', "
                + "expires_at = now() - interval '1 hour' WHERE token_hash = ?", OpaqueTokens.sha256Hex(token));

        String expired = reset(token, NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN")).andReturn().getResponse().getContentAsString();
        String unknown = reset("never-issued-token", NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN")).andReturn().getResponse().getContentAsString();

        assertThat(detailOf(expired)).isEqualTo(detailOf(unknown));
        // Nothing changed: the old password still works.
        TestAccounts.login(mockMvc, json, email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aWeakNewPasswordIsRefusedAndTheLinkIsNotSpent() throws Exception {
        String email = "weak@" + DOMAIN;
        register(email);
        String token = requestLink(email);

        reset(token, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("short\""))));

        reset(token, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void aLinkOfAnAccountSuspendedInTheMeantimeIsNoGood() throws Exception {
        String email = "later-suspended@" + DOMAIN;
        register(email);
        String token = requestLink(email);
        String before = passwordHash(email);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        reset(token, NEW_PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));

        assertThat(passwordHash(email)).isEqualTo(before);
        // The failed attempt rolled back as a whole, so the link was not used up either.
        assertThat(jdbc.queryForObject("SELECT used_at IS NULL FROM password_reset_tokens WHERE token_hash = ?", Boolean.class,
                OpaqueTokens.sha256Hex(token))).isTrue();
    }

    @Test
    void resettingThePasswordLiftsALockOut() throws Exception {
        String email = "locked@" + DOMAIN;
        register(email);
        jdbc.update("UPDATE users SET failed_login_attempts = 3, locked_until = now() + interval '1 hour' WHERE email = ?", email);
        TestAccounts.login(mockMvc, json, email, PASSWORD).andExpect(status().isForbidden());

        reset(requestLink(email), NEW_PASSWORD).andExpect(status().isNoContent());

        Map<String, Object> row = jdbc.queryForMap("SELECT failed_login_attempts, locked_until FROM users WHERE email = ?", email);
        assertThat(row.get("failed_login_attempts")).isEqualTo(0);
        assertThat(row.get("locked_until")).isNull();
        TestAccounts.login(mockMvc, json, email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void twoRequestsWithTheSameLinkAtTheSameMomentCannotBothSucceed() throws Exception {
        String email = "race@" + DOMAIN;
        register(email);
        String token = requestLink(email);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String password : List.of("first new password", "second new password")) {
                Callable<Integer> attempt = () -> {
                    start.await();
                    return reset(token, password).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(204, 400);
    }

    // --- steps ------------------------------------------------------------------------------------------------------

    private void register(String email) throws Exception {
        TestAccounts.register(mockMvc, json, email, PASSWORD, "Reset Test");
    }

    private ResultActions forgot(String email) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        return mockMvc.perform(post(FORGOT).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("newPassword", newPassword);
        return mockMvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** Asks for a link and returns the token from the e-mail that arrives. */
    private String requestLink(String email) throws Exception {
        int already = mail.sentTo(email).size();
        forgot(email).andExpect(status().isAccepted());
        return RecordingEmailSender.tokenIn(mail.awaitMessagesTo(email, already + 1).get(already));
    }

    private String passwordHash(String email) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
    }

    private int tokenRows(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM password_reset_tokens t JOIN users u ON u.id = t.user_id WHERE u.email = ?", Integer.class, email);
    }

    private static String detailOf(String problemJson) {
        return com.jayway.jsonpath.JsonPath.read(problemJson, "$.detail");
    }
}
