package com.resalemarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.auth.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Starts the whole application <b>without a database</b>: every bean must wire, the real {@code application*.yml} must bind,
 * and every Spring Data query (derived or {@code @Query}) must compile against the entity model, which Spring checks at
 * startup. It cannot check the SQL schema (that is what the Testcontainers tests with PostgreSQL are for), but it catches the
 * mistakes that otherwise only show when the API is started: a missing bean, a misspelt property, a broken JPQL query.
 * Runs on machines without Docker.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",                                   // there is no database to migrate
    "spring.jpa.hibernate.ddl-auto=none",                            // nor one to validate the mapping against
    "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.datasource.hikari.initialization-fail-timeout=-1",       // do not connect while starting
    "spring.datasource.url=jdbc:postgresql://localhost:1/none",
    "spring.datasource.username=none",
    "spring.datasource.password=none",
})
@ActiveProfiles("test")
class ApplicationWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AuthProperties authProperties;

    @Test
    void theApplicationStartsAndAllItsBeansWire() {
        assertThat(context.getBeanNamesForType(com.resalemarket.auth.AuthController.class)).hasSize(1);
    }

    @Test
    void theRealYamlBindsToTheSettings() {
        assertThat(authProperties.maxFailedLogins()).isEqualTo(5);
        assertThat(authProperties.lockoutDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(authProperties.rateLimits().login().attempts()).isEqualTo(100_000);   // application-test.yml
    }

}
