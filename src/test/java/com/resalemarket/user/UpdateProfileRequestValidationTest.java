package com.resalemarket.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.user.dto.UpdateProfileRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UpdateProfileRequestValidationTest {

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

    private static Set<String> invalidFields(UpdateProfileRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static UpdateProfileRequest with(String name, String phone, String bio, Integer province) {
        return new UpdateProfileRequest(name, phone, false, bio, province, null, null);
    }

    @Test
    void aCompleteProfileIsValid() {
        assertThat(validator.validate(new UpdateProfileRequest("Sita Sharma", "9812345678", true, "Hello", 3, 25, 1))).isEmpty();
    }

    @Test
    void everythingExceptTheNameIsOptional() {
        assertThat(validator.validate(with("Sita", null, null, null))).isEmpty();
    }

    @Test
    void theNameIsRequiredAndBetween2And80CharactersAfterTrimming() {
        assertThat(invalidFields(with(null, null, null, null))).containsExactly("displayName");
        assertThat(invalidFields(with("   ", null, null, null))).containsExactly("displayName");
        assertThat(invalidFields(with(" A ", null, null, null))).containsExactly("displayName");
        assertThat(validator.validate(with("  Ab  ", null, null, null))).isEmpty();
        assertThat(validator.validate(with("x".repeat(80), null, null, null))).isEmpty();
        assertThat(invalidFields(with("x".repeat(81), null, null, null))).containsExactly("displayName");
    }

    @Test
    void aMissingConsentFlagMeansFalse() {
        UpdateProfileRequest request = new UpdateProfileRequest("Sita", null, null, null, null, null, null);

        assertThat(request.showPhone()).isFalse();
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void aBlankPhoneOrBioMeansNone() {
        UpdateProfileRequest request = with("Sita", "   ", "  ", null);

        assertThat(request.phone()).isNull();
        assertThat(request.bio()).isNull();
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void thePhoneMustBeANepaliMobileNumber() {
        assertThat(validator.validate(with("Sita", "+9779812345678", null, null))).isEmpty();
        assertThat(invalidFields(with("Sita", "12345", null, null))).containsExactly("phone");
        assertThat(invalidFields(with("Sita", "9512345678", null, null))).containsExactly("phone");
    }

    @Test
    void theBioIsAtMost500Characters() {
        assertThat(validator.validate(with("Sita", null, "b".repeat(500), null))).isEmpty();
        assertThat(invalidFields(with("Sita", null, "b".repeat(501), null))).containsExactly("bio");
    }

    @Test
    void locationIdsMustBePositive() {
        assertThat(invalidFields(with("Sita", null, null, 0))).containsExactly("provinceId");
        assertThat(invalidFields(new UpdateProfileRequest("Sita", null, false, null, 3, -1, -5)))
                .containsExactlyInAnyOrder("districtId", "municipalityId");
    }
}
