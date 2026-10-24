package com.resalemarket.common.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CORS policy, bound from {@code app.cors.*}.
 *
 * <p>Origins are listed explicitly (never {@code *}) because credentials are allowed: the browser must be
 * able to send the HttpOnly refresh-token cookie. An empty list disables cross-origin access entirely,
 * which is the right setting when a reverse proxy serves the SPA and the API from one origin.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        List<String> allowedOrigins,
        @DefaultValue({"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}) List<String> allowedMethods,
        @DefaultValue({"Authorization", "Content-Type", "Accept", "X-Requested-With"}) List<String> allowedHeaders,
        @DefaultValue("true") boolean allowCredentials,
        @DefaultValue("1h") Duration maxAge) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
        allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
    }
}
