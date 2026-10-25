package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.auth.dto.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegisterRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static Set<String> invalidFields(RegisterRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static RegisterRequest withPassword(String password) {
        return new RegisterRequest("sita@example.com", password, "Sita Sharma", null);
    }

    @Test
    void aCompleteRequestIsValid() {
        assertThat(validator.validate(new RegisterRequest("sita@example.com", "correct horse", "Sita Sharma", "9812345678")))
                .isEmpty();
    }

    @Test
    void thePhoneNumberIsOptional() {
        assertThat(validator.validate(new RegisterRequest("sita@example.com", "correct horse", "Sita Sharma", null)))
                .isEmpty();
    }

    @Test
    void everyRequiredFieldIsReportedWhenMissing() {
        assertThat(invalidFields(new RegisterRequest(null, null, null, null)))
                .containsExactlyInAnyOrder("email", "password", "displayName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "a@b", "@example.com", "sita@", "two words@example.com"})
    void rejectsMalformedEmailAddresses(String email) {
        assertThat(invalidFields(new RegisterRequest(email, "correct horse", "Sita", null))).contains("email");
    }

    @Test
    void rejectsAnEmailLongerThan254Characters() {
        String email = "a".repeat(250) + "@b.co";

        assertThat(invalidFields(new RegisterRequest(email, "correct horse", "Sita", null))).contains("email");
    }

    @Test
    void passwordsNeedAtLeastEightCharacters() {
        assertThat(invalidFields(withPassword("1234567"))).containsExactly("password");
        assertThat(invalidFields(withPassword("12345678"))).isEmpty();
    }

    @Test
    void aPasswordOfOnlySpacesIsRejected() {
        assertThat(invalidFields(withPassword("        "))).containsExactly("password");
    }

    @Test
    void passwordsAreLimitedToBcryptsSeventyTwoBytes() {
        assertThat(invalidFields(withPassword("a".repeat(72)))).isEmpty();
        assertThat(invalidFields(withPassword("a".repeat(73)))).containsExactly("password");
    }

    @Test
    void theLimitCountsBytesSoLongNepaliPasswordsAreRejectedCleanlyInsteadOfFailingInTheEncoder() {
        String twentyFourLetters = "क".repeat(24); // 3 bytes each = 72 bytes
        String twentyFiveLetters = "क".repeat(25); // 75 bytes

        assertThat(invalidFields(withPassword(twentyFourLetters))).isEmpty();
        assertThat(invalidFields(withPassword(twentyFiveLetters))).containsExactly("password");
    }

    @Test
    void thePasswordIsKeptExactlyAsTyped() {
        assertThat(withPassword("  spaces kept  ").password()).isEqualTo("  spaces kept  ");
    }

    @Test
    void surroundingWhitespaceIsDroppedBeforeValidation() {
        RegisterRequest request = new RegisterRequest("  sita@example.com ", "correct horse", "  Sita Sharma ", " 9812345678 ");

        assertThat(request.email()).isEqualTo("sita@example.com");
        assertThat(request.displayName()).isEqualTo("Sita Sharma");
        assertThat(request.phone()).isEqualTo("9812345678");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void aDisplayNameThatIsTooShortOnceTrimmedIsRejected() {
        assertThat(invalidFields(new RegisterRequest("sita@example.com", "correct horse", " A ", null)))
                .containsExactly("displayName");
        assertThat(invalidFields(new RegisterRequest("sita@example.com", "correct horse", "A".repeat(81), null)))
                .containsExactly("displayName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123", "9512345678", "98123456789", "+9769812345678", "98 12345678"})
    void rejectsPhoneNumbersThatAreNotNepaliMobileNumbers(String phone) {
        Set<ConstraintViolation<RegisterRequest>> violations =
                validator.validate(new RegisterRequest("sita@example.com", "correct horse", "Sita", phone));

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString()).containsExactly("phone");
    }

    @Test
    void toStringNeverContainsThePassword() {
        assertThat(withPassword("correct horse").toString()).doesNotContain("correct horse");
    }
}
