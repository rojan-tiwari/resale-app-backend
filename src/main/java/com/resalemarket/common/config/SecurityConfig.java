package com.resalemarket.common.config;

import com.resalemarket.common.security.RestSecurityErrorHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Phase 1 security baseline: a stateless API that is <b>deny-by-default</b>.
 *
 * <ul>
 *   <li>Only the explicitly listed read-only endpoints are public; everything else requires authentication.</li>
 *   <li>No HTTP session is ever created and no login form / basic auth is enabled.</li>
 *   <li>401 and 403 are returned as RFC 9457 problem documents.</li>
 * </ul>
 *
 * <p>Phase 2 adds the JWT bearer-token filter and the authentication endpoints. CSRF protection is disabled
 * because requests are authenticated by an {@code Authorization} header, not by an ambient cookie; the
 * refresh-token cookie (Phase 2) is scoped to the refresh endpoint, SameSite-restricted and requires a
 * JSON/custom-header request, so it cannot be abused cross-site.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Public read-only API endpoints. Later phases append (e.g. listings, categories, public seller profiles). */
    private static final String[] PUBLIC_GET_ENDPOINTS = {
        "/api/v1/system/ping",
        "/api/v1/locations/**",
    };

    /** Interactive API documentation; only reachable when springdoc is enabled (development). */
    private static final String[] API_DOCS = {
        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            RestSecurityErrorHandlers errorHandlers)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, API_DOCS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    /**
     * There are no user accounts until Phase 2. Declaring a {@link UserDetailsService} stops Spring Boot from
     * generating its default in-memory user with a random password (a login nobody asked for). Phase 2 replaces
     * this bean with the database-backed implementation.
     */
    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("No user accounts exist yet.");
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(properties.allowedMethods());
        configuration.setAllowedHeaders(properties.allowedHeaders());
        configuration.setAllowCredentials(properties.allowCredentials());
        configuration.setMaxAge(properties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
