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
@WebMvcTest(controllers = {AuthController.class})
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
    private AuthRateLimits rateLimits;

    /** Needed by {@link SecurityConfig}'s authentication manager; sign-in itself is not exercised here. */
    @MockitoBean
    private UserDetailsService userDetailsService;

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
