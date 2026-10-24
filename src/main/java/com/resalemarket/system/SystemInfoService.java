package com.resalemarket.system;

import com.resalemarket.system.SystemInfoResponse.DatabaseInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Collects the information behind the ping endpoint. A database failure is reported as
 * {@code DOWN} in the payload instead of an error, so the UI can show exactly what is broken.
 */
@Service
public class SystemInfoService {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoService.class);

    private static final String SERVER_VERSION_SQL = "SELECT current_setting('server_version')";
    private static final String APPLIED_MIGRATIONS_SQL =
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank";

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final Clock clock;
    private final String applicationName;

    public SystemInfoService(
            JdbcTemplate jdbcTemplate,
            Environment environment,
            ObjectProvider<BuildProperties> buildProperties,
            Clock clock,
            @Value("${spring.application.name}") String applicationName) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.buildProperties = buildProperties;
        this.clock = clock;
        this.applicationName = applicationName;
    }

    public SystemInfoResponse getSystemInfo() {
        return new SystemInfoResponse(applicationName, version(), activeProfiles(), Instant.now(clock), databaseInfo());
    }

    private String version() {
        BuildProperties build = buildProperties.getIfAvailable();
        return build != null ? build.getVersion() : "dev";
    }

    /** Active profiles, or the default profile(s) (e.g. {@code dev}) when none is set explicitly. */
    private String activeProfiles() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }
        return String.join(",", profiles);
    }

    private DatabaseInfo databaseInfo() {
        try {
            List<String> serverVersion = jdbcTemplate.queryForList(SERVER_VERSION_SQL, String.class);
            List<String> migrations = jdbcTemplate.queryForList(APPLIED_MIGRATIONS_SQL, String.class);
            return new DatabaseInfo(
                    DatabaseInfo.Status.UP,
                    serverVersion.isEmpty() ? null : serverVersion.get(0),
                    migrations.size(),
                    migrations.isEmpty() ? null : migrations.get(migrations.size() - 1));
        } catch (DataAccessException ex) {
            log.warn("Database health check failed: {}", ex.getMessage());
            return new DatabaseInfo(DatabaseInfo.Status.DOWN, null, 0, null);
        }
    }
}
