package com.enterprise.regulatory.worker;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.backoff.ExponentialBackoffStrategy;
import org.camunda.bpm.client.interceptor.auth.BasicAuthProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Camunda External Task Client.
 * Manages the connection to Camunda engine for external task workers.
 */
@Configuration
@Slf4j
public class ExternalTaskWorkerConfig {
    
    @Value("${camunda.bpm.client.base-url:http://localhost:8080/engine-rest}")
    private String camundaBaseUrl;
    
    @Value("${camunda.bpm.client.async-response-timeout:30000}")
    private long asyncResponseTimeout;
    
    @Value("${camunda.bpm.client.lock-duration:60000}")
    private long lockDuration;
    
    @Value("${camunda.bpm.client.max-tasks:10}")
    private int maxTasks;
    
    @Value("${camunda.bpm.client.worker-id:regulatory-worker}")
    private String workerId;
    
    // Credentials the client uses to authenticate against the (now protected) engine
    // REST API. Default to the engine admin user so a single secret governs both the
    // Cockpit login and worker access.
    @Value("${camunda.bpm.admin-user.id:admin}")
    private String engineAuthUsername;
    
    @Value("${camunda.bpm.admin-user.password:admin}")
    private String engineAuthPassword;
    
    @Bean
    public ExternalTaskClient externalTaskClient() {
        log.info("Initializing External Task Client with base URL: {}", camundaBaseUrl);
        
        return ExternalTaskClient.create()
                   .baseUrl(camundaBaseUrl)
                   .workerId(workerId)
                   .asyncResponseTimeout(asyncResponseTimeout)
                   .lockDuration(lockDuration)
                   .maxTasks(maxTasks)
                   .backoffStrategy(new ExponentialBackoffStrategy(500, 2, 10000))
                   .addInterceptor(new BasicAuthProvider(engineAuthUsername, engineAuthPassword))
                   .disableAutoFetching()
                   .build();
    }
}
