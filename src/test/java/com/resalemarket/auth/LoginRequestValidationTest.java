package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.auth.dto.LoginRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LoginRequestValidationTest {

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

    private static Set<String> invalidFields(LoginRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void anEmailAndAPasswordAreValid() {
        assertThat(validator.validate(new LoginRequest("sita@example.com", "correct horse"))).isEmpty();
    }

    @Test
    void bothFieldsAreRequired() {
        assertThat(invalidFields(new LoginRequest(null, null))).containsExactlyInAnyOrder("email", "password");
        assertThat(invalidFields(new LoginRequest("  ", " "))).containsExactlyInAnyOrder("email", "password");
    }

    @Test
    void theAddressIsTrimmedButThePasswordIsKeptAsTyped() {
        LoginRequest request = new LoginRequest("  sita@example.com ", " pass word ");

        assertThat(request.email()).isEqualTo("sita@example.com");
        assertThat(request.password()).isEqualTo(" pass word ");
    }

    @Test
    void passwordRulesOfRegistrationAreNotRepeatedHereBeyondWhatBcryptCanHandle() {
        // Short or oddly formatted values are simply wrong credentials (401), not a hint about the password policy.
        assertThat(validator.validate(new LoginRequest("not-an-email", "x"))).isEmpty();
        // Anything BCrypt cannot hash (over 72 bytes) can never be a stored password; refuse it before hashing.
        assertThat(invalidFields(new LoginRequest("sita@example.com", "क".repeat(25)))).containsExactly("password");
    }

    @Test
    void anOverlongAddressIsRejected() {
        assertThat(invalidFields(new LoginRequest("a".repeat(250) + "@b.com", "pw"))).containsExactly("email");
    }

    @Test
    void thePasswordIsNotPartOfToString() {
        assertThat(new LoginRequest("sita@example.com", "correct horse").toString()).doesNotContain("correct horse");
    }
}
