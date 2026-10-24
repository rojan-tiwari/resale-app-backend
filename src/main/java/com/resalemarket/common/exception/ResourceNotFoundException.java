package com.resalemarket.common.exception;

import org.springframework.http.HttpStatus;

/** The requested resource does not exist (or the caller is not allowed to know that it exists). */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "%s with id '%s' was not found.".formatted(resource, id));
    }
}
