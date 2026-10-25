package com.resalemarket.common.config;

import com.resalemarket.common.security.RestSecurityErrorHandlers;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Phase 1 security baseline: a stateless API that is <b>deny-by-default</b>.
 *
 * <ul>
 *   <li>Only the explicitly listed endpoints are public (reads, and account registration); everything else requires authentication.</li>
 *   <li>No HTTP session is ever created and no login form / basic auth is enabled.</li>
 *   <li>401 and 403 are returned as RFC 9457 problem documents.</li>
 * </ul>
 *
 * <p>Phase 2 adds the authentication endpoints (registration is in; login and the JWT bearer-token filter follow).
 * Accounts are looked up in the database by {@code DatabaseUserDetailsService} and passwords are checked with the
 * {@link #passwordEncoder() PasswordEncoder} below. CSRF protection is disabled because requests are authenticated
 * by an {@code Authorization} header, not by an ambient cookie; the refresh-token cookie (Phase 2) is scoped to the
 * refresh endpoint, SameSite-restricted and requires a JSON/custom-header request, so it cannot be abused cross-site.
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

    /** Anonymous callers may create an account; the other authentication endpoints are added as they are built. */
    private static final String[] PUBLIC_POST_ENDPOINTS = {
        "/api/v1/auth/register",
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
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, API_DOCS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    /**
     * BCrypt, wrapped in a {@link DelegatingPasswordEncoder} so hashes are stored as {@code {bcrypt}$2a$10$...}. The id
     * prefix lets a stronger algorithm (e.g. Argon2) become the default later while existing hashes keep working.
     * Only BCrypt is registered on purpose: the stock factory would also accept legacy {@code {noop}} and {@code {MD5}}
     * hashes. A hash is 7 + 60 characters, which fits {@code users.password_hash VARCHAR(100)}.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder()));
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
