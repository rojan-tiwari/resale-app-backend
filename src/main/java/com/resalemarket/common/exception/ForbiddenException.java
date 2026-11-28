package com.resalemarket.common.exception;

import org.springframework.http.HttpStatus;

/** The caller is known but not allowed to do this (e.g. signing in to a suspended account). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, message);
    }
}
