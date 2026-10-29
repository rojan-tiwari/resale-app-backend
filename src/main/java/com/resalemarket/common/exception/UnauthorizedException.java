package com.resalemarket.common.exception;

import org.springframework.http.HttpStatus;

/** The caller could not be authenticated (e.g. wrong login credentials or an expired refresh token). */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, message);
    }
}
