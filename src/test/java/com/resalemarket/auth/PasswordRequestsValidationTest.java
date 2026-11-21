package com.resalemarket.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.auth.dto.ChangePasswordRequest;
import com.resalemarket.auth.dto.ForgotPasswordRequest;
import com.resalemarket.auth.dto.ResetPasswordRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The three request bodies of the password endpoints: what they accept, and that they never print a password. */
class PasswordRequestsValidationTest {

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

    private static Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    // --- forgot password ----------------------------------------------------------------------------------------

    @Test
    void forgotPasswordNeedsAWellFormedAddress() {
        assertThat(validator.validate(new ForgotPasswordRequest("sita@example.com"))).isEmpty();
        assertThat(invalidFields(new ForgotPasswordRequest(null))).containsExactly("email");
        assertThat(invalidFields(new ForgotPasswordRequest("  "))).containsExactly("email");
        assertThat(invalidFields(new ForgotPasswordRequest("not-an-address"))).containsExactly("email");
        assertThat(invalidFields(new ForgotPasswordRequest("a@" + "b".repeat(260) + ".com"))).contains("email");
    }

    @Test
    void forgotPasswordTrimsTheAddress() {
        assertThat(new ForgotPasswordRequest("  sita@example.com ").email()).isEqualTo("sita@example.com");
    }

    // --- reset password -----------------------------------------------------------------------------------------

    @Test
    void resetPasswordNeedsATokenAndAValidNewPassword() {
        assertThat(validator.validate(new ResetPasswordRequest("abc", "correct horse"))).isEmpty();
        assertThat(invalidFields(new ResetPasswordRequest(null, null))).containsExactlyInAnyOrder("token", "newPassword");
        assertThat(invalidFields(new ResetPasswordRequest(" ", "short"))).containsExactlyInAnyOrder("token", "newPassword");
        assertThat(invalidFields(new ResetPasswordRequest("t".repeat(201), "correct horse"))).containsExactly("token");
    }

    @Test
    void resetPasswordMeasuresTheNewPasswordInBytesBecauseOfBcrypt() {
        assertThat(validator.validate(new ResetPasswordRequest("abc", "a".repeat(72)))).isEmpty();
        assertThat(invalidFields(new ResetPasswordRequest("abc", "a".repeat(73)))).containsExactly("newPassword");
        // 25 Devanagari letters are 25 characters but 75 bytes.
        assertThat(invalidFields(new ResetPasswordRequest("abc", "क".repeat(25)))).containsExactly("newPassword");
    }

    @Test
    void resetPasswordTrimsTheTokenButNotThePassword() {
        ResetPasswordRequest request = new ResetPasswordRequest("  abc  ", "  spaces kept  ");

        assertThat(request.token()).isEqualTo("abc");
        assertThat(request.newPassword()).isEqualTo("  spaces kept  ");
    }

    @Test
    void resetPasswordNeverPrintsTheTokenOrThePassword() {
        assertThat(new ResetPasswordRequest("secret-token", "secret password").toString())
                .doesNotContain("secret-token", "secret password");
    }

    // --- change password ----------------------------------------------------------------------------------------

    @Test
    void changePasswordNeedsBothPasswords() {
        assertThat(validator.validate(new ChangePasswordRequest("old password", "a brand new password"))).isEmpty();
        assertThat(invalidFields(new ChangePasswordRequest(null, null))).containsExactlyInAnyOrder("currentPassword", "newPassword");
        assertThat(invalidFields(new ChangePasswordRequest("old password", "short"))).containsExactly("newPassword");
    }

    @Test
    void changePasswordDoesNotJudgeTheLengthOfTheCurrentPasswordOnlyItsByteLimit() {
        // An old, shorter password must still be accepted as the *current* one; but nothing over 72 bytes can be stored.
        assertThat(validator.validate(new ChangePasswordRequest("abc", "a brand new password"))).isEmpty();
        assertThat(invalidFields(new ChangePasswordRequest("a".repeat(73), "a brand new password")))
                .containsExactly("currentPassword");
    }

    @Test
    void changePasswordNeverPrintsEitherPassword() {
        assertThat(new ChangePasswordRequest("old secret", "new secret").toString()).doesNotContain("old secret", "new secret");
    }
}
