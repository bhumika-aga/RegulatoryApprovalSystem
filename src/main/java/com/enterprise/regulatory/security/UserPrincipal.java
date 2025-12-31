package com.enterprise.regulatory.security;

import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Builder
public class UserPrincipal implements UserDetails {

    private final String username;
    private final String email;
    private final String fullName;
    private final String department;
    private final Set<String> roles;
    private final Collection<? extends GrantedAuthority> authorities;

    public static UserPrincipal create(String username, String email, String fullName,
                                        String department, List<String> roles) {
        Set<String> roleSet = Set.copyOf(roles);
        List<GrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .collect(Collectors.toList());

        return UserPrincipal.builder()
                .username(username)
                .email(email)
                .fullName(fullName)
                .department(department)
                .roles(roleSet)
                .authorities(authorities)
                .build();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return null; // JWT-based auth doesn't use password in principal
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public boolean hasRole(String role) {
        return roles.contains(role.toUpperCase());
    }

    public boolean hasAnyRole(String... checkRoles) {
        for (String role : checkRoles) {
            if (roles.contains(role.toUpperCase())) {
                return true;
            }
        }
        return false;
    }
}
