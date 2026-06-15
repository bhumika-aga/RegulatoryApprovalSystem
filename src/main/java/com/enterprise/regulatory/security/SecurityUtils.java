package com.enterprise.regulatory.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
}
