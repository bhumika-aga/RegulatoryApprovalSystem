package com.enterprise.regulatory.config;

import org.camunda.bpm.engine.rest.security.auth.ProcessEngineAuthenticationFilter;
import org.camunda.bpm.engine.rest.security.auth.impl.HttpBasicAuthenticationProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Camunda Security Configuration.
 *
 * <p>The Camunda webapp (Cockpit/Tasklist/Admin) uses its built-in authentication:
 * only the admin user configured in {@code application.yml} exists in the engine
 * identity service, so the login page is what protects {@code /camunda/**}.
 * Login credentials:
 * <ul>
 *   <li>Username: {@code admin}</li>
 *   <li>Password: value of the {@code CAMUNDA_ADMIN_PASSWORD} environment variable</li>
 * </ul>
 *
 * <p>The raw engine REST API ({@code /engine-rest/**}) is a different surface: it is
 * <em>not</em> used by the webapp (which talks to {@code /camunda/api/**}), only by the
 * external task workers. It used to be anonymously public, which exposed the full engine
 * API to the internet. It is now guarded by Camunda's {@link ProcessEngineAuthenticationFilter}
 * using HTTP Basic authentication against the engine identity service. The external task
 * client authenticates with the admin credentials (see {@code ExternalTaskWorkerConfig}).
 */
@Configuration
public class CamundaSecurityConfig {

    /**
     * Registers Camunda's authentication filter over the engine REST API so that every
     * {@code /engine-rest/*} call must carry valid HTTP Basic credentials.
     */
    @Bean
    public FilterRegistrationBean<ProcessEngineAuthenticationFilter> engineRestAuthenticationFilter() {
        FilterRegistrationBean<ProcessEngineAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setName("camunda-engine-rest-auth");
        registration.setFilter(new ProcessEngineAuthenticationFilter());
        registration.setInitParameters(Map.of(
            "authentication-provider", HttpBasicAuthenticationProvider.class.getName()));
        registration.addUrlPatterns("/engine-rest/*");
        return registration;
    }
}
