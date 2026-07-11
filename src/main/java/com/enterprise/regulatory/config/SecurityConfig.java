package com.enterprise.regulatory.config;

import com.enterprise.regulatory.security.JwtAccessDeniedHandler;
import com.enterprise.regulatory.security.JwtAuthenticationEntryPoint;
import com.enterprise.regulatory.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

    /**
     * Whether the H2 console is exposed. Off by default; enabled only via the
     * {@code dev} profile (see {@code application-dev.yml}). When false the
     * console path is not permitted and frames stay denied.
     */
    @Value("${app.security.h2-console-exposed:false}")
    private boolean h2ConsoleExposed;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .exceptionHandling(exception -> exception
                                                .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                                                .accessDeniedHandler(jwtAccessDeniedHandler))
            .sessionManagement(session -> session
                                              .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth
                    // Public endpoints
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    // Health probe stays public for the platform (Render) health check;
                    // all other actuator endpoints (metrics, info, ...) require authentication.
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/**").authenticated()
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                        "/v3/api-docs/**")
                    .permitAll()

                    // Camunda webapp - guarded by Camunda's own login page (only the
                    // configured admin user exists), so Spring delegates auth to it.
                    .requestMatchers("/camunda/**").permitAll()
                    .requestMatchers("/camunda-welcome/**").permitAll()
                    // Engine REST API - guarded by Camunda's ProcessEngineAuthenticationFilter
                    // (HTTP Basic, see CamundaSecurityConfig). No longer anonymously public.
                    .requestMatchers("/engine-rest/**").permitAll();

                // H2 Console - only reachable when explicitly exposed (dev profile).
                if (h2ConsoleExposed) {
                    auth.requestMatchers("/h2-console/**").permitAll();
                }

                auth
                    // Workflow endpoints - role-based
                    .requestMatchers(HttpMethod.POST, "/api/v1/workflow/start")
                    .hasAnyRole("REVIEWER", "MANAGER", "ADMIN")
                    .requestMatchers("/api/v1/workflow/**").authenticated()

                    // Task endpoints - role-based
                    .requestMatchers("/api/v1/tasks/**").authenticated()

                    // Audit endpoints - restricted
                    .requestMatchers("/api/v1/audit/**")
                    .hasAnyRole("AUDITOR", "ADMIN", "COMPLIANCE")

                    // Admin endpoints
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                    // All other requests require authentication
                    .anyRequest().authenticated();
            })
            // Frames are denied by default; only relaxed for the H2 console when exposed (dev).
            .headers(headers -> {
                if (h2ConsoleExposed) {
                    headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin);
                }
            })
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers"));
        configuration.setExposedHeaders(List.of("Authorization", "X-Total-Count"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
