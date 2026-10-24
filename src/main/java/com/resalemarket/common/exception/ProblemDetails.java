package com.resalemarket.common.exception;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the single error shape used by the whole API: an RFC 9457 {@link ProblemDetail}
 * ({@code type, title, status, detail, instance}) extended with
 * <ul>
 *   <li>{@code code} – a stable {@link ErrorCode} name,</li>
 *   <li>{@code timestamp} – when the error was produced (UTC),</li>
 *   <li>{@code errors} – field violations, for validation failures only.</li>
 * </ul>
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatusCode status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        enrich(problem, code);
        return problem;
    }

    /** Adds {@code code} and {@code timestamp} unless they are already present. */
    public static void enrich(ProblemDetail problem, ErrorCode fallbackCode) {
        Map<String, Object> properties = problem.getProperties();
        if (properties == null || !properties.containsKey("code")) {
            problem.setProperty("code", fallbackCode.name());
        }
        if (properties == null || !properties.containsKey("timestamp")) {
            problem.setProperty("timestamp", Instant.now());
        }
    }
}
