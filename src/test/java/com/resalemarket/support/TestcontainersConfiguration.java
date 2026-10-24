package com.resalemarket.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts a throw-away PostgreSQL for integration tests and wires it in as the application's datasource
 * ({@code @ServiceConnection} replaces the {@code spring.datasource.*} properties). Flyway then runs every
 * migration against it, so the tests exercise the real schema, not an in-memory imitation.
 *
 * <p>The image is kept in step with the Docker Compose service; PostgreSQL 17 is used for both.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }
}
