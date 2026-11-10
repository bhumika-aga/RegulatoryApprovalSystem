package com.enterprise.regulatory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Checks that the Camunda identity set for a request never outlives it.
 */
class JwtAuthenticationFilterTest {
    
    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(properties());
    private final CamundaIdentityService camundaIdentity = mock(CamundaIdentityService.class);
    private final JwtAuthenticationFilter filter =
        new JwtAuthenticationFilter(tokenProvider, properties(), camundaIdentity);
    
    private static JwtProperties properties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(Base64.getEncoder().encodeToString(new byte[64]));
        return properties;
    }
    
    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setServletPath("/api/v1/tasks");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
    
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
    
    @Test
    void setsTheCamundaIdentityWithRolesAndClearsItAfterTheRequest() throws Exception {
        String token = tokenProvider.generateToken("alice", List.of("MANAGER"), "OPERATIONS");
        filter.doFilter(requestWithToken(token), new MockHttpServletResponse(),
            (req, res) -> verify(camundaIdentity, never()).clearAuthentication());
        
        verify(camundaIdentity).setAuthentication("alice", List.of("MANAGER"));
        verify(camundaIdentity).clearAuthentication();
    }
    
    @Test
    void clearsTheCamundaIdentityWhenTheRequestFails() {
        String token = tokenProvider.generateToken("alice", List.of("MANAGER"), "OPERATIONS");
        FilterChain failing = (req, res) -> {
            throw new ServletException("boom");
        };
        
        assertThatThrownBy(() -> filter.doFilter(requestWithToken(token), new MockHttpServletResponse(), failing))
            .isInstanceOf(ServletException.class);
        verify(camundaIdentity).clearAuthentication();
    }
    
    @Test
    void doesNotAuthenticateWithARefreshToken() throws Exception {
        String refresh = tokenProvider.generateRefreshToken("alice");
        filter.doFilter(requestWithToken(refresh), new MockHttpServletResponse(),
            (req, res) -> assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());
        
        verify(camundaIdentity, never()).setAuthentication(anyString(), anyList());
    }
}
