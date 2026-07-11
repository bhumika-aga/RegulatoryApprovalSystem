package com.enterprise.regulatory.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Configured set of users allowed to obtain a token from {@code /api/v1/auth/token}.
 *
 * <p>This is a self-contained stand-in for an external identity provider: users
 * (and their roles) are defined in configuration rather than a database, which
 * suits the in-memory H2 / demo deployment. Passwords are supplied in raw form
 * here and hashed once at startup by {@link AuthUserService} — they are never
 * stored or compared in plain text at request time. Override the passwords via
 * environment variables in any real deployment.
 */
@Component
@ConfigurationProperties(prefix = "app.security.auth")
@Validated
@Getter
@Setter
public class AuthUserProperties {
    
    /**
     * Configured users. Roles here are authoritative; clients cannot request roles.
     */
    private List<User> users = new ArrayList<>();
    
    @Getter
    @Setter
    public static class User {
        
        @NotBlank(message = "A configured auth user requires a username")
        private String username;
        
        @NotBlank(message = "A configured auth user requires a password")
        private String password;
        
        private List<String> roles = new ArrayList<>();
        
        private String department;
    }
}
