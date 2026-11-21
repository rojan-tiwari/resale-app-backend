package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    @Test
    void aTokenIs256BitsOfUrlSafeText() {
        String token = OpaqueTokens.newToken();

        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void tokensDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(OpaqueTokens.newToken())).isTrue();
        }
    }

    @Test
    void theHashIsLowerCaseHexSha256() {
        // Well-known test vector: SHA-256("abc").
        assertThat(OpaqueTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
