package com.resalemarket.common.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Stable, machine-readable error identifiers returned in the {@code code} property of every error response.
 * Clients branch on these codes; the human-readable {@code detail} may change without notice.
 */
public enum ErrorCode {
    BAD_REQUEST,
    VALIDATION_FAILED,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    CONFLICT,
    CONCURRENT_MODIFICATION,
    BUSINESS_RULE_VIOLATION,
    /** A single-use link token (e.g. password reset) is unknown, already used or expired; the client offers to request a new one. */
    INVALID_TOKEN,
    TOO_MANY_REQUESTS,
    /** An upload is larger than the web server allows; the client tells the person to choose smaller files. */
    PAYLOAD_TOO_LARGE,
    INTERNAL_ERROR;

    /** Best-effort mapping for errors raised by the framework itself (e.g. a malformed JSON body). */
    public static ErrorCode fromStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> BUSINESS_RULE_VIOLATION;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 429 -> TOO_MANY_REQUESTS;
            default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
        };
    }
}
