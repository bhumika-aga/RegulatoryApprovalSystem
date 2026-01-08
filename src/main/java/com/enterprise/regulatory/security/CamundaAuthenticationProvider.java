package com.enterprise.regulatory.security;

import java.security.Principal;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.rest.security.auth.AuthenticationResult;
import org.camunda.bpm.engine.rest.security.auth.impl.ContainerBasedAuthenticationProvider;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Custom authentication provider for Camunda that integrates with Spring
 * Security.
 * Uses the authenticated principal from Spring Security context.
 *
 * Security: Requires valid authentication - no anonymous access allowed.
 */
public class CamundaAuthenticationProvider extends ContainerBasedAuthenticationProvider {

    @Override
    public AuthenticationResult extractAuthenticatedUser(HttpServletRequest request, ProcessEngine engine) {
        Principal principal = request.getUserPrincipal();

        if (principal == null) {
            return AuthenticationResult.unsuccessful();
        }

        String name = principal.getName();
        if (name == null || name.isEmpty()) {
            return AuthenticationResult.unsuccessful();
        }

        AuthenticationResult result = new AuthenticationResult(name, true);
        result.setGroups(getUserGroups(name, engine));
        return result;
    }

    private List<String> getUserGroups(String userId, ProcessEngine engine) {
        try {
            return engine.getIdentityService()
                    .createGroupQuery()
                    .groupMember(userId)
                    .list()
                    .stream()
                    .map(group -> group.getId())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
