package com.resalemarket.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The text must not be longer than {@link #value()} bytes when encoded as UTF-8; {@code null} is valid.
 *
 * <p>{@code @Size} counts characters, which is the wrong unit for BCrypt: it only accepts 72 <em>bytes</em>, and
 * a Devanagari character takes 3 bytes, so a 25-character Nepali password is already too long.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "must be at most {value} bytes long (some characters, such as Nepali letters, use 3 bytes each)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
