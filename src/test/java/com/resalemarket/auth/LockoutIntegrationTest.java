package com.resalemarket.auth;

import static com.resalemarket.support.TestAccounts.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.support.IntegrationTest;
import com.resalemarket.support.TestAccounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wrong-password counting and lock-out against PostgreSQL. The limits are the defaults: 5 wrong passwords in a row lock the
 * account for 15 minutes. The requests commit, so every account lives in a test-only domain and is removed afterwards.
 */
@IntegrationTest
class LockoutIntegrationTest {

    private static final String DOMAIN = "lockout-itest.example";
    private static final String WRONG = "not the password";

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
    void everyWrongPasswordIsCountedAndTheFifthInARowLocksTheAccount() throws Exception {
        String email = "count@" + DOMAIN;
        register(email);

        for (int attempt = 1; attempt <= 4; attempt++) {
            wrongPassword(email);
            assertThat(failedAttempts(email)).isEqualTo(attempt);
            assertThat(lockedUntilIsSet(email)).isFalse();
        }
        wrongPassword(email);   // the fifth: still the same plain answer, but now the account is locked

        assertThat(failedAttempts(email)).isZero();   // the count starts over after a lock
        assertThat(jdbc.queryForObject(
                "SELECT locked_until > now() + interval '14 minutes' AND locked_until <= now() + interval '15 minutes' "
                        + "FROM users WHERE email = ?", Boolean.class, email)).isTrue();
    }

    @Test
    void onceLockedEvenTheCorrectPasswordIsRefusedAndSaysWhy() throws Exception {
        String email = "locked@" + DOMAIN;
        register(email);
        for (int attempt = 0; attempt < 5; attempt++) {
            wrongPassword(email);
        }

        TestAccounts.login(mockMvc, json, email, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value(containsString("temporarily locked")));
        assertThat(sessionCount(email)).isZero();
    }

    @Test
    void aWrongPasswordOnALockedAccountStaysAPlain401AndDoesNotExtendTheLock() throws Exception {
        String email = "nolonger@" + DOMAIN;
        register(email);
        for (int attempt = 0; attempt < 5; attempt++) {
            wrongPassword(email);
        }
        Object lockedUntil = jdbc.queryForObject("SELECT locked_until FROM users WHERE email = ?", Object.class, email);

        wrongPassword(email);
        wrongPassword(email);

        assertThat(jdbc.queryForObject("SELECT locked_until FROM users WHERE email = ?", Object.class, email)).isEqualTo(lockedUntil);
        assertThat(failedAttempts(email)).isZero();
    }

    @Test
    void aCorrectPasswordForgetsTheWrongOnesAndRecordsTheSignIn() throws Exception {
        String email = "forgive@" + DOMAIN;
        register(email);
        wrongPassword(email);
        wrongPassword(email);
        assertThat(failedAttempts(email)).isEqualTo(2);

        TestAccounts.login(mockMvc, json, email, PASSWORD).andExpect(status().isOk());

        assertThat(failedAttempts(email)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_login_at IS NOT NULL FROM users WHERE email = ?", Boolean.class, email)).isTrue();
        // So it takes five fresh mistakes to lock it, not three.
        for (int attempt = 0; attempt < 3; attempt++) {
            wrongPassword(email);
        }
        TestAccounts.login(mockMvc, json, email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theLockEndsOnItsOwnAndCountingStartsAgainFromZero() throws Exception {
        String email = "expiry@" + DOMAIN;
        register(email);
        jdbc.update("UPDATE users SET locked_until = now() - interval '1 second' WHERE email = ?", email);

        wrongPassword(email);
        assertThat(failedAttempts(email)).isEqualTo(1);

        TestAccounts.login(mockMvc, json, email, PASSWORD).andExpect(status().isOk());
        assertThat(lockedUntilIsSet(email)).isFalse();
    }

    @Test
    void aSuspendedAccountIsNotCounted() throws Exception {
        String email = "suspended@" + DOMAIN;
        register(email);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?", email);

        for (int attempt = 0; attempt < 6; attempt++) {
            wrongPassword(email);
        }

        assertThat(failedAttempts(email)).isZero();
        assertThat(lockedUntilIsSet(email)).isFalse();
    }

    @Test
    void anUnknownAddressIsAnsweredLikeAKnownOneHoweverOftenItIsTried() throws Exception {
        for (int attempt = 0; attempt < 7; attempt++) {
            TestAccounts.login(mockMvc, json, "nobody@" + DOMAIN, WRONG)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Invalid email or password."));
        }
    }

    @Test
    void lockingOneAccountLeavesOthersAlone() throws Exception {
        String victim = "victim@" + DOMAIN;
        String other = "other@" + DOMAIN;
        register(victim);
        register(other);
        for (int attempt = 0; attempt < 5; attempt++) {
            wrongPassword(victim);
        }

        TestAccounts.login(mockMvc, json, other, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void simultaneousWrongPasswordsAreCountedOneAfterTheOtherSoTheLockAlwaysEngages() throws Exception {
        String email = "parallel@" + DOMAIN;
        register(email);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int attempt = 0; attempt < 6; attempt++) {
                Callable<Integer> wrong = () -> {
                    start.await();
                    return TestAccounts.login(mockMvc, json, email, WRONG).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(wrong));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(401);
            }
        } finally {
            pool.shutdownNow();
        }

        // Six wrong passwords arrived at once. The row lock makes them count one after the other: four are counted, the
        // fifth locks the account (the count starts over at 0) and the sixth is ignored because the account is locked.
        // With lost updates the counter could stop short of five and the account would never lock.
        Map<String, Object> row = jdbc.queryForMap("SELECT failed_login_attempts, locked_until FROM users WHERE email = ?", email);
        assertThat(row.get("locked_until")).isNotNull();
        assertThat(row.get("failed_login_attempts")).isEqualTo(0);
    }

    // --- steps ------------------------------------------------------------------------------------------------------

    private void register(String email) throws Exception {
        TestAccounts.register(mockMvc, json, email, PASSWORD, "Lockout Test");
    }

    private void wrongPassword(String email) throws Exception {
        TestAccounts.login(mockMvc, json, email, WRONG)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password."));
    }

    private int failedAttempts(String email) {
        return jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE email = ?", Integer.class, email);
    }

    private boolean lockedUntilIsSet(String email) {
        return jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM users WHERE email = ?", Boolean.class, email);
    }

    private int sessionCount(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id WHERE u.email = ?", Integer.class, email);
    }
}
