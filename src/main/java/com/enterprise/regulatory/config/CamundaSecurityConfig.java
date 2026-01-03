package com.enterprise.regulatory.config;

import java.util.Collections;

import org.camunda.bpm.webapp.impl.security.auth.ContainerBasedAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Disables Camunda's built-in webapp authentication.
 * Security is handled by Spring Security and JWT authentication instead.
 */
@Configuration
public class CamundaSecurityConfig {

    @Bean
    public FilterRegistrationBean<ContainerBasedAuthenticationFilter> containerBasedAuthenticationFilter() {
        FilterRegistrationBean<ContainerBasedAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ContainerBasedAuthenticationFilter());
        registration.setInitParameters(Collections.singletonMap("authentication-provider",
                "com.enterprise.regulatory.security.CamundaAuthenticationProvider"));
        registration.addUrlPatterns("/camunda/app/*", "/camunda/api/*", "/engine-rest/*");
        registration.setOrder(101); // After Spring Security filter
        return registration;
    }
}
