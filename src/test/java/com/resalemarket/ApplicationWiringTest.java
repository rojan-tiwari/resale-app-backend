package com.resalemarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.resalemarket.auth.AuthProperties;
import com.resalemarket.common.config.FrontendProperties;
import com.resalemarket.common.mail.EmailProperties;
import com.resalemarket.common.mail.EmailSender;
import com.resalemarket.common.storage.StorageProperties;
import com.resalemarket.common.storage.StorageService;
import com.resalemarket.favorite.FavoriteProperties;
import com.resalemarket.listing.ListingProperties;
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

    @Autowired
    private EmailProperties emailProperties;

    @Autowired
    private FrontendProperties frontendProperties;

    @Autowired
    private EmailSender emailSender;

    @Autowired
    private ListingProperties listingProperties;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private FavoriteProperties favoriteProperties;

    @Autowired
    private StorageService storageService;

    @Test
    void theApplicationStartsAndAllItsBeansWire() {
        assertThat(context.getBeanNamesForType(com.resalemarket.auth.PasswordController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(com.resalemarket.user.UserController.class)).hasSize(1);
    }

    @Test
    void theRealYamlBindsToTheSettings() {
        assertThat(authProperties.passwordResetTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(authProperties.maxFailedLogins()).isEqualTo(5);
        assertThat(authProperties.lockoutDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(authProperties.rateLimits().login().attempts()).isEqualTo(100_000);   // application-test.yml
        assertThat(frontendProperties.url()).isEqualTo("http://localhost:4200");
    }

    @Test
    void developmentAndTestsLogMailInsteadOfSendingIt() {
        assertThat(emailProperties.transport()).isEqualTo("log");
        assertThat(emailSender.getClass().getSimpleName()).isEqualTo("LoggingEmailSender");
    }

    @Test
    void theListingSettingsBindFromTheRealYaml() {
        assertThat(listingProperties.requireReview()).isFalse();                      // new listings go live at once by default
        assertThat(listingProperties.maxImageSize().toMegabytes()).isEqualTo(5);
        assertThat(listingProperties.rateLimits().create().attempts()).isEqualTo(100_000);   // application-test.yml
        assertThat(new ListingProperties.RateLimits(null, null).create().attempts()).isEqualTo(30);   // the built-in defaults
        assertThat(new ListingProperties.RateLimits(null, null).imageUpload().attempts()).isEqualTo(120);
    }

    @Test
    void photosAreStoredOnLocalDiskUnderTheConfiguredRoot() {
        assertThat(storageProperties.type()).isEqualTo("local");
        assertThat(storageProperties.root().toString()).isEqualTo("target/test-uploads");   // application-test.yml
        assertThat(storageService.getClass().getSimpleName()).isEqualTo("LocalDiskStorageService");
    }

    @Test
    void theFavouriteSettingsBindFromTheRealYaml() {
        assertThat(favoriteProperties.maxPerUser()).isEqualTo(500);
        assertThat(favoriteProperties.rateLimits().write().attempts()).isEqualTo(100_000);   // application-test.yml
        assertThat(context.getBeanNamesForType(com.resalemarket.discovery.RecommendationController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(com.resalemarket.favorite.FavoriteController.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(com.resalemarket.listing.ListingSearchController.class)).hasSize(1);
    }
}
