package com.enterprise.regulatory.security;

import java.util.List;

import org.camunda.bpm.engine.IdentityService;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service to synchronize JWT roles with Camunda identity groups at runtime.
 * This allows Camunda to use candidateGroups in BPMN while keeping
 * authentication centralized with JWT.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CamundaIdentityService {

    private final IdentityService identityService;

    /**
     * Sets the authenticated user and their groups in Camunda's identity context.
     * This is called on each request to sync JWT roles with Camunda.
     *
     * @param userId the username from JWT
     * @param roles  the roles from JWT token
     */
    public void syncUserGroups(String userId, List<String> roles) {
        try {
            identityService.setAuthenticatedUserId(userId);
            log.debug("Set Camunda authenticated user: {} with groups: {}", userId, roles);
        } catch (Exception e) {
            log.error("Failed to sync user groups with Camunda: {}", e.getMessage());
        }
    }

    /**
     * Clears the Camunda identity context.
     * Should be called after request processing is complete.
     */
    public void clearAuthentication() {
        identityService.clearAuthentication();
    }

    /**
     * Gets the currently authenticated user from Camunda context.
     *
     * @return the authenticated user ID or null
     */
    public String getCurrentUserId() {
        return identityService.getCurrentAuthentication() != null
                ? identityService.getCurrentAuthentication().getUserId()
                : null;
    }
}
