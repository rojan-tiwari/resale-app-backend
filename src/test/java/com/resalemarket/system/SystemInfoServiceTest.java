package com.resalemarket.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.resalemarket.system.SystemInfoResponse.DatabaseInfo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class SystemInfoServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:15:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Environment environment;

    @Mock
    private ObjectProvider<BuildProperties> buildProperties;

    private SystemInfoService service;

    @BeforeEach
    void setUp() {
        Properties build = new Properties();
        build.setProperty("version", "0.1.0");
        lenient().when(buildProperties.getIfAvailable()).thenReturn(new BuildProperties(build));
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});

        service = new SystemInfoService(
                jdbcTemplate, environment, buildProperties, Clock.fixed(NOW, ZoneOffset.UTC), "resale-market-api");
    }

    @Test
    void reportsApplicationAndDatabaseDetailsWhenHealthy() {
        when(jdbcTemplate.queryForList(startsWith("SELECT current_setting"), eq(String.class)))
                .thenReturn(List.of("17.2"));
        when(jdbcTemplate.queryForList(startsWith("SELECT version FROM flyway_schema_history"), eq(String.class)))
                .thenReturn(List.of("1", "2", "3"));

        SystemInfoResponse info = service.getSystemInfo();

        assertThat(info.application()).isEqualTo("resale-market-api");
        assertThat(info.version()).isEqualTo("0.1.0");
        assertThat(info.environment()).isEqualTo("dev");
        assertThat(info.serverTime()).isEqualTo(NOW);
        assertThat(info.database().status()).isEqualTo(DatabaseInfo.Status.UP);
        assertThat(info.database().version()).isEqualTo("17.2");
        assertThat(info.database().migrationsApplied()).isEqualTo(3);
        assertThat(info.database().latestMigration()).isEqualTo("3");
    }

    @Test
    void reportsTheDefaultProfileWhenNoneIsActive() {
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
        when(environment.getDefaultProfiles()).thenReturn(new String[] {"dev"});

        assertThat(service.getSystemInfo().environment()).isEqualTo("dev");
    }

    @Test
    void reportsDatabaseDownInsteadOfThrowing() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        SystemInfoResponse info = service.getSystemInfo();

        assertThat(info.database().status()).isEqualTo(DatabaseInfo.Status.DOWN);
        assertThat(info.database().version()).isNull();
        assertThat(info.database().migrationsApplied()).isZero();
        assertThat(info.database().latestMigration()).isNull();
        assertThat(info.application()).isEqualTo("resale-market-api");
    }
}
