package com.enterprise.regulatory.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "app.security.jwt")
@Validated
@Getter
@Setter
public class JwtProperties {

    @NotBlank(message = "JWT secret key must be configured")
    private String secretKey;

    @Positive
    private long expirationMs = 86400000; // 24 hours default

    @Positive
    private long refreshExpirationMs = 604800000; // 7 days default

    private String issuer = "regulatory-approval-system";

    private String tokenPrefix = "Bearer ";

    private String headerName = "Authorization";
}
