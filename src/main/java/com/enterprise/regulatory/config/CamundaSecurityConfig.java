package com.enterprise.regulatory.config;

import org.springframework.context.annotation.Configuration;

/**
 * Camunda Security Configuration.
 *
 * Camunda webapp uses its built-in authentication (admin user configured in
 * application.yml).
 * The custom ContainerBasedAuthenticationFilter has been removed to allow
 * Camunda's native login page to work.
 *
 * Login credentials:
 * - Username: admin
 * - Password: value of CAMUNDA_ADMIN_PASSWORD environment variable
 */
@Configuration
public class CamundaSecurityConfig {
    // Camunda's built-in authentication is used
    // No custom filter registration needed
}
