package com.enterprise.regulatory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logins, token types and refresh, and login rate limiting (API and Cockpit).
 */
class AuthenticationIntegrationTest extends IntegrationTestBase {
    
    @Test
    void tokenEndpointRejectsMissingPassword() {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/auth/token", null,
            Map.of("username", "admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }
    
    @Test
    void tokenEndpointRejectsWrongPassword() {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/auth/token", null,
            Map.of("username", "admin", "password", "nope"));
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }
    
    @Test
    void tokenEndpointRejectsUnknownUser() {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/auth/token", null,
            Map.of("username", "mallory", "password", PASSWORD));
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }
    
    @Test
    void tokenEndpointIssuesServerControlledRoles() {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/auth/token", null,
            Map.of("username", "admin", "password", PASSWORD, "roles", java.util.List.of("SUPERUSER")));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        // Roles come from the store, not the request — the bogus "SUPERUSER" is ignored.
        assertThat(roles(json(res).at("/data/roles"))).containsExactly("ADMIN");
    }
    
    @Test
    void refreshPreservesRoles() {
        JsonNode login = json(call(HttpMethod.POST, "/api/v1/auth/token", null,
            Map.of("username", "reviewer", "password", PASSWORD))).get("data");
        String refreshToken = login.get("refreshToken").asText();
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Refresh-Token", refreshToken);
        ResponseEntity<String> res = rest.exchange(url("/api/v1/auth/refresh"),
            HttpMethod.POST, new HttpEntity<>(headers), String.class);
        
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(roles(json(res).at("/data/roles"))).containsExactly("REVIEWER");
    }
    
    @Test
    void refreshRefusesAnAccessToken() {
        String accessToken = login("reviewer");
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Refresh-Token", accessToken);
        ResponseEntity<String> res = rest.exchange(url("/api/v1/auth/refresh"),
            HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }
    
    @Test
    void refreshTokenIsNotAnAccessToken() {
        String refreshToken = json(call(HttpMethod.POST, "/api/v1/auth/token", null,
            Map.of("username", "admin", "password", PASSWORD))).at("/data/refreshToken").asText();
        assertThat(call(HttpMethod.GET, "/api/v1/tasks", refreshToken, null).getStatusCode().value())
            .isEqualTo(401);
    }
    
    @Test
    void apiLoginIsRateLimitedPerClientAddress() {
        for (int i = 0; i < 10; i++) {
            assertThat(apiLoginFrom("203.0.113.10", "wrong").getStatusCode().value()).isEqualTo(401);
        }
        ResponseEntity<String> blocked = apiLoginFrom("203.0.113.10", PASSWORD);
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        // Other clients, and so the user, are unaffected.
        assertThat(apiLoginFrom("203.0.113.11", PASSWORD).getStatusCode().value()).isEqualTo(200);
    }
    
    @Test
    void cockpitLoginIsRateLimitedPerClientAndNeverLocksAdmin() {
        for (int i = 0; i < 10; i++) {
            assertThat(cockpitLoginFrom("203.0.113.20", "wrong").getStatusCode().value()).isEqualTo(401);
        }
        assertThat(cockpitLoginFrom("203.0.113.20", CAMUNDA_PASSWORD).getStatusCode().value()).isEqualTo(429);
        // Camunda's own lock is off: after 10 failed attempts admin can still log in at once.
        assertThat(cockpitLoginFrom("203.0.113.21", CAMUNDA_PASSWORD).getStatusCode().value()).isEqualTo(200);
    }
}
