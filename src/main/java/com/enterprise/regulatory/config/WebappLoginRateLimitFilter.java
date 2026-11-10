package com.enterprise.regulatory.config;

import com.enterprise.regulatory.security.ClientAddressResolver;
import com.enterprise.regulatory.security.LoginAttemptLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate-limits the Camunda webapp login ({@code POST /camunda/api/admin/auth/user/{engine}/login/{app}})
 * per client address, with the same {@link LoginAttemptLimiter} as the API login.
 *
 * <p>This replaces Camunda's own protection, which locks the <em>user</em> after failed logins
 * and so let anyone keep {@code admin} out of Cockpit by sending wrong passwords for it.
 * That lock is switched off in {@code application.yml}.
 */
@Slf4j
@RequiredArgsConstructor
public class WebappLoginRateLimitFilter extends OncePerRequestFilter {
    
    private final LoginAttemptLimiter limiter;
    private final ClientAddressResolver clientAddressResolver;
    
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().contains("/login/");
    }
    
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String client = clientAddressResolver.resolve(request);
        long retryAfter = limiter.retryAfterSeconds(client);
        if (retryAfter > 0) {
            log.warn("Camunda webapp login refused for {}: too many failed attempts from this address", client);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"type\":\"TooManyRequests\",\"message\":\"Too many failed login attempts; try again in "
                                           + retryAfter + " seconds\"}");
            return;
        }
        
        filterChain.doFilter(request, response);
        
        if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
            limiter.recordFailure(client);
            log.warn("Failed Camunda webapp login from {}", client);
        }
    }
}
