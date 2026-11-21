package com.resalemarket.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.common.config.CorsProperties;
import com.resalemarket.common.config.JwtConfig;
import com.resalemarket.common.config.JwtProperties;
import com.resalemarket.common.config.SecurityConfig;
import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.InvalidTokenException;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.exception.TooManyRequestsException;
import com.resalemarket.common.exception.UnauthorizedException;
import com.resalemarket.common.security.RestSecurityErrorHandlers;
import com.resalemarket.location.dto.LocationNames;
import com.resalemarket.support.TestTokens;
import com.resalemarket.user.UserController;
import com.resalemarket.user.UserService;
import com.resalemarket.user.dto.PublicUserResponse;
import com.resalemarket.user.dto.UpdateProfileRequest;
import com.resalemarket.user.dto.UserResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The HTTP contract of the account endpoints (profile, public profile, forgot / reset / change password) through the
 * <b>real</b> security filter chain, JWT verification and error handler, with the services mocked. No database is needed, so
 * these run everywhere, including machines without Docker; the end-to-end versions with PostgreSQL are the
 * {@code *IntegrationTest} classes.
 */
@WebMvcTest(controllers = {UserController.class, PasswordController.class, AuthController.class})
@Import({SecurityConfig.class, JwtConfig.class, RestSecurityErrorHandlers.class, RefreshCookies.class})
// A web slice does not process @ConfigurationPropertiesScan, so the settings the imported classes need are listed here.
@EnableConfigurationProperties({AuthProperties.class, JwtProperties.class, CorsProperties.class})
@ActiveProfiles("test")
class AccountEndpointsWebTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String PUBLIC_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private PasswordResetService passwordReset;

    @MockitoBean
    private AuthRateLimits rateLimits;

    /** Needed by {@link SecurityConfig}'s authentication manager; sign-in itself is not exercised here. */
    @MockitoBean
    private UserDetailsService userDetailsService;

    private final UserResponse account = new UserResponse(
            USER_ID, "sita@example.com", "Sita Sharma", "+9779812345678", true, "Hello", 3, 25, 1,
            Instant.parse("2026-10-20T08:15:00Z"), List.of("ROLE_USER"));

    private String bearer() {
        return "Bearer " + TestTokens.valid(jwtEncoder, USER_ID.toString(), "ROLE_USER");
    }

    private static final String PROFILE_JSON = """
            {"displayName":"Sita Sharma","phone":"9812345678","showPhone":true,"bio":"Hello",
             "provinceId":3,"districtId":25,"municipalityId":1}""";

    // --- GET /users/me --------------------------------------------------------------------------------------------

    @Test
    void ownProfileNeedsASignIn() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(userService);
    }

    @Test
    void ownProfileIsReturnedForTheTokensSubjectAndIsNeverCached() throws Exception {
        when(userService.getActiveAccount(USER_ID)).thenReturn(account);

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("sita@example.com"))
                .andExpect(jsonPath("$.phone").value("+9779812345678"))
                .andExpect(jsonPath("$.showPhone").value(true))
                .andExpect(jsonPath("$.bio").value("Hello"))
                .andExpect(jsonPath("$.provinceId").value(3))
                .andExpect(jsonPath("$.districtId").value(25))
                .andExpect(jsonPath("$.municipalityId").value(1))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void ownProfileOfAnAccountThatWasSuspendedMeanstheSessionIsOver() throws Exception {
        when(userService.getActiveAccount(USER_ID)).thenThrow(new UnauthorizedException("Your session has expired. Please sign in again."));

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void anExpiredTokenIsRefusedSoTheWebAppKnowsToRenewIt() throws Exception {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        String expired = TestTokens.sign(jwtEncoder, TestTokens.ISSUER, USER_ID.toString(), past.minusSeconds(900), past, "ROLE_USER");

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    // --- PUT /users/me --------------------------------------------------------------------------------------------

    @Test
    void savingTheProfileNeedsASignIn() throws Exception {
        mockMvc.perform(put("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void savingTheProfileHandsTheFormToTheServiceForTheTokensUserOnly() throws Exception {
        when(userService.updateProfile(eq(USER_ID), any(UpdateProfileRequest.class))).thenReturn(account);

        mockMvc.perform(put("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROFILE_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.displayName").value("Sita Sharma"));

        org.mockito.ArgumentCaptor<UpdateProfileRequest> form = org.mockito.ArgumentCaptor.forClass(UpdateProfileRequest.class);
        verify(userService).updateProfile(eq(USER_ID), form.capture());
        org.assertj.core.api.Assertions.assertThat(form.getValue())
                .isEqualTo(new UpdateProfileRequest("Sita Sharma", "9812345678", true, "Hello", 3, 25, 1));
    }

    @Test
    void aBodyCannotNameAnotherUserTheIdComesOnlyFromTheToken() throws Exception {
        when(userService.updateProfile(eq(USER_ID), any(UpdateProfileRequest.class))).thenReturn(account);
        String sneaky = """
                {"id":"%s","email":"evil@example.com","roles":["ROLE_ADMIN"],"displayName":"Sita Sharma"}"""
                .formatted(UUID.randomUUID());

        mockMvc.perform(put("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sneaky))
                .andExpect(status().isOk());

        verify(userService).updateProfile(eq(USER_ID), any(UpdateProfileRequest.class));
    }

    @Test
    void aFormThatLeavesOutTheConsentFlagMeansFalseNotAnUnreadableBody() throws Exception {
        when(userService.updateProfile(eq(USER_ID), any(UpdateProfileRequest.class))).thenReturn(account);

        for (String body : List.of(
                "{\"displayName\":\"Sita Sharma\"}",
                "{\"displayName\":\"Sita Sharma\",\"showPhone\":null}")) {
            mockMvc.perform(put("/api/v1/users/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        org.mockito.ArgumentCaptor<UpdateProfileRequest> forms = org.mockito.ArgumentCaptor.forClass(UpdateProfileRequest.class);
        verify(userService, org.mockito.Mockito.times(2)).updateProfile(eq(USER_ID), forms.capture());
        org.assertj.core.api.Assertions.assertThat(forms.getAllValues()).allSatisfy(form -> {
            org.assertj.core.api.Assertions.assertThat(form.showPhone()).isFalse();
            org.assertj.core.api.Assertions.assertThat(form.phone()).isNull();
        });
    }

    @Test
    void anInvalidFormListsEveryBadField() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"A","phone":"12345","bio":"%s","provinceId":0}""".formatted("b".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(4)));

        verifyNoInteractions(userService);
    }

    @Test
    void aLocationThatDoesNotFitTogetherIsAFieldError() throws Exception {
        when(userService.updateProfile(eq(USER_ID), any(UpdateProfileRequest.class)))
                .thenThrow(new FieldValidationException("districtId", "This district is not in the chosen province."));

        mockMvc.perform(put("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROFILE_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("districtId"));
    }

    // --- GET /users/{id} (public) ---------------------------------------------------------------------------------

    @Test
    void aMembersPublicProfileNeedsNoSignIn() throws Exception {
        when(userService.getPublicProfile(UUID.fromString(PUBLIC_ID))).thenReturn(new PublicUserResponse(
                UUID.fromString(PUBLIC_ID), "Sita Sharma", "Hello", Instant.parse("2026-10-20T08:15:00Z"),
                new LocationNames("Bagmati", "Kathmandu", null), null));

        mockMvc.perform(get("/api/v1/users/" + PUBLIC_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Sita Sharma"))
                .andExpect(jsonPath("$.location.province").value("Bagmati"))
                .andExpect(jsonPath("$.phone").isEmpty())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist());
    }

    @Test
    void anUnknownMemberIsA404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(userService.getPublicProfile(unknown)).thenThrow(new ResourceNotFoundException("User", unknown));

        mockMvc.perform(get("/api/v1/users/" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void thePublicPatternDoesNotOpenAnythingElseUnderUsers() throws Exception {
        // Not a UUID: not the public profile, so the deny-by-default rule applies.
        mockMvc.perform(get("/api/v1/users/someone")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me/password")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);
    }

    @Test
    void thePublicProfileIsReadOnly() throws Exception {
        mockMvc.perform(put("/api/v1/users/" + PUBLIC_ID).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isUnauthorized());
    }

    // --- POST /auth/forgot-password -------------------------------------------------------------------------------

    @Test
    void forgotPasswordIsPublicAndAlwaysAnswersTheSame() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  Sita@Example.com \"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        verify(passwordReset).requestReset("Sita@Example.com");   // trimmed; the service lower-cases
    }

    @Test
    void forgotPasswordNeedsAWellFormedAddress() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nonsense\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));

        verifyNoInteractions(passwordReset);
    }

    @Test
    void forgotPasswordIsRateLimitedBeforeAnyWorkIsDone() throws Exception {
        doThrow(new TooManyRequestsException(Duration.ofMinutes(42))).when(rateLimits).forgotPassword(any(), eq("sita@example.com"));

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"sita@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2520"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.detail").value("Too many attempts. Please try again in 42 minutes."));

        verifyNoInteractions(passwordReset);
    }

    // --- POST /auth/reset-password --------------------------------------------------------------------------------

    @Test
    void resetPasswordIsPublicAndAnswers204() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc123\",\"newPassword\":\"a brand new password\"}"))
                .andExpect(status().isNoContent());

        verify(passwordReset).reset("abc123", "a brand new password");
    }

    @Test
    void aBadLinkIsA400WithItsOwnCodeSoTheWebAppCanOfferANewOne() throws Exception {
        doThrow(new InvalidTokenException("This password reset link is invalid or has expired. Please request a new one."))
                .when(passwordReset).reset(any(), any());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc123\",\"newPassword\":\"a brand new password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void aWeakNewPasswordIsAValidationErrorAndTheTokenIsNotSpent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc123\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));

        verify(passwordReset, never()).reset(any(), any());
    }

    @Test
    void resetPasswordIsRateLimited() throws Exception {
        doThrow(new TooManyRequestsException(Duration.ofSeconds(30))).when(rateLimits).resetPassword(any());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"abc123\",\"newPassword\":\"a brand new password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));

        verifyNoInteractions(passwordReset);
    }

    // --- PUT /users/me/password -----------------------------------------------------------------------------------

    private ResultActions changePassword(String body, boolean signedIn) throws Exception {
        var request = put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signedIn) {
            request.header(HttpHeaders.AUTHORIZATION, bearer());
        }
        return mockMvc.perform(request);
    }

    private static final String CHANGE_JSON = "{\"currentPassword\":\"old password\",\"newPassword\":\"a brand new password\"}";

    @Test
    void changingThePasswordNeedsASignIn() throws Exception {
        changePassword(CHANGE_JSON, false).andExpect(status().isUnauthorized());

        verifyNoInteractions(authService);
    }

    @Test
    void changingThePasswordAnswers204AndClearsTheRefreshCookieBecauseEverySessionEnded() throws Exception {
        changePassword(CHANGE_JSON, true)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("refresh_token=;")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));

        verify(rateLimits).changePassword(USER_ID);
        verify(authService).changePassword(eq(USER_ID), any());
    }

    @Test
    void aWrongCurrentPasswordIsAFieldErrorAndNeverA401() throws Exception {
        doThrow(new FieldValidationException("currentPassword", "This is not your current password."))
                .when(authService).changePassword(eq(USER_ID), any());

        changePassword(CHANGE_JSON, true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"));
    }

    @Test
    void changingThePasswordIsRateLimitedPerUser() throws Exception {
        doThrow(new TooManyRequestsException(Duration.ofMinutes(15))).when(rateLimits).changePassword(USER_ID);

        changePassword(CHANGE_JSON, true)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));

        verifyNoInteractions(authService);
    }

    @Test
    void changingThePasswordNeverEchoesAPassword() throws Exception {
        doThrow(new FieldValidationException("currentPassword", "This is not your current password."))
                .when(authService).changePassword(eq(USER_ID), any());

        String body = changePassword(CHANGE_JSON, true).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("old password", "a brand new password");
    }

    // --- rate limits on the older endpoints -----------------------------------------------------------------------

    @Test
    void signInAndRegistrationAreRateLimitedToo() throws Exception {
        doThrow(new TooManyRequestsException(Duration.ofMinutes(10))).when(rateLimits).login(any());
        doThrow(new TooManyRequestsException(Duration.ofHours(1))).when(rateLimits).register(any());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"sita@example.com\",\"password\":\"correct horse\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "600"));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"sita@example.com\",\"password\":\"correct horse\",\"displayName\":\"Sita\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"));

        verifyNoInteractions(authService);
    }
}
