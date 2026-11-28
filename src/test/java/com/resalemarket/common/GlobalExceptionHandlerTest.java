package com.resalemarket.common;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.common.exception.BusinessRuleException;
import com.resalemarket.common.exception.ConflictException;
import com.resalemarket.common.exception.ForbiddenException;
import com.resalemarket.common.exception.GlobalExceptionHandler;
import com.resalemarket.common.exception.ResourceNotFoundException;
import com.resalemarket.common.exception.UnauthorizedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies the error contract without starting Spring or a database: every failure must be an RFC 9457
 * problem document carrying a stable {@code code}, and internal details must never leak.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Jackson 3 converter; its default mapper already writes problem-detail extension properties
        // (code, timestamp, errors) as top-level fields, exactly as Spring Boot does.
        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter();

        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    @Test
    void notFoundBecomesA404ProblemWithStableCode() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Widget with id '42' was not found."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void conflictBecomesA409Problem() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Email is already registered."));
    }

    @Test
    void businessRuleViolationBecomesA422Problem() throws Exception {
        mockMvc.perform(get("/test/rule"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void unauthorizedBecomesA401ProblemThatSaysHowToAuthenticate() throws Exception {
        mockMvc.perform(get("/test/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("Invalid email or password."));
    }

    @Test
    void forbiddenBecomesA403Problem() throws Exception {
        mockMvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("This account has been suspended."));
    }

    @Test
    void aFailedAuthenticationBecomesA401() throws Exception {
        mockMvc.perform(get("/test/bad-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void anOutageInsideAuthenticationIsAServerErrorNotAWrongCredential() throws Exception {
        mockMvc.perform(get("/test/auth-outage"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("connection refused"))));
    }

    @Test
    void invalidBodyListsEveryInvalidField() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "quantity")));
    }

    @Test
    void malformedJsonIsABadRequestWithCode() throws Exception {
        mockMvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void frameworkErrorsAlsoCarryTheStableCodeAndTimestamp() throws Exception {
        // GET on a POST-only route: Spring MVC raises 405 itself, with no body for us to enrich up front.
        mockMvc.perform(get("/test/validate"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void databaseConstraintViolationsDoNotLeakConstraintNames() throws Exception {
        mockMvc.perform(get("/test/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(content().string(not(containsString("uq_users_email"))));
    }

    @Test
    void unexpectedErrorsAreGenericAndNeverRevealInternals() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred. Please try again later."))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    /** Throws on demand so each handler can be exercised in isolation. */
    @RestController
    @RequestMapping("/test")
    static class FailingController {

        @GetMapping("/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Widget", 42);
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("Email is already registered.");
        }

        @GetMapping("/unauthorized")
        void unauthorized() {
            throw new UnauthorizedException("Invalid email or password.");
        }

        @GetMapping("/bad-credentials")
        void badCredentials() {
            throw new BadCredentialsException("bad");
        }

        @GetMapping("/auth-outage")
        void authOutage() {
            throw new InternalAuthenticationServiceException("connection refused");
        }

        @GetMapping("/forbidden")
        void forbidden() {
            throw new ForbiddenException("This account has been suspended.");
        }

        @GetMapping("/rule")
        void rule() {
            throw new BusinessRuleException("A draft cannot be marked as sold.");
        }

        @GetMapping("/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_users_email\"");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody NewWidget body) {
            // reaching here means the body was valid
        }
    }

    record NewWidget(@NotBlank String name, @Min(1) int quantity) {
    }
}
