package com.enterprise.regulatory.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lets a request through only if it comes from this host (a loopback address) and was
 * not relayed by a proxy; anything else gets a 403 before it reaches the protected path.
 *
 * <p>A request carrying {@code Forwarded} or {@code X-Forwarded-For} is refused even
 * from a loopback address, since it was relayed on behalf of another client (e.g. by a
 * reverse proxy on the same host).
 */
@Slf4j
public class LocalOnlyFilter extends OncePerRequestFilter {
    
    static boolean isLocal(HttpServletRequest request) {
        if (request.getHeader("Forwarded") != null || request.getHeader("X-Forwarded-For") != null) {
            return false;
        }
        String address = request.getRemoteAddr();
        return address != null && (address.startsWith("127.")
                                       || address.equals("::1")
                                       || address.equals("0:0:0:0:0:0:0:1")
                                       || address.startsWith("::ffff:127."));
    }
    
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (isLocal(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("Refused {} {} from {}: only reachable from this host",
            request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"message\":\"Only reachable from this host\"}");
    }
}
