package com.resalemarket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the ResaleMarket API (a modular monolith organised by business domain).
 *
 * <p>Spring Boot's default in-memory user (random password) is avoided by the explicit
 * {@code UserDetailsService} bean in {@code SecurityConfig}; Phase 2 replaces it with a database-backed one.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ResaleMarketApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResaleMarketApplication.class, args);
    }
}
