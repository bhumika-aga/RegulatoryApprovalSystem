package com.enterprise.regulatory.worker;

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.client.topic.TopicSubscription;
import org.springframework.stereotype.Component;

import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.model.enums.ComplianceResult;
import com.enterprise.regulatory.service.AuditService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * External Task Worker for Compliance Checks.
 * Performs regulatory compliance validation on submitted requests.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComplianceCheckWorker implements ExternalTaskHandler {

    private static final String TOPIC_NAME = "compliance-check";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_TIMEOUT = 10000L;

    private final ExternalTaskClient externalTaskClient;
    private final AuditService auditService;
    private TopicSubscription subscription;

    @PostConstruct
    public void subscribe() {
        log.info("Subscribing to topic: {}", TOPIC_NAME);
        subscription = externalTaskClient.subscribe(TOPIC_NAME)
                .lockDuration(60000)
                .handler(this)
                .open();
        externalTaskClient.start();
    }

    @PreDestroy
    public void unsubscribe() {
        if (subscription != null) {
            subscription.close();
        }
    }

    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        String processInstanceId = externalTask.getProcessInstanceId();
        String requestId = externalTask.getVariable("requestId");
        String requestType = externalTask.getVariable("requestType");
        Integer riskScore = externalTask.getVariable("riskScore");

        log.info("Processing compliance check for request: {}, type: {}, riskScore: {}",
                requestId, requestType, riskScore);

        try {
            ComplianceResult result = performComplianceCheck(requestId, requestType, riskScore);

            Map<String, Object> variables = new HashMap<>();
            variables.put("complianceResult", result.name());
            variables.put("complianceCheckTimestamp", System.currentTimeMillis());

            if (result == ComplianceResult.PASS) {
                variables.put("complianceComment", "Automated compliance check passed");
                auditService.recordAuditEvent(
                        processInstanceId,
                        externalTask.getId(),
                        "Compliance Check",
                        AuditEventType.COMPLIANCE_CHECK_PASSED,
                        null,
                        result.name(),
                        "system",
                        "COMPLIANCE",
                        "Automated compliance verification completed successfully");
            } else if (result == ComplianceResult.FAIL) {
                variables.put("complianceComment",
                        "Automated compliance check failed - regulatory requirements not met");
                auditService.recordAuditEvent(
                        processInstanceId,
                        externalTask.getId(),
                        "Compliance Check",
                        AuditEventType.COMPLIANCE_CHECK_FAILED,
                        null,
                        result.name(),
                        "system",
                        "COMPLIANCE",
                        "Automated compliance verification failed");
            } else {
                variables.put("complianceComment", "Requires manual compliance review");
            }

            externalTaskService.complete(externalTask, variables);
            log.info("Compliance check completed for request: {} with result: {}", requestId, result);

        } catch (Exception e) {
            log.error("Error during compliance check for request: {}", requestId, e);
            handleFailure(externalTask, externalTaskService, e);
        }
    }

    private ComplianceResult performComplianceCheck(String requestId, String requestType, Integer riskScore) {
        // Simulate compliance check logic
        // In production, this would call external compliance systems

        if (riskScore == null) {
            riskScore = 50; // Default risk score
        }

        // High-risk requests need manual review
        if (riskScore > 80) {
            return ComplianceResult.REQUIRES_ADDITIONAL_INFO;
        }

        // Medium-risk requests based on type
        if (riskScore > 60) {
            if ("FINANCIAL_PRODUCT".equals(requestType) || "REGULATORY_CHANGE".equals(requestType)) {
                return ComplianceResult.REQUIRES_ADDITIONAL_INFO;
            }
        }

        // Low-risk requests auto-pass
        if (riskScore < 30) {
            return ComplianceResult.PASS;
        }

        // Standard compliance checks for medium-risk
        boolean documentsComplete = checkDocumentCompleteness(requestId);
        boolean regulatoryRequirementsMet = checkRegulatoryRequirements(requestType);

        if (documentsComplete && regulatoryRequirementsMet) {
            return ComplianceResult.PASS;
        } else if (!documentsComplete) {
            return ComplianceResult.REQUIRES_ADDITIONAL_INFO;
        } else {
            return ComplianceResult.FAIL;
        }
    }

    private boolean checkDocumentCompleteness(String requestId) {
        // Simulate document completeness check
        return requestId != null && !requestId.isEmpty();
    }

    private boolean checkRegulatoryRequirements(String requestType) {
        // Simulate regulatory requirement validation
        // In production, this would validate against specific regulatory rules
        return requestType != null && !requestType.equals("PROHIBITED");
    }

    private void handleFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception e) {
        int retries = externalTask.getRetries() != null ? externalTask.getRetries() : MAX_RETRIES;

        if (retries > 0) {
            log.warn("Retrying compliance check, remaining retries: {}", retries - 1);
            externalTaskService.handleFailure(
                    externalTask,
                    "Compliance check failed: " + e.getMessage(),
                    e.getClass().getName(),
                    retries - 1,
                    RETRY_TIMEOUT);
        } else {
            log.error("Max retries exceeded for compliance check, creating incident");
            externalTaskService.handleBpmnError(
                    externalTask,
                    "COMPLIANCE_ERROR",
                    "Compliance check failed after max retries: " + e.getMessage());
        }
    }
}
