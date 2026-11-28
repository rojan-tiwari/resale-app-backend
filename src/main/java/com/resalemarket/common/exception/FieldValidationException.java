package com.resalemarket.common.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A request field is wrong for a reason that only the service can tell (the current password does not match, a
 * district is not in the chosen province). It answers exactly like a Bean Validation failure: 400
 * {@link ErrorCode#VALIDATION_FAILED} with the offending fields in {@code errors}, so clients show the message next to the field.
 */
public class FieldValidationException extends ApiException {

    private final List<FieldViolation> violations;

    public FieldValidationException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.");
        this.violations = List.of(new FieldViolation(field, message));
    }

    public List<FieldViolation> getViolations() {
        return violations;
    }
}
