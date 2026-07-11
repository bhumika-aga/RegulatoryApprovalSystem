package com.enterprise.regulatory.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class for security-related operations.
 */
@Component
public class SecurityUtils {

    /**
     * Roles with cross-request visibility: they may read workflows and tasks they
     * neither submitted nor are assigned to. These mirror the roles already granted
     * the list/search endpoints ({@code /workflow/user}, {@code /by-status}, audit).
     */
    private static final Set<String> OVERSIGHT_ROLES =
        Set.of("MANAGER", "SENIOR_MANAGER", "ADMIN", "AUDITOR", "COMPLIANCE");
    
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
     * Gets the current user's roles (e.g. {@code REVIEWER}), or an empty set if
     * unauthenticated. Role names match the BPMN candidate-group names.
     */
    public Set<String> getCurrentUserRoles() {
        return getCurrentUser().map(UserPrincipal::getRoles).orElseGet(Collections::emptySet);
    }

    /** Whether the current user holds at least one of the given roles. */
    public boolean currentUserHasAnyRole(Collection<String> roles) {
        Set<String> mine = getCurrentUserRoles();
        return roles.stream().anyMatch(mine::contains);
    }

    /**
     * Whether the current user has an oversight role and may therefore read
     * workflows/tasks they did not submit and are not assigned to.
     */
    public boolean currentUserHasOversight() {
        return currentUserHasAnyRole(OVERSIGHT_ROLES);
    }
}
