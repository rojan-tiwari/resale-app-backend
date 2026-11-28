package com.resalemarket.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A single-use token that arrived in a link (such as a password-reset token) cannot be used: it is unknown, was already
 * used or has expired. The three cases are deliberately not told apart, so the answer reveals nothing about which tokens exist.
 */
public class InvalidTokenException extends ApiException {

    public InvalidTokenException(String message) {
        super(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TOKEN, message);
    }
}
