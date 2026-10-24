package com.resalemarket.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for expected, business-level failures. {@link GlobalExceptionHandler} turns these into
 * RFC 9457 problem responses with the status and {@link ErrorCode} carried here.
 *
 * <p>Throw a subclass from services; never build HTTP responses in business code.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getCode() {
        return code;
    }
}
