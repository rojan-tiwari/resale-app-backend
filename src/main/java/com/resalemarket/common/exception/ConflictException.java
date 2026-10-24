package com.resalemarket.common.exception;

import org.springframework.http.HttpStatus;

/** The request conflicts with the current state of a resource (e.g. an email address that is already registered). */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, ErrorCode.CONFLICT, message);
    }
}
