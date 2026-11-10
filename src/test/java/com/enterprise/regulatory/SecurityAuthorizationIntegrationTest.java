package com.enterprise.regulatory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.runtime.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end authentication and authorization tests over real HTTP.
 *
 * <p>Runs the full application, on a random free port, so the external task
 * workers (which follow the server's actual port) can complete the automated steps
 * and produce the human tasks the authorization checks act on. The application has no built-in secrets,
 * so the test supplies them (test-only values).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "JWT_SECRET=" + SecurityAuthorizationIntegrationTest.TEST_JWT_SECRET,
    "CAMUNDA_ADMIN_PASSWORD=" + SecurityAuthorizationIntegrationTest.CAMUNDA_PASSWORD,
    "AUTH_DEFAULT_PASSWORD=" + SecurityAuthorizationIntegrationTest.PASSWORD,
    // Lets rate-limiting tests act as distinct clients without blocking the other tests' logins.
    "CLIENT_IP_HEADER=" + SecurityAuthorizationIntegrationTest.CLIENT_HEADER})
class SecurityAuthorizationIntegrationTest {
    
    static final String PASSWORD = "Test-Api-Password-1";
    static final String CAMUNDA_PASSWORD = "test-camunda-admin-password";
    static final String CLIENT_HEADER = "X-Test-Client-Address";
    /**
     * 64 random bytes, Base64-encoded; used only by this test.
     */
    static final String TEST_JWT_SECRET =
        "HgnK7rbBcZ4oBumFlRn28YIXjxgqZ2nomHJmZY0jDlaKmNyPjAZETiOsHzzTTb9mMnOcaINqN7fOFZQxZtSSQA==";
    
    @LocalServerPort
    private int port;
    
    /**
     * No automatic retries or cookie handling: HttpClient would otherwise wait out a 429's
     * {@code Retry-After} (15 minutes) and retry, and the tests manage cookies themselves.
     */
    private final TestRestTemplate rest = new TestRestTemplate(new RestTemplateBuilder()
        .requestFactory(() -> new HttpComponentsClientHttpRequestFactory(
            HttpClients.custom().disableAutomaticRetries().disableCookieManagement().build())));
    
    @Autowired
    private ObjectMapper mapper;
    
    @Autowired
    private ManagementService managementService;
    
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
    
    // ---------------------------------------------------------------- Exposed surface
    
    @Test
    void engineRestIsOpenToThisHostOnly() {
        // The in-process workers call it from localhost without credentials ...
        assertThat(call(HttpMethod.GET, "/engine-rest/process-definition", null, null)
                       .getStatusCode().value()).isEqualTo(200);
        // ... while a request relayed by a proxy on behalf of another client is refused.
        HttpHeaders proxied = new HttpHeaders();
        proxied.set("X-Forwarded-For", "203.0.113.7");
        assertThat(rest.exchange(url("/engine-rest/process-definition"), HttpMethod.GET,
            new HttpEntity<>(proxied), String.class).getStatusCode().value()).isEqualTo(403);
    }
    
    @Test
    void tasklistIsClosed() {
        assertThat(call(HttpMethod.GET, "/camunda/app/tasklist/default/", null, null)
                       .getStatusCode().value()).isIn(401, 403);
        assertThat(call(HttpMethod.POST, "/camunda/api/engine/engine/default/task/some-id/complete", null, Map.of())
                       .getStatusCode().value()).isIn(401, 403);
        // Cockpit itself stays available.
        assertThat(call(HttpMethod.GET, "/camunda/app/cockpit/default/", null, null)
                       .getStatusCode().value()).isEqualTo(200);
    }
    
    @Test
    void apiDocsAreOffByDefault() {
        assertThat(call(HttpMethod.GET, "/v3/api-docs", null, null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/swagger-ui.html", null, null).getStatusCode().value()).isEqualTo(404);
    }
    
    // ---------------------------------------------------------------- Task authorization
    
    @Test
    void enforcesCandidateGroupOnTaskCompletion() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String manager = login("manager");
        
        // Submitted by the admin, so neither reviewer nor manager is the submitter.
        String pid = startWorkflow(admin, "Task authz flow");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        
        // A manager is not a candidate for the REVIEWER's Initial Review task.
        assertThat(complete(manager, initialReview).getStatusCode().value()).isEqualTo(403);
        // The reviewer is.
        assertThat(complete(reviewer, initialReview).getStatusCode().value()).isEqualTo(200);
        
        // Next stage is Manager Approval (candidate MANAGER).
        String managerApproval = awaitTask(manager, pid, "Task_ManagerApproval");
        assertThat(complete(reviewer, managerApproval).getStatusCode().value()).isEqualTo(403);
        assertThat(complete(manager, managerApproval).getStatusCode().value()).isEqualTo(200);
    }
    
    // ---------------------------------------------------------------- Separation of duties
    
    @Test
    void submitterCannotApproveTheirOwnRequest() {
        String reviewer = login("reviewer");
        String manager = login("manager");
        
        // The manager submits; the reviewer (not the submitter) approves Initial Review.
        String pid = startWorkflow(manager, "Own request");
        complete(reviewer, awaitTask(reviewer, pid, "Task_InitialReview"));
        
        // Manager Approval is a MANAGER task, but this manager submitted the request:
        // it is not offered to them, and claiming or completing it is refused.
        String managerApproval = awaitTaskOfProcess(login("admin"), pid, "Task_ManagerApproval");
        assertThat(taskIdsOf(manager, pid)).doesNotContain(managerApproval);
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + managerApproval + "/claim", manager, null)
                       .getStatusCode().value()).isEqualTo(403);
        ResponseEntity<String> res = complete(manager, managerApproval);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody()).contains("Separation of duties");
    }
    
    @Test
    void submitterCanStillProvideRequestedInformation() {
        String reviewer = login("reviewer");
        String manager = login("manager");
        
        // "Provide Additional Information" is assigned to the submitter by design.
        String pid = startWorkflow(manager, "Needs info");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + initialReview + "/complete", reviewer,
            Map.of("decision", "NEEDS_INFO")).getStatusCode().value()).isEqualTo(200);
        assertThat(complete(manager, awaitTask(manager, pid, "Task_RequestInfo")).getStatusCode().value())
            .isEqualTo(200);
    }
    
    // ---------------------------------------------------------------- Process variable injection
    
    @Test
    void clientCannotSetProcessVariables() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String manager = login("manager");
        
        String pid = startWorkflow(admin, "Variable injection attempt");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        // Extra properties such as additionalVariables are ignored.
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + initialReview + "/complete", reviewer,
            Map.of("decision", "APPROVED",
                "additionalVariables", Map.of("riskScore", "abc", "submitterId", "someone-else")))
                       .getStatusCode().value()).isEqualTo(200);
        
        // Previously an injected non-numeric riskScore broke every manager's task list (500).
        String managerApproval = awaitTask(manager, pid, "Task_ManagerApproval");
        JsonNode task = json(call(HttpMethod.GET, "/api/v1/tasks/" + managerApproval, manager, null)).get("data");
        assertThat(task.get("riskScore").isInt()).isTrue();
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
    
    // ---------------------------------------------------------------- Client errors
    
    @Test
    void clientErrorsGetTheirOwnStatusNot500() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        // Malformed JSON
        assertThat(rest.exchange(url("/api/v1/auth/token"), HttpMethod.POST,
            new HttpEntity<>("{not json", json), String.class).getStatusCode().value()).isEqualTo(400);
        // Missing required header
        assertThat(rest.exchange(url("/api/v1/auth/refresh"), HttpMethod.POST,
            HttpEntity.EMPTY, String.class).getStatusCode().value()).isEqualTo(400);
        // Wrong method
        assertThat(call(HttpMethod.GET, "/api/v1/auth/token", null, null).getStatusCode().value()).isEqualTo(405);
        // Unsupported media type
        HttpHeaders text = new HttpHeaders();
        text.setContentType(MediaType.TEXT_PLAIN);
        assertThat(rest.exchange(url("/api/v1/auth/token"), HttpMethod.POST,
            new HttpEntity<>("admin", text), String.class).getStatusCode().value()).isEqualTo(415);
    }
    
    // ---------------------------------------------------------------- Decisions, stages, audit
    
    @Test
    void refusesADecisionTheTaskCannotRoute() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String pid = startWorkflow(admin, "Unroutable decision");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/tasks/" + initialReview + "/complete", reviewer,
            Map.of("decision", "ESCALATE"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).contains("APPROVED", "REJECTED", "NEEDS_INFO");
        // The task is still open and can be completed with a valid decision.
        assertThat(complete(reviewer, initialReview).getStatusCode().value()).isEqualTo(200);
    }
    
    @Test
    void stageFollowsTheDecisionAndCompletionIsAuditedOnce() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String auditor = login("auditor");
        String pid = startWorkflow(admin, "Rejected at initial review");
        
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + awaitTask(reviewer, pid, "Task_InitialReview")
                                             + "/complete", reviewer, Map.of("decision", "REJECTED"))
                       .getStatusCode().value()).isEqualTo(200);
        
        JsonNode status = awaitWorkflow(admin, pid, w -> "REJECTED".equals(w.path("status").asText()));
        assertThat(status.path("currentStage").asText()).isEqualTo("COMPLETED");
        assertThat(status.path("rejectionReason").asText()).contains("Initial Review");
        
        awaitAuditEvent(auditor, pid, "WORKFLOW_COMPLETED", null);
        sleep(1000); // let any late asynchronous audit writes land
        assertThat(auditEvents(auditor, pid, "WORKFLOW_COMPLETED", null)).hasSize(1);
    }
    
    @Test
    void seniorManagerDecisionIsAuditedAndComplianceResultRecorded() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String manager = login("manager");
        String seniorManager = login("senior.manager");
        String auditor = login("auditor");
        String pid = startWorkflow(admin, "Escalated to senior manager");
        
        complete(reviewer, awaitTask(reviewer, pid, "Task_InitialReview"));
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + awaitTask(manager, pid, "Task_ManagerApproval")
                                             + "/complete", manager, Map.of("decision", "ESCALATE"))
                       .getStatusCode().value()).isEqualTo(200);
        assertThat(awaitWorkflow(admin, pid, w -> true).path("currentStage").asText())
            .isEqualTo("SENIOR_MANAGER_REVIEW");
        complete(seniorManager, awaitTask(seniorManager, pid, "Task_SeniorManagerReview"));
        
        // The audit shows the senior manager's own decision, not the manager's ESCALATE.
        JsonNode decision = awaitAuditEvent(auditor, pid, "DECISION_MADE", "Senior Manager Review");
        assertThat(decision.path("newValue").asText()).isEqualTo("APPROVED");
        assertThat(decision.path("role").asText()).isEqualTo("SENIOR_MANAGER");
        
        // The automated compliance check records its result on the request.
        JsonNode workflow = awaitWorkflow(admin, pid, w -> !w.path("complianceResult").isMissingNode()
                                                               && !w.path("complianceResult").isNull());
        assertThat(workflow.path("complianceResult").asText()).isIn("PASS", "FAIL", "REQUIRES_ADDITIONAL_INFO");
    }
    
    @Test
    void slaEscalationHandsTheTaskToTheEscalationTarget() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String manager = login("manager");
        String pid = startWorkflow(admin, "SLA breach");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        // The reviewer claims it, then sits on it past the 8-hour SLA.
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + initialReview + "/claim", reviewer, null)
                       .getStatusCode().value()).isEqualTo(200);
        
        Job slaTimer = managementService.createJobQuery().processInstanceId(pid).timers().singleResult();
        managementService.executeJob(slaTimer.getId());
        
        // Escalated to MANAGER: the claim is released and a manager can now complete it.
        assertThat(awaitTask(manager, pid, "Task_InitialReview")).isEqualTo(initialReview);
        assertThat(complete(manager, initialReview).getStatusCode().value()).isEqualTo(200);
        assertThat(awaitWorkflow(admin, pid, w -> w.path("escalated").asBoolean()).path("escalationReason").asText())
            .contains("MANAGER");
    }
    
    // ---------------------------------------------------------------- Actuator
    
    @Test
    void metricsNeedAnAdminToken() {
        assertThat(call(HttpMethod.GET, "/actuator/metrics", null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/actuator/metrics", login("reviewer"), null).getStatusCode().value())
            .isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/actuator/metrics", login("admin"), null).getStatusCode().value())
            .isEqualTo(200);
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
    
    /**
     * Polls the caller's task queue until the task for the given BPMN step of the given
     * process appears (risk scoring must finish first).
     */
    private String awaitTask(String token, String processInstanceId, String taskDefinitionKey) {
        return awaitTaskIn("/api/v1/tasks", token, processInstanceId, taskDefinitionKey);
    }
    
    /**
     * Like {@link #awaitTask}, but via the per-process task list (oversight roles).
     */
    private String awaitTaskOfProcess(String token, String processInstanceId, String taskDefinitionKey) {
        return awaitTaskIn("/api/v1/tasks/process/" + processInstanceId, token, processInstanceId, taskDefinitionKey);
    }
    
    private String awaitTaskIn(String path, String token, String processInstanceId, String taskDefinitionKey) {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode data = json(call(HttpMethod.GET, path, token, null)).get("data");
            if (data != null && data.isArray()) {
                for (JsonNode task : data) {
                    if (processInstanceId.equals(task.get("processInstanceId").asText())
                            && taskDefinitionKey.equals(task.get("taskDefinitionKey").asText())) {
                        return task.get("taskId").asText();
                    }
                }
            }
            sleep(500);
        }
        throw new AssertionError("Task " + taskDefinitionKey + " of process " + processInstanceId
                                     + " did not become available within the timeout");
    }
    
    private java.util.List<String> taskIdsOf(String token, String processInstanceId) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        JsonNode data = json(call(HttpMethod.GET, "/api/v1/tasks", token, null)).get("data");
        if (data != null) {
            data.forEach(t -> {
                if (processInstanceId.equals(t.get("processInstanceId").asText())) {
                    ids.add(t.get("taskId").asText());
                }
            });
        }
        return ids;
    }
    
    private ResponseEntity<String> apiLoginFrom(String clientAddress, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(CLIENT_HEADER, clientAddress);
        return rest.exchange(url("/api/v1/auth/token"), HttpMethod.POST,
            new HttpEntity<>(Map.of("username", "reviewer", "password", password), headers), String.class);
    }
    
    /**
     * Logs in to the Camunda webapp as admin, with the session and CSRF token its login requires.
     */
    private ResponseEntity<String> cockpitLoginFrom(String clientAddress, String password) {
        ResponseEntity<String> page = rest.getForEntity(url("/camunda/app/cockpit/default/"), String.class);
        java.util.List<String> cookies = new java.util.ArrayList<>();
        String csrfToken = null;
        for (String setCookie : page.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE)) {
            String cookie = setCookie.split(";", 2)[0];
            cookies.add(cookie);
            if (cookie.startsWith("XSRF-TOKEN=")) {
                csrfToken = cookie.substring("XSRF-TOKEN=".length());
            }
        }
        assertThat(csrfToken).as("Camunda CSRF token").isNotNull();
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.COOKIE, String.join("; ", cookies));
        headers.set("X-XSRF-TOKEN", csrfToken);
        headers.set(CLIENT_HEADER, clientAddress);
        return rest.exchange(url("/camunda/api/admin/auth/user/default/login/cockpit"), HttpMethod.POST,
            new HttpEntity<>("username=admin&password=" + password, headers), String.class);
    }
    
    private JsonNode awaitWorkflow(String token, String processInstanceId,
                                   java.util.function.Predicate<JsonNode> condition) {
        long deadline = System.currentTimeMillis() + 30_000L;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = json(call(HttpMethod.GET, "/api/v1/workflow/status/" + processInstanceId, token, null)).get("data");
            if (last != null && condition.test(last)) {
                return last;
            }
            sleep(500);
        }
        throw new AssertionError("Workflow " + processInstanceId + " did not reach the expected state: " + last);
    }
    
    private java.util.List<JsonNode> auditEvents(String token, String processInstanceId, String eventType,
                                                 String taskName) {
        java.util.List<JsonNode> events = new java.util.ArrayList<>();
        JsonNode data = json(call(HttpMethod.GET, "/api/v1/audit/process/" + processInstanceId, token, null))
                            .get("data");
        if (data != null) {
            data.forEach(e -> {
                if (eventType.equals(e.path("eventType").asText())
                        && (taskName == null || taskName.equals(e.path("taskName").asText()))) {
                    events.add(e);
                }
            });
        }
        return events;
    }
    
    private JsonNode awaitAuditEvent(String token, String processInstanceId, String eventType, String taskName) {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            java.util.List<JsonNode> events = auditEvents(token, processInstanceId, eventType, taskName);
            if (!events.isEmpty()) {
                return events.get(0);
            }
            sleep(500);
        }
        throw new AssertionError("No " + eventType + " audit event for " + processInstanceId);
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
