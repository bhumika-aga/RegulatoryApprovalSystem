package com.enterprise.regulatory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Main application class for the Regulatory Approval System.
 *
 * <p>
 * This Spring Boot application integrates with Camunda 7 BPMN engine to
 * implement
 * a multi-stage regulatory approval workflow with the following features:
 * </p>
 *
 * <ul>
 * <li>BPMN workflow: Reviewer → Manager → Compliance → Final Approval</li>
 * <li>SLA enforcement using timer boundary events</li>
 * <li>Escalation to senior management on SLA breach</li>
 * <li>JWT-based authentication with role-based authorization</li>
 * <li>External Task Workers for decoupled service execution</li>
 * <li>Comprehensive audit trail in PostgreSQL</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties
@Slf4j
public class RegulatoryApprovalApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegulatoryApprovalApplication.class, args);
        log.info("=================================================");
        log.info("  Regulatory Approval System Started Successfully");
        log.info("=================================================");
        log.info("  Swagger UI: http://localhost:8080/swagger-ui.html");
        log.info("  Camunda Webapp: http://localhost:8080/camunda");
        log.info("  Health Check: http://localhost:8080/api/v1/health");
        log.info("=================================================");
    }
}
