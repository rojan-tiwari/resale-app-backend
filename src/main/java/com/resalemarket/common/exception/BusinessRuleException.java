package com.resalemarket.common.exception;

import org.springframework.http.HttpStatus;

/** The request is well-formed but violates a business rule (e.g. an invalid listing status transition). */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }
}
