package com.enterprise.regulatory.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Writes a clean {@code 403 Forbidden} JSON body when an authenticated user
 * lacks the role required for a resource.
 *
 * <p>
 * Handling the denial here (instead of the default {@code sendError}) avoids an
 * {@code /error} re-dispatch. On that re-dispatch the stateless JWT filter does
 * not run, so the request would otherwise be treated as anonymous and reported
 * as {@code 401} rather than {@code 403}.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAccessDeniedHandler implements AccessDeniedHandler {
    
    private final ObjectMapper objectMapper;
    
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Access denied for {}: {}", request.getServletPath(), accessDeniedException.getMessage());
        
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        
        Map<String, Object> body = Map.of(
            "status", HttpServletResponse.SC_FORBIDDEN,
            "error", "Forbidden",
            "message", "You do not have permission to access this resource",
            "path", request.getServletPath(),
            "timestamp", LocalDateTime.now().toString());
        
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
