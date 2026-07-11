package com.enterprise.regulatory.security;

import com.enterprise.regulatory.security.AuthUserService.StoredUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuthUserService} — credential verification and lookup,
 * without a Spring context.
 */
class AuthUserServiceTest {

    private AuthUserService service;

    @BeforeEach
    void setUp() {
        AuthUserProperties.User user = new AuthUserProperties.User();
        user.setUsername("Alice");
        user.setPassword("s3cret-pw");
        user.setRoles(List.of("REVIEWER"));
        user.setDepartment("OPERATIONS");

        AuthUserProperties properties = new AuthUserProperties();
        properties.setUsers(List.of(user));

        service = new AuthUserService(properties, new BCryptPasswordEncoder());
        service.init(); // @PostConstruct is not invoked outside a Spring context
    }

    @Test
    void authenticatesWithCorrectPassword() {
        Optional<StoredUser> result = service.authenticate("Alice", "s3cret-pw");

        assertThat(result).isPresent();
        assertThat(result.get().getRoles()).containsExactly("REVIEWER");
        assertThat(result.get().getDepartment()).isEqualTo("OPERATIONS");
    }

    @Test
    void isCaseInsensitiveOnUsername() {
        assertThat(service.authenticate("alice", "s3cret-pw")).isPresent();
        assertThat(service.findByUsername("ALICE")).isPresent();
    }

    @Test
    void rejectsWrongPassword() {
        assertThat(service.authenticate("Alice", "wrong")).isEmpty();
    }

    @Test
    void rejectsUnknownUser() {
        assertThat(service.authenticate("mallory", "s3cret-pw")).isEmpty();
    }

    @Test
    void rejectsNullArguments() {
        assertThat(service.authenticate(null, "x")).isEmpty();
        assertThat(service.authenticate("Alice", null)).isEmpty();
    }

    @Test
    void storesPasswordHashedNotInPlainText() {
        StoredUser user = service.findByUsername("Alice").orElseThrow();

        assertThat(user.getPasswordHash()).isNotEqualTo("s3cret-pw");
        assertThat(user.getPasswordHash()).startsWith("$2"); // BCrypt prefix
    }
}
