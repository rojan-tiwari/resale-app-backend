package com.resalemarket.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for Swagger UI ({@code /swagger-ui.html}, development only). Declares the bearer-token
 * scheme that Phase 2 endpoints reference, so the "Authorize" button works as soon as login exists.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI resaleMarketOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ResaleMarket API")
                        .version("v1")
                        .description("REST API of the ResaleMarket second-hand marketplace for Nepal. "
                                + "All endpoints live under /api/v1. Errors follow RFC 9457 (application/problem+json)."))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
