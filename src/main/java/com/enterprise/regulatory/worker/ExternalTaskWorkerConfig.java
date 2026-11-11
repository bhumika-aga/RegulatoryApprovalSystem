package com.enterprise.regulatory.worker;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.UrlResolver;
import org.camunda.bpm.client.backoff.ExponentialBackoffStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Configuration for Camunda External Task Client.
 * Manages the connection to Camunda engine for external task workers.
 *
 * <p>The client calls the engine REST API over {@code localhost} without credentials: that
 * API is reachable from this host only (see {@code CamundaSecurityConfig}), and not logging
 * in means Camunda's failed-login lockout can never stall the workers.
 *
 * <p>Unless {@code camunda.bpm.client.base-url} is set, the URL follows the port this server
 * actually listens on, resolved on each request; so it also works with a random port
 * ({@code server.port=0}), as the integration tests use.
 */
@Configuration
@Slf4j
public class ExternalTaskWorkerConfig {
    
    @Value("${camunda.bpm.client.base-url:}")
    private String configuredBaseUrl;
    
    @Value("${camunda.bpm.client.async-response-timeout:5000}")
    private long asyncResponseTimeout;
    
    @Value("${camunda.bpm.client.max-tasks:10}")
    private int maxTasks;
    
    @Value("${camunda.bpm.client.worker-id:regulatory-worker}")
    private String workerId;
    
    @Bean
    public ExternalTaskClient externalTaskClient(Environment environment) {
        UrlResolver baseUrl = configuredBaseUrl.isBlank()
                                  ? () -> "http://localhost:" + environment.getProperty("local.server.port",
            environment.getProperty("server.port", "8080")) + "/engine-rest"
                                  : () -> configuredBaseUrl;
        log.info("Initializing External Task Client against {}",
            configuredBaseUrl.isBlank() ? "this server's engine REST API" : configuredBaseUrl);
        
        return ExternalTaskClient.create()
                   .urlResolver(baseUrl)
                   .workerId(workerId)
                   .asyncResponseTimeout(asyncResponseTimeout)
                   .maxTasks(maxTasks)
                   .backoffStrategy(new ExponentialBackoffStrategy(500, 2, 10000))
                   .disableAutoFetching()
                   .build();
    }
}
