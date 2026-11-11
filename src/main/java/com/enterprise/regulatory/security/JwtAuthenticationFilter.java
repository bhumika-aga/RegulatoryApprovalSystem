package com.enterprise.regulatory.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final CamundaIdentityService camundaIdentityService;
    
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);
            Optional<Claims> claims = StringUtils.hasText(jwt) ? tokenProvider.parseAccessToken(jwt) : Optional.empty();
            
            if (claims.isPresent()) {
                String username = claims.get().getSubject();
                List<String> roles = JwtTokenProvider.rolesOf(claims.get());
                String department = JwtTokenProvider.departmentOf(claims.get());
                
                UserPrincipal userPrincipal = UserPrincipal.create(username, department, roles);
                
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userPrincipal,
                    null,
                    userPrincipal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                
                SecurityContextHolder.getContext().setAuthentication(authentication);
                
                // Make the user and their roles (as groups) Camunda's current identity
                camundaIdentityService.setAuthentication(username, roles);
                
                log.debug("Authenticated user: {} with roles: {}", username, roles);
            }
        } catch (Exception ex) {
            log.error("Could not set user authentication in security context", ex);
        }
        
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Camunda's identity is thread-local and servlet threads are pooled: always clear it,
            // so the next request served by this thread does not run as this user.
            camundaIdentityService.clearAuthentication();
        }
    }
    
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader(jwtProperties.getHeaderName());
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(jwtProperties.getTokenPrefix())) {
            return bearerToken.substring(jwtProperties.getTokenPrefix().length());
        }
        return null;
    }
    
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getServletPath();
        // Actuator requests are filtered: metrics and info need an (ADMIN) token.
        return path.startsWith("/api/v1/auth/") ||
                   path.startsWith("/swagger-ui/") ||
                   path.startsWith("/v3/api-docs") ||
                   path.startsWith("/camunda/") ||
                   path.startsWith("/camunda-welcome") ||
                   path.startsWith("/engine-rest/");
    }
}
