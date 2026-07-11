package com.enterprise.regulatory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end authentication and authorization tests over real HTTP.
 *
 * <p>Runs the full application on the configured port so the external task
 * workers can complete the automated risk-scoring step and produce the human
 * tasks the authorization checks act on. Uses the seeded demo users and their
 * default password.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class SecurityAuthorizationIntegrationTest {

    private static final String PASSWORD = "ChangeMe123!";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    // ---------------------------------------------------------------- Authentication

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

    // ---------------------------------------------------------------- Task authorization

    @Test
    void enforcesCandidateGroupOnTaskCompletion() {
        String reviewer = login("reviewer");
        String manager = login("manager");

        startWorkflow(reviewer, "Task authz flow");
        String initialReview = awaitFirstTaskId(reviewer);

        // A manager is not a candidate for the REVIEWER's Initial Review task.
        assertThat(complete(manager, initialReview).getStatusCode().value()).isEqualTo(403);
        // The reviewer is.
        assertThat(complete(reviewer, initialReview).getStatusCode().value()).isEqualTo(200);

        // Next stage is Manager Approval (candidate MANAGER).
        String managerApproval = awaitFirstTaskId(manager);
        assertThat(complete(reviewer, managerApproval).getStatusCode().value()).isEqualTo(403);
        assertThat(complete(manager, managerApproval).getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- Read scoping (IDOR)

    @Test
    void scopesSingleItemReadsToOwnerOrOversight() {
        String reviewer = login("reviewer");
        String manager = login("manager");
        String auditor = login("auditor");

        // Manager submits a request; reviewer is neither submitter nor oversight.
        String pid = startWorkflow(manager, "IDOR flow");
        String requestId = requestIdOf(manager, pid);

        assertThat(call(HttpMethod.GET, "/api/v1/workflow/" + requestId, reviewer, null)
            .getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/v1/workflow/status/" + pid, reviewer, null)
            .getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/v1/tasks/process/" + pid, reviewer, null)
            .getStatusCode().value()).isEqualTo(403);

        // Submitter and oversight roles may read it.
        assertThat(call(HttpMethod.GET, "/api/v1/workflow/" + requestId, manager, null)
            .getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/workflow/" + requestId, auditor, null)
            .getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- Helpers

    private String login(String username) {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/auth/token", null,
            Map.of("username", username, "password", PASSWORD));
        assertThat(res.getStatusCode().value()).as("login for %s", username).isEqualTo(200);
        return json(res).at("/data/accessToken").asText();
    }

    private String startWorkflow(String token, String title) {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/workflow/start", token,
            Map.of("requestTitle", title,
                "requestDescription", "integration test",
                "requestType", "OPERATIONAL_CHANGE",
                "department", "OPERATIONS",
                "priority", "NORMAL"));
        assertThat(res.getStatusCode().value()).as("start workflow").isEqualTo(201);
        return json(res).at("/data/processInstanceId").asText();
    }

    private String requestIdOf(String token, String processInstanceId) {
        ResponseEntity<String> res = call(HttpMethod.GET,
            "/api/v1/workflow/status/" + processInstanceId, token, null);
        return json(res).at("/data/requestId").asText();
    }

    private ResponseEntity<String> complete(String token, String taskId) {
        return call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/complete", token,
            Map.of("decision", "APPROVED", "comment", "ok"));
    }

    /** Polls the caller's task queue until a task is available (risk scoring must finish first). */
    private String awaitFirstTaskId(String token) {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/tasks", token, null);
            JsonNode data = json(res).get("data");
            if (data != null && data.isArray() && !data.isEmpty()) {
                return data.get(0).get("taskId").asText();
            }
            sleep(500);
        }
        throw new AssertionError("No task became available within the timeout");
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode json(ResponseEntity<String> res) {
        try {
            return mapper.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response body was not valid JSON: " + res.getBody(), e);
        }
    }

    private java.util.List<String> roles(JsonNode rolesNode) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (rolesNode != null && rolesNode.isArray()) {
            rolesNode.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
