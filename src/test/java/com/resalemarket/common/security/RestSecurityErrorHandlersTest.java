package com.resalemarket.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * The 401/403 bodies are written by hand inside the security filter chain, so their JSON must be valid and
 * must have the same shape as the problem documents produced by {@code GlobalExceptionHandler}.
 */
class RestSecurityErrorHandlersTest {

    private final RestSecurityErrorHandlers handlers = new RestSecurityErrorHandlers();

    @Test
    void missingCredentialsProduceA401ProblemWithABearerChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/listings");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.commence(request, response, new BadCredentialsException("not shown to clients"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentType()).startsWith("application/problem+json");

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(body, "$.type")).isEqualTo("about:blank");
        assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo("Unauthorized");
        assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(401);
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("UNAUTHORIZED");
        assertThat(JsonPath.<String>read(body, "$.instance")).isEqualTo("/api/v1/listings");
        assertThat(JsonPath.<String>read(body, "$.timestamp")).isNotBlank();
        assertThat(body).doesNotContain("not shown to clients");
    }

    @Test
    void insufficientPermissionsProduceA403ProblemWithoutAChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/admin/users/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.handle(request, response, new AccessDeniedException("role ADMIN required"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(403);
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("FORBIDDEN");
        assertThat(body).doesNotContain("role ADMIN required");
    }

    @Test
    void aRequestPathThatIsNotAValidUriIsStillAnsweredJustWithoutAnInstance() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bad path");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.commence(request, response, new BadCredentialsException("x"));

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(401);
        assertThat(body).doesNotContain("\"instance\"");
    }

    @Test
    void jsonStringsEscapeQuotesBackslashesAndControlCharacters() {
        assertThat(RestSecurityErrorHandlers.quote("a\"b\\c\nd\te\u0001"))
                .isEqualTo("\"a\\\"b\\\\c\\nd\\te\\u0001\"");
        assertThat(RestSecurityErrorHandlers.quote("Kathmandu \u0915\u093e\u0920\u092e\u093e\u0921\u094c\u0902"))
                .startsWith("\"Kathmandu ");
    }
}
