package com.resalemarket.system;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Response of {@code GET /api/v1/system/ping}: who is answering and whether the database is healthy. */
@Schema(description = "Application and database status")
public record SystemInfoResponse(
        @Schema(example = "resale-market-api") String application,
        @Schema(example = "0.1.0-SNAPSHOT") String version,
        @Schema(description = "Active Spring profile(s)", example = "dev") String environment,
        @Schema(description = "Current server time (UTC)") Instant serverTime,
        DatabaseInfo database) {

    @Schema(description = "PostgreSQL connectivity and Flyway migration state")
    public record DatabaseInfo(
            Status status,
            @Schema(description = "PostgreSQL server version, null when the database is down", example = "16.4")
                    String version,
            @Schema(description = "Number of successfully applied Flyway migrations") int migrationsApplied,
            @Schema(description = "Latest applied Flyway version", example = "10") String latestMigration) {

        public enum Status {
            UP,
            DOWN
        }
    }
}
