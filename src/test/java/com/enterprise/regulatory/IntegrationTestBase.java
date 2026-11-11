package com.enterprise.regulatory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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
 * Shared setup for the end-to-end tests, which run the full application over real HTTP.
 *
 * <p>The application runs on a random free port, so the external task workers (which follow
 * the server's actual port) complete the automated steps and produce the human tasks the tests
 * act on. It has no built-in secrets, so the tests supply them (test-only values).
 *
 * <p>Every subclass inherits this exact configuration, so they all share one cached
 * application context: the application starts once for the whole suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "JWT_SECRET=" + IntegrationTestBase.TEST_JWT_SECRET,
    "CAMUNDA_ADMIN_PASSWORD=" + IntegrationTestBase.CAMUNDA_PASSWORD,
    "AUTH_DEFAULT_PASSWORD=" + IntegrationTestBase.PASSWORD,
    // Lets rate-limiting tests act as distinct clients without blocking the other tests' logins.
    "CLIENT_IP_HEADER=" + IntegrationTestBase.CLIENT_HEADER})
abstract class IntegrationTestBase {
    
    static final String PASSWORD = "Test-Api-Password-1";
    static final String CAMUNDA_PASSWORD = "test-camunda-admin-password";
    static final String CLIENT_HEADER = "X-Test-Client-Address";
    /**
     * 64 random bytes, Base64-encoded; used only by these tests.
     */
    static final String TEST_JWT_SECRET =
        "HgnK7rbBcZ4oBumFlRn28YIXjxgqZ2nomHJmZY0jDlaKmNyPjAZETiOsHzzTTb9mMnOcaINqN7fOFZQxZtSSQA==";
    /**
     * No automatic retries or cookie handling: HttpClient would otherwise wait out a 429's
     * {@code Retry-After} (15 minutes) and retry, and the tests manage cookies themselves.
     */
    protected final TestRestTemplate rest = new TestRestTemplate(new RestTemplateBuilder()
                                                                     .requestFactory(() -> new HttpComponentsClientHttpRequestFactory(
                                                                         HttpClients.custom().disableAutomaticRetries().disableCookieManagement().build())));
    @LocalServerPort
    protected int port;
    @Autowired
    protected ObjectMapper mapper;
    
    protected static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the other transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
    
    protected String login(String username) {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/auth/token", null,
            Map.of("username", username, "password", PASSWORD));
        assertThat(res.getStatusCode().value()).as("login for %s", username).isEqualTo(200);
        return json(res).at("/data/accessToken").asText();
    }
    
    protected String startWorkflow(String token, String title) {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/workflow/start", token,
            Map.of("requestTitle", title,
                "requestDescription", "integration test",
                "requestType", "OPERATIONAL_CHANGE",
                "department", "OPERATIONS",
                "priority", "NORMAL"));
        assertThat(res.getStatusCode().value()).as("start workflow").isEqualTo(201);
        return json(res).at("/data/processInstanceId").asText();
    }
    
    protected String requestIdOf(String token, String processInstanceId) {
        ResponseEntity<String> res = call(HttpMethod.GET,
            "/api/v1/workflow/status/" + processInstanceId, token, null);
        return json(res).at("/data/requestId").asText();
    }
    
    protected ResponseEntity<String> complete(String token, String taskId) {
        return call(HttpMethod.POST, "/api/v1/tasks/" + taskId + "/complete", token,
            Map.of("decision", "APPROVED", "comment", "ok"));
    }
    
    /**
     * Polls the caller's task queue until the task for the given BPMN step of the given
     * process appears (risk scoring must finish first).
     */
    protected String awaitTask(String token, String processInstanceId, String taskDefinitionKey) {
        return awaitTaskIn("/api/v1/tasks", token, processInstanceId, taskDefinitionKey);
    }
    
    /**
     * Like {@link #awaitTask}, but via the per-process task list (oversight roles).
     */
    protected String awaitTaskOfProcess(String token, String processInstanceId, String taskDefinitionKey) {
        return awaitTaskIn("/api/v1/tasks/process/" + processInstanceId, token, processInstanceId, taskDefinitionKey);
    }
    
    protected String awaitTaskIn(String path, String token, String processInstanceId, String taskDefinitionKey) {
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
    
    protected java.util.List<String> taskIdsOf(String token, String processInstanceId) {
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
    
    protected ResponseEntity<String> apiLoginFrom(String clientAddress, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(CLIENT_HEADER, clientAddress);
        return rest.exchange(url("/api/v1/auth/token"), HttpMethod.POST,
            new HttpEntity<>(Map.of("username", "reviewer", "password", password), headers), String.class);
    }
    
    /**
     * Logs in to the Camunda webapp as admin, with the session and CSRF token its login requires.
     */
    protected ResponseEntity<String> cockpitLoginFrom(String clientAddress, String password) {
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
    
    protected JsonNode awaitWorkflow(String token, String processInstanceId,
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
    
    protected java.util.List<JsonNode> auditEvents(String token, String processInstanceId, String eventType,
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
    
    protected JsonNode awaitAuditEvent(String token, String processInstanceId, String eventType, String taskName) {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            java.util.List<JsonNode> events = auditEvents(token, processInstanceId, eventType, taskName);
            if (!events.isEmpty()) {
                return events.getFirst();
            }
            sleep(500);
        }
        throw new AssertionError("No " + eventType + " audit event for " + processInstanceId);
    }
    
    protected ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }
    
    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
    
    protected JsonNode json(ResponseEntity<String> res) {
        try {
            return mapper.readTree(res.getBody());
        } catch (Exception e) {
            throw new AssertionError("Response body was not valid JSON: " + res.getBody(), e);
        }
    }
    
    protected java.util.List<String> roles(JsonNode rolesNode) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (rolesNode != null && rolesNode.isArray()) {
            rolesNode.forEach(n -> result.add(n.asText()));
        }
        return result;
    }
    
    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
