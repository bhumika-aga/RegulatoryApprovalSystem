package com.enterprise.regulatory.config;

import com.enterprise.regulatory.security.ClientAddressResolver;
import com.enterprise.regulatory.security.LoginAttemptLimiter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Camunda Security Configuration.
 *
 * <p>The Camunda webapp (Cockpit/Admin) uses its built-in authentication: only the admin
 * user configured in {@code application.yml} exists in the engine identity service, so
 * the login page is what protects {@code /camunda/**}. Login credentials:
 * <ul>
 *   <li>Username: {@code admin}</li>
 *   <li>Password: value of the {@code CAMUNDA_ADMIN_PASSWORD} environment variable</li>
 * </ul>
 * Tasklist and task completion through the webapp are closed (see {@code SecurityConfig}),
 * so tasks can only be completed through the API, with its role and separation-of-duties
 * checks.
 *
 * <p>Failed webapp logins are rate-limited per client address ({@link WebappLoginRateLimitFilter}),
 * like API logins. Camunda's own protection, which locks the user after failed logins, is switched
 * off: it let anyone keep {@code admin} out of Cockpit by sending wrong passwords for it.
 *
 * <p>The raw engine REST API ({@code /engine-rest/**}) is a different surface: the webapp
 * does not use it (it talks to {@code /camunda/api/**}); only the external task workers
 * do, and they run in this application and call it over {@code localhost}. So it is
 * reachable from this host only, and refused (403) to everyone else.
 *
 * <p>It used to be guarded by HTTP Basic authentication with the admin credentials,
 * which the workers sent too. Camunda locks a user after failed logins (for growing
 * delays, then permanently after 10 attempts), so anyone on the internet could lock
 * {@code admin} with wrong passwords and stall the workers. Without a login on this
 * path, there is nothing for an outsider to lock.
 */
@Configuration
public class CamundaSecurityConfig {

    @Bean
    public FilterRegistrationBean<LocalOnlyFilter> engineRestLocalOnlyFilter() {
        FilterRegistrationBean<LocalOnlyFilter> registration = new FilterRegistrationBean<>(new LocalOnlyFilter());
        registration.setName("camunda-engine-rest-local-only");
        registration.addUrlPatterns("/engine-rest/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
    
    @Bean
    public FilterRegistrationBean<WebappLoginRateLimitFilter> webappLoginRateLimitFilter(
        LoginAttemptLimiter limiter, ClientAddressResolver clientAddressResolver) {
        FilterRegistrationBean<WebappLoginRateLimitFilter> registration =
            new FilterRegistrationBean<>(new WebappLoginRateLimitFilter(limiter, clientAddressResolver));
        registration.setName("camunda-webapp-login-rate-limit");
        registration.addUrlPatterns("/camunda/api/admin/auth/user/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
