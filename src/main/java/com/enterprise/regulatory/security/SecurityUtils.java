package com.enterprise.regulatory.security;

import java.util.Optional;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Utility class for security-related operations.
 */
@Component
public class SecurityUtils {

    /**
     * Gets the current authenticated user principal.
     *
     * @return Optional containing the UserPrincipal if authenticated
     */
    public Optional<UserPrincipal> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal) {
            return Optional.of((UserPrincipal) authentication.getPrincipal());
        }
        return Optional.empty();
    }

    /**
     * Gets the current authenticated username.
     *
     * @return the username or "system" if not authenticated
     */
    public String getCurrentUsername() {
        return getCurrentUser()
                .map(UserPrincipal::getUsername)
                .orElse("system");
    }

    /**
     * Gets the current user's department.
     *
     * @return Optional containing the department
     */
    public Optional<String> getCurrentUserDepartment() {
        return getCurrentUser().map(UserPrincipal::getDepartment);
    }

    /**
     * Gets the current user's roles.
     *
     * @return Set of role names
     */
    public Set<String> getCurrentUserRoles() {
        return getCurrentUser()
                .map(UserPrincipal::getRoles)
                .orElse(Set.of());
    }

    /**
     * Checks if the current user has a specific role.
     *
     * @param role the role to check
     * @return true if the user has the role
     */
    public boolean hasRole(String role) {
        return getCurrentUser()
                .map(user -> user.hasRole(role))
                .orElse(false);
    }

    /**
     * Checks if the current user has any of the specified roles.
     *
     * @param roles roles to check
     * @return true if the user has any of the roles
     */
    public boolean hasAnyRole(String... roles) {
        return getCurrentUser()
                .map(user -> user.hasAnyRole(roles))
                .orElse(false);
    }

    /**
     * Checks if the current user is an admin.
     *
     * @return true if user has ADMIN role
     */
    public boolean isAdmin() {
        return hasRole("ADMIN");
    }

    /**
     * Checks if the current user can view audit logs.
     *
     * @return true if user has audit access
     */
    public boolean canViewAudit() {
        return hasAnyRole("ADMIN", "AUDITOR", "COMPLIANCE");
    }
}
