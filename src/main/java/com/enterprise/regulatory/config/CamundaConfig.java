package com.enterprise.regulatory.config;

import org.camunda.bpm.spring.boot.starter.configuration.impl.AbstractCamundaConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Camunda BPM engine configuration.
 *
 * Note: RuntimeService, TaskService, and RepositoryService beans are
 * automatically
 * provided by Camunda's Spring Boot Starter auto-configuration.
 */
@Configuration
public class CamundaConfig extends AbstractCamundaConfiguration {

}
