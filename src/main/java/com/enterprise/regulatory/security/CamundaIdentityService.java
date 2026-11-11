package com.enterprise.regulatory.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.IdentityService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Propagates the JWT-authenticated user and their roles (as groups) to Camunda's
 * identity context for the duration of a request.
 *
 * <p>Camunda keeps this context in a thread-local, and servlet threads are pooled, so
 * every {@link #setAuthentication} must be paired with {@link #clearAuthentication}
 * once the request is done; otherwise the next request served by the same thread
 * would run as this user.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CamundaIdentityService {
    
    private final IdentityService identityService;
    
    /**
     * Sets the current thread's Camunda identity.
     *
     * @param userId the username from the JWT
     * @param roles  the roles from the JWT, used as the user's Camunda groups
     */
    public void setAuthentication(String userId, List<String> roles) {
        identityService.setAuthentication(userId, roles);
        log.debug("Set Camunda authentication: {} with groups: {}", userId, roles);
    }
    
    /**
     * Clears the current thread's Camunda identity.
     */
    public void clearAuthentication() {
        identityService.clearAuthentication();
    }
}
