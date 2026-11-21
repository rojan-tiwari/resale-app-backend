package com.resalemarket.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on {@code @Async}. It is used where the caller must get the same answer in the same time whatever happens next
 * (the forgot-password request, whose speed must not reveal whether the address has an account). Spring Boot's own
 * application task executor runs the work.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
