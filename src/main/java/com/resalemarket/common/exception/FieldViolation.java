package com.resalemarket.common.exception;

/** One invalid field of a request; serialised as an element of the {@code errors} array of a validation problem. */
public record FieldViolation(String field, String message) {
}
