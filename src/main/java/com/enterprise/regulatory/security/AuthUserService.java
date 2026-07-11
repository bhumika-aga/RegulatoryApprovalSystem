package com.enterprise.regulatory.security;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory credential store for the token endpoint.
 *
 * <p>Loads the configured users (see {@link AuthUserProperties}), hashes each
 * password once at startup with the {@link PasswordEncoder}, and verifies
 * supplied credentials at login. Roles and department come from configuration
 * and are authoritative — a client cannot elevate its own privileges by asking
 * for roles it was not granted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthUserService {
    
    private final AuthUserProperties properties;
    private final PasswordEncoder passwordEncoder;
    
    private final Map<String, StoredUser> usersByName = new ConcurrentHashMap<>();
    
    @PostConstruct
    void init() {
        for (AuthUserProperties.User user : properties.getUsers()) {
            String key = user.getUsername().toLowerCase();
            usersByName.put(key, new StoredUser(
                user.getUsername(),
                passwordEncoder.encode(user.getPassword()),
                List.copyOf(user.getRoles()),
                user.getDepartment()));
        }
        if (usersByName.isEmpty()) {
            log.warn("No auth users configured under app.security.auth.users — "
                         + "the token endpoint will reject all logins.");
        } else {
            log.info("Loaded {} auth user(s): {}", usersByName.size(),
                usersByName.values().stream().map(StoredUser::username).collect(Collectors.toList()));
        }
    }
    
    /**
     * Verifies a username/password pair. Returns the stored user on success,
     * or empty on unknown user or bad password (callers must not distinguish
     * the two in their response).
     */
    public Optional<StoredUser> authenticate(String username, String rawPassword) {
        if (username == null || rawPassword == null) {
            return Optional.empty();
        }
        StoredUser user = usersByName.get(username.toLowerCase());
        if (user == null || !passwordEncoder.matches(rawPassword, user.passwordHash())) {
            return Optional.empty();
        }
        return Optional.of(user);
    }
    
    /**
     * Looks up a user by name without a password check — used when refreshing tokens.
     */
    public Optional<StoredUser> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(usersByName.get(username.toLowerCase()));
    }
    
    /**
     * Immutable view of a configured user with its hashed password.
     */
    @Getter
    public static final class StoredUser {
        private final String username;
        private final String passwordHash;
        private final List<String> roles;
        private final String department;
        
        StoredUser(String username, String passwordHash, List<String> roles, String department) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.roles = roles;
            this.department = department;
        }
        
        public String username() {
            return username;
        }
        
        public String passwordHash() {
            return passwordHash;
        }
        
        public List<String> roles() {
            return roles;
        }
        
        public String department() {
            return department;
        }
    }
}
