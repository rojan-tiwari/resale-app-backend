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
import com.resalemarket.common.exception.InvalidTokenException;
import com.resalemarket.common.exception.TooManyRequestsException;
import com.resalemarket.common.security.RestSecurityErrorHandlers;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The HTTP contract of the account endpoints through the <b>real</b> security filter chain, JWT verification and error
 * handler, with the services mocked. No database is needed, so these run everywhere, including machines without Docker; the
 * end-to-end versions with PostgreSQL are the {@code *IntegrationTest} classes.
 */
@WebMvcTest(controllers = {PasswordController.class, AuthController.class})
@Import({SecurityConfig.class, JwtConfig.class, RestSecurityErrorHandlers.class, RefreshCookies.class})
// A web slice does not process @ConfigurationPropertiesScan, so the settings the imported classes need are listed here.
@EnableConfigurationProperties({AuthProperties.class, JwtProperties.class, CorsProperties.class})
@ActiveProfiles("test")
class AccountEndpointsWebTest {


    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private PasswordResetService passwordReset;

    @MockitoBean
    private AuthRateLimits rateLimits;

    /** Needed by {@link SecurityConfig}'s authentication manager; sign-in itself is not exercised here. */
    @MockitoBean
    private UserDetailsService userDetailsService;

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
