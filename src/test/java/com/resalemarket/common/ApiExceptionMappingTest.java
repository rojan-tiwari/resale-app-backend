package com.resalemarket.common;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.common.exception.FieldValidationException;
import com.resalemarket.common.exception.GlobalExceptionHandler;
import com.resalemarket.common.exception.InvalidTokenException;
import com.resalemarket.common.exception.TooManyRequestsException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** How the exceptions added for password reset, profiles and rate limiting appear on the wire. */
class ApiExceptionMappingTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new Thrower())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    void tooManyRequestsIsA429ProblemWithRetryAfter() throws Exception {
        mockMvc.perform(get("/test/too-many"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.detail").value("Too many attempts. Please try again in 5 minutes."));
    }

    @Test
    void aFieldValidationExceptionLooksLikeABeanValidationFailure() throws Exception {
        mockMvc.perform(get("/test/field"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("One or more fields are invalid."))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
                .andExpect(jsonPath("$.errors[0].message").value("This is not your current password."));
    }

    @Test
    void anInvalidTokenIsA400WithItsOwnCode() throws Exception {
        mockMvc.perform(get("/test/token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.detail").value("This link is no good."))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @RestController
    @RequestMapping("/test")
    static class Thrower {

        @GetMapping("/too-many")
        void tooMany() {
            throw new TooManyRequestsException(Duration.ofMinutes(5));
        }

        @GetMapping("/field")
        void field() {
            throw new FieldValidationException("currentPassword", "This is not your current password.");
        }

        @GetMapping("/token")
        void token() {
            throw new InvalidTokenException("This link is no good.");
        }
    }
}
