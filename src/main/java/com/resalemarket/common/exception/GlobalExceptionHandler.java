package com.resalemarket.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindingResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Centralised error handling: every failure leaves the API as an RFC 9457 problem document
 * (see {@link ProblemDetails}). Extending {@link ResponseEntityExceptionHandler} covers the standard
 * Spring MVC exceptions (malformed JSON, unsupported media type, missing parameters, unknown routes, ...).
 *
 * <p>Internal details (stack traces, SQL, constraint names) are logged but never sent to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        log.debug("Request failed with {}: {}", ex.getCode(), ex.getMessage());
        ProblemDetail problem = ProblemDetails.of(ex.getStatus(), ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    /** {@code @Valid @RequestBody} failures: one entry per invalid field in {@code errors}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.");
        problem.setProperty("errors", violations(ex.getBindingResult()));
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Failures of constraints declared directly on parameters (e.g. {@code @Positive @PathVariable}). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid.");
        List<FieldViolation> errors = ex.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        // The constraint name stays in the log; clients only learn that the data conflicts.
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.CONFLICT, ErrorCode.CONFLICT, "The request conflicts with existing data.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLocking(ObjectOptimisticLockingFailureException ex) {
        log.debug("Optimistic locking failure: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.CONFLICT,
                ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified by someone else. Reload it and try again.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Raised by method security ({@code @PreAuthorize}) inside controllers. An anonymous caller is told to
     * authenticate (401); an authenticated caller without permission gets 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = authentication == null || authentication instanceof AnonymousAuthenticationToken;
        if (anonymous) {
            return unauthorized();
        }
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "You do not have permission to access this resource.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return unauthorized();
    }

    /** Last line of defence: log everything, reveal nothing. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Please try again later.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Adds {@code code} and {@code timestamp} to the problems Spring MVC builds for its own exceptions.
     *
     * <p>Most framework exceptions (405, 415, missing parameter, unknown route, ...) arrive here with a
     * {@code null} body; the base class would only create their {@link ProblemDetail} afterwards, so it is
     * created here first to be able to enrich it.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        Object effectiveBody = body;
        if (effectiveBody == null && ex instanceof ErrorResponse errorResponse) {
            effectiveBody = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (effectiveBody instanceof ProblemDetail problem) {
            ProblemDetails.enrich(problem, ErrorCode.fromStatus(statusCode));
        }
        return super.handleExceptionInternal(ex, effectiveBody, headers, statusCode, request);
    }

    private static ResponseEntity<ProblemDetail> unauthorized() {
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication is required to access this resource.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    private static List<FieldViolation> violations(BindingResult bindingResult) {
        List<FieldViolation> violations = new ArrayList<>();
        bindingResult.getFieldErrors().forEach(
                error -> violations.add(new FieldViolation(error.getField(), error.getDefaultMessage())));
        // Class-level constraints (e.g. "passwords must match") are reported under the object name.
        bindingResult.getGlobalErrors().forEach(
                error -> violations.add(new FieldViolation(error.getObjectName(), error.getDefaultMessage())));
        return violations;
    }
}
