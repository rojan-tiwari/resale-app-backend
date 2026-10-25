package com.resalemarket.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NepalPhoneTest {

    private static final Pattern INPUT = Pattern.compile(NepalPhone.INPUT_PATTERN);

    @ParameterizedTest
    @ValueSource(strings = {"9812345678", "9712345678", "9612345678", "+9779812345678"})
    void acceptsNepaliMobileNumbers(String number) {
        assertThat(INPUT.matcher(number).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "981234567", "98123456789", "9512345678", "0812345678", "+9769812345678",
        "+97798123", "98 12345678", "98-12345678", "९८१२३४५६७८", "9812345678 "})
    void rejectsEverythingElse(String number) {
        assertThat(INPUT.matcher(number).matches()).isFalse();
    }

    @Test
    void addsTheCountryCodeWhenMissing() {
        assertThat(NepalPhone.normalise("9812345678")).isEqualTo("+9779812345678");
    }

    @Test
    void keepsTheCountryCodeWhenPresent() {
        assertThat(NepalPhone.normalise("+9779812345678")).isEqualTo("+9779812345678");
    }

    @Test
    void treatsMissingAndBlankNumbersAsNoNumber() {
        assertThat(NepalPhone.normalise(null)).isNull();
        assertThat(NepalPhone.normalise("   ")).isNull();
    }
}
