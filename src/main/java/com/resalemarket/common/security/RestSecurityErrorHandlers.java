package com.resalemarket.common.security;

import com.resalemarket.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Security failures happen in the filter chain, before Spring MVC (and therefore before
 * {@code GlobalExceptionHandler}) is involved. This component writes the same RFC 9457 problem body
 * ({@code type, title, status, detail, instance, code, timestamp}) so clients see one consistent error
 * format for 401 and 403 as well.
 *
 * <p>The body is assembled by hand on purpose: it has a fixed shape of strings and one number, which keeps the
 * security layer independent of the JSON library (Jackson) and of how the MVC message converters are configured.
 */
@Component
public class RestSecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    /** No (valid) credentials were supplied for a protected resource. */
    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                "Authentication is required to access this resource.");
    }

    /** The caller is authenticated but not allowed to access the resource. */
    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                "You do not have permission to access this resource.");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            ErrorCode code,
            String detail)
            throws IOException {
        StringBuilder json = new StringBuilder(256)
                .append("{\"type\":\"about:blank\"")
                .append(",\"title\":").append(quote(status.getReasonPhrase()))
                .append(",\"status\":").append(status.value())
                .append(",\"detail\":").append(quote(detail));
        String instance = instanceOf(request);
        if (instance != null) {
            json.append(",\"instance\":").append(quote(instance));
        }
        json.append(",\"code\":").append(quote(code.name()))
                .append(",\"timestamp\":").append(quote(Instant.now().toString()))
                .append('}');

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        response.getOutputStream().write(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The request path as a URI reference, or {@code null} when it is not a valid URI (then "instance" is omitted). */
    private static String instanceOf(HttpServletRequest request) {
        try {
            return URI.create(request.getRequestURI()).toASCIIString();
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    /** Renders {@code value} as a JSON string literal, escaping quotes, backslashes and control characters. */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
