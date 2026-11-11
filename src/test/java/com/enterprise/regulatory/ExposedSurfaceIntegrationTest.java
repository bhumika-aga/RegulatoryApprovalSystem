package com.enterprise.regulatory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the application exposes and how it answers: the engine REST API, Tasklist, API
 * docs, actuator, client errors, and log output.
 */
class ExposedSurfaceIntegrationTest extends IntegrationTestBase {
    
    @Autowired
    private org.springframework.core.env.Environment environment;
    
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
    
    @Test
    void metricsNeedAnAdminToken() {
        assertThat(call(HttpMethod.GET, "/actuator/metrics", null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/actuator/metrics", login("reviewer"), null).getStatusCode().value())
            .isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/actuator/metrics", login("admin"), null).getStatusCode().value())
            .isEqualTo(200);
    }
    
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
    
    @Test
    void logLinesCannotBeForgedThroughRequestValues() {
        var context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var layout = new ch.qos.logback.classic.PatternLayout();
        layout.setContext(context);
        layout.setPattern(environment.getProperty("logging.pattern.console"));
        layout.start();
        var event = new ch.qos.logback.classic.spi.LoggingEvent("test",
            context.getLogger("test"), ch.qos.logback.classic.Level.WARN,
            "Task {} denied", null, new Object[]{"t1\r\n2026-01-01 00:00:00 [main] INFO  fake - admin approved"});
        
        String line = layout.doLayout(event);
        // One line only: the injected line break became a space.
        assertThat(line.strip()).doesNotContain("\r", "\n");
        assertThat(line).endsWith(System.lineSeparator()).contains("Task t1 2026-01-01");
    }
}
